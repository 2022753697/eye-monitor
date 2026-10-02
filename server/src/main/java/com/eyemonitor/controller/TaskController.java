package com.eyemonitor.controller;

import com.eyemonitor.entity.TaskEntity;
import com.eyemonitor.repository.TaskRepo;
import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.service.PairService;
import com.eyemonitor.web.ApiResponse;
import com.eyemonitor.web.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务历史查询（任务页加载 / 换机恢复 / 跨端对账）。
 * 与 ChatController 同模式：pairCode 路径 + belongsToPair 校验。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskRepo taskRepo;
    private final PairService pairService;

    public TaskController(TaskRepo taskRepo, PairService pairService) {
        this.taskRepo = taskRepo;
        this.pairService = pairService;
    }

    @GetMapping("/{pairCode}")
    public ApiResponse<List<Map<String, Object>>> tasks(@PathVariable String pairCode,
                                                        HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        if (!pairService.belongsToPair(userId, pairCode)) {
            throw new BizException(403, "无权访问该配对");
        }
        List<TaskEntity> rows = taskRepo.findByPairCodeOrderByTsDesc(pairCode);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskEntity e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", e.getTaskId());
            m.put("publisherUser", e.getPublisherUser());
            m.put("content", e.getContentText());
            m.put("mediaFileId", e.getMediaFileId());
            m.put("mediaFileIds", e.getMediaFileIds());
            m.put("rewardType", e.getRewardType());
            m.put("rewardText", e.getRewardText());
            m.put("peerName", e.getPeerName());
            m.put("status", e.getStatus());
            m.put("reason", e.getReason());
            m.put("ts", e.getTs());
            m.put("completedTs", e.getCompletedTs());
            m.put("rewardedTs", e.getRewardedTs());
            m.put("isMine", e.getPublisherUser() != null && e.getPublisherUser() == userId);
            out.add(m);
        }
        return ApiResponse.ok(out);
    }
}
