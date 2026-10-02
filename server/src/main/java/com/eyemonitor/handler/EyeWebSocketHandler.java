package com.eyemonitor.handler;

import com.eyemonitor.model.WsMessage;
import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.security.WsSessionManager;
import com.eyemonitor.repository.MediaFileRepo;
import com.eyemonitor.service.MessageStore;
import com.eyemonitor.service.PairService;
import com.eyemonitor.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 消息处理器。
 * <p>
 * 职责：消息解析、类型分发、心跳回复、生命周期管理、WS 消息落库。
 * 业务逻辑（配对、路由）委托给 PairService；身份来自握手 WsAuthInterceptor 写入的 userId。
 */
@Component
public class EyeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EyeWebSocketHandler.class);

    private final PairService pairService;
    private final MessageStore messageStore;
    private final WsSessionManager wsSessionManager;
    private final MediaFileRepo mediaFileRepo;
    private final TaskService taskService;

    public EyeWebSocketHandler(PairService pairService, MessageStore messageStore,
                               WsSessionManager wsSessionManager,
                               MediaFileRepo mediaFileRepo,
                               TaskService taskService) {
        this.pairService = pairService;
        this.messageStore = messageStore;
        this.wsSessionManager = wsSessionManager;
        this.mediaFileRepo = mediaFileRepo;
        this.taskService = taskService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        long userId = AuthUtil.userIdFromSession(session);
        log.info("新连接: sessionId={}, userId={}, remote={}", session.getId(), userId, session.getRemoteAddress());
        if (userId > 0) {
            wsSessionManager.register(userId, session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String raw = message.getPayload();
        if (raw == null || raw.isBlank()) return;

        WsMessage msg = WsMessage.fromJson(raw);
        if (msg == null) {
            log.warn("消息解析失败: session={}, raw={}", session.getId(), raw);
            pairService.sendMessage(session, WsMessage.createError(null, null, "消息格式错误"));
            return;
        }
        if (msg.getType() == null || msg.getDeviceId() == null) {
            log.warn("消息缺少必填字段: session={}, raw={}", session.getId(), raw);
            pairService.sendMessage(session, WsMessage.createError(null, null, "缺少 type 或 deviceId"));
            return;
        }

        long userId = AuthUtil.userIdFromSession(session);

        switch (msg.getType()) {
            case "pair_request" -> handlePairRequest(session, msg, userId);
            case "pair_recover" -> handlePairRecover(session, msg, userId);
            case "ping" -> pairService.sendMessage(session, WsMessage.createPong(msg.getDeviceId()));
            case "pong" -> { /* 心跳回复无需处理 */ }
            case "location" -> handleLocation(session, msg, userId);
            case "chat" -> handleChat(session, msg, userId);
            case "typing" -> pairService.forwardToPeer(msg.getDeviceId(), msg);
            case "chat_read" -> handleChatRead(session, msg, userId);
            case "chat_recall" -> handleChatRecall(session, msg, userId);
            case "sos" -> handleSos(session, msg, userId);
            case "media" -> handleMedia(session, msg, userId);
            case "task_publish", "task_respond", "task_complete", "task_reward" -> handleTaskMessage(session, msg, userId);
            default -> handleForward(session, msg);
        }
    }

    // --- 业务分发 ---

    private void handlePairRequest(WebSocketSession session, WsMessage msg, long userId) {
        WsMessage response = pairService.handlePairRequest(userId, msg.getDeviceId(), msg.getPairCode(), session);
        pairService.sendMessage(session, response);
    }

    private void handlePairRecover(WebSocketSession session, WsMessage msg, long userId) {
        WsMessage response = pairService.handlePairRecover(userId, msg.getDeviceId(), msg.getPairCode(), session);
        pairService.sendMessage(session, response);
    }

    private void handleLocation(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        messageStore.saveLocation(pairCode, userId, msg.getDeviceId(), msg.getPayload(), msg.getTimestamp());
        pairService.forwardToPeer(msg.getDeviceId(), msg);
    }

    private void handleChat(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        Map<String, Object> payload = msg.getPayload();
        String text = payload != null && payload.get("text") instanceof String
                ? (String) payload.get("text") : null;
        Object refId = payload != null ? payload.get("refMsgId") : null;
        Object refText = payload != null ? payload.get("refText") : null;
        Long refMsgId = refId instanceof Number ? ((Number) refId).longValue() : null;
        String refTextS = refText instanceof String && !((String) refText).isEmpty()
                ? (String) refText : null;
        messageStore.saveChat(pairCode, userId, text, false, msg.getTimestamp(),
                "chat", refMsgId, refTextS);
        pairService.forwardToPeer(msg.getDeviceId(), msg);
        // 送达回执给发送方：服务器收到即回 chat_ack{msgTs}（对方离线也视为已送达服务器，离线补收兜底）
        Map<String, Object> ackPayload = new HashMap<>();
        ackPayload.put("msgTs", msg.getTimestamp());
        pairService.sendMessage(session,
                new WsMessage("chat_ack", msg.getDeviceId(), pairCode,
                        ackPayload, System.currentTimeMillis()));
    }

    /** chat_read：标记对方已读（upToTs 及更早），再转发让对方端刷新自己消息的已读态 */
    private void handleChatRead(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        Map<String, Object> payload = msg.getPayload();
        Object upTo = payload != null ? payload.get("upToTs") : null;
        long upToTs = upTo instanceof Number ? ((Number) upTo).longValue() : System.currentTimeMillis();
        messageStore.markChatRead(pairCode, userId, upToTs);
        pairService.forwardToPeer(msg.getDeviceId(), msg);
    }

    /** chat_recall：2 分钟窗口校验后置 deleted，成功才转发（否则回错误给发送方） */
    private void handleChatRecall(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        Map<String, Object> payload = msg.getPayload();
        Object ts = payload != null ? payload.get("msgTs") : null;
        long msgTs = ts instanceof Number ? ((Number) ts).longValue() : 0L;
        if (messageStore.recallChat(pairCode, msgTs, userId)) {
            pairService.forwardToPeer(msg.getDeviceId(), msg);
        } else {
            // 业务级失败走 system_tip：type=error 会被客户端误判为配对失效（清配对+跳配对页）
            pairService.sendMessage(session, new WsMessage("system_tip", msg.getDeviceId(), pairCode,
                    Map.of("text", "撤回失败：消息不存在或已超时"), System.currentTimeMillis()));
        }
    }

    private void handleSos(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        messageStore.saveSos(pairCode, userId, msg.getPayload(), msg.getTimestamp());
        pairService.forwardToPeer(msg.getDeviceId(), msg);
    }

    /** 媒体消息：落库聊天行（text=fileId, kind=media）保证离线补收，再转发给对方 */
    private void handleMedia(WebSocketSession session, WsMessage msg, long userId) {        String pairCode = resolvePairCode(msg, userId);
        Map<String, Object> payload = msg.getPayload();
        String fileId = payload != null && payload.get("fileId") instanceof String
                ? (String) payload.get("fileId") : null;
        if (fileId != null && !fileId.isBlank()) {
            messageStore.saveChat(pairCode, userId, fileId, false, msg.getTimestamp(), "media");
            // 回写媒体时长（上传端点存 null，语音/视频时长靠 WS 消息补全，/api/media 列表才能返回）
            Object dur = payload != null ? payload.get("duration") : null;
            if (dur instanceof Number) {
                try {
                    mediaFileRepo.findById(fileId).ifPresent(e -> {
                        e.setDuration(((Number) dur).longValue());
                        mediaFileRepo.save(e);
                    });
                } catch (Exception ex) {
                    log.warn("媒体时长回写失败 fileId={}", fileId, ex);
                }
            }
        }
        pairService.forwardToPeer(msg.getDeviceId(), msg);
    }

    /** 任务消息：TaskService 状态机校验+落库 → 成功才转发给 peer；业务失败回 system_tip */
    private void handleTaskMessage(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        TaskService.Result r = taskService.process(msg, userId, pairCode);
        if (r.forward) {
            pairService.forwardToPeer(msg.getDeviceId(), msg);
            return;
        }
        if (r.error != null) {
            log.warn("任务消息被拒: type={}, error={}", msg.getType(), r.error);
            pairService.sendMessage(session, new WsMessage("system_tip", msg.getDeviceId(), pairCode,
                    Map.of("text", r.error), System.currentTimeMillis()));
        }
    }

    private void handleForward(WebSocketSession session, WsMessage msg) {
        // app_switch / request_peer_location / anniversary_sync / fence_sync /
        // user_profile / sos_ack / device_status / media_deleted
        log.debug("handleForward: type={}, deviceId={}, pairCode={}",
                msg.getType(), msg.getDeviceId(), msg.getPairCode());
        pairService.forwardToPeer(msg.getDeviceId(), msg);
    }

    /** pairCode 优先取消息自带；缺失时按用户当前配对兜底 */
    private String resolvePairCode(WsMessage msg, long userId) {
        if (msg.getPairCode() != null && !msg.getPairCode().isBlank()) {
            return msg.getPairCode();
        }
        return pairService.getPairCodeOfUser(userId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("连接关闭: sessionId={}, status={}", session.getId(), status);
        long userId = AuthUtil.userIdFromSession(session);
        if (userId > 0) {
            wsSessionManager.remove(userId, session);
        }
        pairService.onDisconnect(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("传输错误: sessionId={}, error={}", session.getId(), exception.getMessage());
        pairService.onDisconnect(session);
    }
}