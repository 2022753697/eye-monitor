package com.eyemonitor.service;

import com.eyemonitor.entity.PairEntity;
import com.eyemonitor.entity.TaskEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.repository.PairRepo;
import com.eyemonitor.repository.TaskRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 情侣任务状态机（服务端为状态权威）。
 * <p>
 * 状态机：PENDING → ACCEPTED → COMPLETED（发布方确认）→ REWARDED（接收方确认）
 *                       ↘ REJECTED（含理由）
 * 权限：仅接收方可 accept/reject/reward；仅发布方可 complete。
 * 幂等：重复的 publish/respond 直接放行转发（客户端按 taskId 去重），非法迁移返回错误。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    public static final String MSG_PUBLISH = "task_publish";
    public static final String MSG_RESPOND = "task_respond";
    public static final String MSG_COMPLETE = "task_complete";
    public static final String MSG_REWARD = "task_reward";

    private final TaskRepo taskRepo;
    private final PairRepo pairRepo;

    public TaskService(TaskRepo taskRepo, PairRepo pairRepo) {
        this.taskRepo = taskRepo;
        this.pairRepo = pairRepo;
    }

    /** 处理结果：forward=true 时 handler 转发给 peer；error 非空时回 system_tip 给发送方 */
    public static class Result {
        public final boolean forward;
        public final String error;
        public Result(boolean forward, String error) {
            this.forward = forward;
            this.error = error;
        }
    }

    public Result process(WsMessage msg, long userId, String pairCode) {
        if (msg.getType() == null || pairCode == null) {
            return new Result(false, "任务消息缺少必要字段");
        }
        try {
            switch (msg.getType()) {
                case MSG_PUBLISH:
                    return handlePublish(msg, userId, pairCode);
                case MSG_RESPOND:
                    return handleRespond(msg, userId, pairCode);
                case MSG_COMPLETE:
                    return handleComplete(msg, userId, pairCode);
                case MSG_REWARD:
                    return handleReward(msg, userId, pairCode);
                default:
                    return new Result(false, "未知任务消息类型: " + msg.getType());
            }
        } catch (Exception ex) {
            log.error("任务处理异常 type={} userId={}", msg.getType(), userId, ex);
            return new Result(false, "任务处理失败");
        }
    }

    // --- 各类型处理 ---

    private Result handlePublish(WsMessage msg, long userId, String pairCode) {
        Map<String, Object> payload = msg.getPayload();
        String taskId = str(payload, "taskId");
        if (taskId == null || taskId.isBlank()) {
            return new Result(false, "任务缺少 taskId");
        }
        TaskEntity existing = taskRepo.findByTaskId(taskId);
        if (existing != null) {
            // 幂等：重复发布（重连重发等）不再落库，直接放行转发（客户端去重）
            log.info("任务重复发布，跳过落库 taskId={}", taskId);
            return new Result(true, null);
        }
        PairEntity pair = pairRepo.findByPairCode(pairCode);
        if (pair == null || !pair.belongs(userId)) {
            return new Result(false, "配对不存在或无权发布");
        }
        TaskEntity e = new TaskEntity();
        e.setTaskId(taskId);
        e.setPairCode(pairCode);
        e.setPublisherUser(userId);
        e.setReceiverUser(pair.peerOf(userId));
        e.setContentText(str(payload, "content"));
        e.setMediaFileId(str(payload, "mediaFileId"));
        e.setRewardType(str(payload, "rewardType"));
        e.setRewardText(str(payload, "rewardText"));
        e.setPeerName(str(payload, "from"));
        e.setStatus(TaskEntity.STATUS_PENDING);
        e.setTs(msg.getTimestamp() > 0 ? msg.getTimestamp() : System.currentTimeMillis());
        taskRepo.save(e);
        log.info("任务发布落库 taskId={} pair={} publisher={}", taskId, pairCode, userId);
        return new Result(true, null);
    }

    private Result handleRespond(WsMessage msg, long userId, String pairCode) {
        Map<String, Object> payload = msg.getPayload();
        String taskId = str(payload, "taskId");
        String action = str(payload, "action");
        TaskEntity task = taskRepo.findByTaskId(taskId);
        if (task == null || !pairCode.equals(task.getPairCode())) {
            return new Result(false, "任务不存在");
        }
        if (!isUser(task, userId, task.getReceiverUser())) {
            return new Result(false, "只有接收方可以接受/拒绝任务");
        }
        boolean accept = "accept".equals(action);
        boolean reject = "reject".equals(action);
        if (!accept && !reject) {
            return new Result(false, "响应动作无效");
        }
        if (TaskEntity.STATUS_ACCEPTED.equals(task.getStatus())) {
            if (accept) return new Result(true, null); // 幂等：已接受重复确认直接放行
        }
        if (TaskEntity.STATUS_REJECTED.equals(task.getStatus()) && reject) {
            return new Result(true, null); // 幂等
        }
        if (!TaskEntity.STATUS_PENDING.equals(task.getStatus())) {
            return new Result(false, "任务已响应，无法重复操作");
        }
        if (reject) {
            String reason = str(payload, "reason");
            if (reason == null || reason.isBlank()) {
                return new Result(false, "拒绝需要填写理由");
            }
            task.setReason(reason);
            task.setStatus(TaskEntity.STATUS_REJECTED);
        } else {
            task.setStatus(TaskEntity.STATUS_ACCEPTED);
        }
        taskRepo.save(task);
        log.info("任务响应 taskId={} action={} userId={}", taskId, action, userId);
        return new Result(true, null);
    }

    private Result handleComplete(WsMessage msg, long userId, String pairCode) {
        String taskId = str(msg.getPayload(), "taskId");
        TaskEntity task = taskRepo.findByTaskId(taskId);
        if (task == null || !pairCode.equals(task.getPairCode())) {
            return new Result(false, "任务不存在");
        }
        if (!isUser(task, userId, task.getPublisherUser())) {
            return new Result(false, "只有发布方可以确认任务完成");
        }
        if (TaskEntity.STATUS_COMPLETED.equals(task.getStatus())) {
            return new Result(true, null); // 幂等
        }
        if (!TaskEntity.STATUS_ACCEPTED.equals(task.getStatus())) {
            return new Result(false, "任务尚未被接受，无法确认完成");
        }
        task.setStatus(TaskEntity.STATUS_COMPLETED);
        task.setCompletedTs(System.currentTimeMillis());
        taskRepo.save(task);
        log.info("任务完成确认 taskId={} publisher={}", taskId, userId);
        return new Result(true, null);
    }

    private Result handleReward(WsMessage msg, long userId, String pairCode) {
        String taskId = str(msg.getPayload(), "taskId");
        TaskEntity task = taskRepo.findByTaskId(taskId);
        if (task == null || !pairCode.equals(task.getPairCode())) {
            return new Result(false, "任务不存在");
        }
        if (!isUser(task, userId, task.getReceiverUser())) {
            return new Result(false, "只有接收方可以确认奖励兑现");
        }
        if (TaskEntity.STATUS_REWARDED.equals(task.getStatus())) {
            return new Result(true, null); // 幂等
        }
        if (!TaskEntity.STATUS_COMPLETED.equals(task.getStatus())) {
            return new Result(false, "任务尚未确认完成，无法兑现奖励");
        }
        task.setStatus(TaskEntity.STATUS_REWARDED);
        task.setRewardedTs(System.currentTimeMillis());
        taskRepo.save(task);
        log.info("奖励兑现确认 taskId={} receiver={}", taskId, userId);
        return new Result(true, null);
    }

    private static String str(Map<String, Object> payload, String key) {
        if (payload == null) return null;
        Object v = payload.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 安全比较：Long 字段可能为 null */
    private static boolean isUser(TaskEntity task, long userId, Long userField) {
        return userField != null && userField == userId;
    }
}
