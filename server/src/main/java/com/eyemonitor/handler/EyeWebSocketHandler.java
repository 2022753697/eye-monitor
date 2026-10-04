package com.eyemonitor.handler;

import com.eyemonitor.model.WsMessage;
import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.security.WsSessionManager;
import com.eyemonitor.repository.MediaFileRepo;
import com.eyemonitor.service.AffectionService;
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
    private final AffectionService affectionService;

    public EyeWebSocketHandler(PairService pairService, MessageStore messageStore,
                               WsSessionManager wsSessionManager,
                               MediaFileRepo mediaFileRepo,
                               TaskService taskService,
                               AffectionService affectionService) {
        this.pairService = pairService;
        this.messageStore = messageStore;
        this.wsSessionManager = wsSessionManager;
        this.mediaFileRepo = mediaFileRepo;
        this.taskService = taskService;
        this.affectionService = affectionService;
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

        // P1-2（安全加固）：单连接频率限流（令牌桶式滑动窗口：60 条 / 10 秒，超限断开）
        if (!allowMessage(session.getId())) {
            log.warn("WS 消息过频，断开连接: session={}", session.getId());
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception ignored) {}
            return;
        }

        WsMessage msg = WsMessage.fromJson(raw);
        if (msg == null) {
            log.warn("消息解析失败: session={}, rawHead={}", session.getId(), truncate(raw, 200));
            pairService.sendMessage(session, WsMessage.createError(null, null, "消息格式错误"));
            return;
        }
        if (msg.getType() == null || msg.getDeviceId() == null) {
            log.warn("消息缺少必填字段: session={}, rawHead={}", session.getId(), truncate(raw, 200));
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
            case "typing" -> pairService.forwardToPeer(userId, msg.getDeviceId(), msg);
            case "chat_read" -> handleChatRead(session, msg, userId);
            case "chat_recall" -> handleChatRecall(session, msg, userId);
            case "sos" -> handleSos(session, msg, userId);
            case "sos_ack" -> handleSosAck(session, msg, userId);
            case "media" -> handleMedia(session, msg, userId);
            case "task_publish", "task_respond", "task_complete", "task_reward" -> handleTaskMessage(session, msg, userId);
            case "check_in" -> handleCheckIn(session, msg, userId);
            default -> handleForward(session, msg, userId);
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
        pairService.forwardToPeer(userId, msg.getDeviceId(), msg);
    }

    private void handleChat(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        Map<String, Object> payload = msg.getPayload();
        String text = payload != null && payload.get("text") instanceof String
                ? (String) payload.get("text") : null;
        // P1-2：文本长度限制（≤2000 字符，防 DB 无界增长/洪泛）
        if (text != null && text.length() > 2000) {
            pairService.sendMessage(session, WsMessage.createError(msg.getDeviceId(), pairCode,
                    "消息过长（最多 2000 字）"));
            return;
        }
        // L-1（修复）：拒绝空 chat（无文本且非媒体类型），防对端渲染空气泡
        if (text == null || text.isEmpty()) {
            pairService.sendMessage(session, WsMessage.createError(msg.getDeviceId(), pairCode,
                    "消息内容为空"));
            return;
        }
        Object refId = payload != null ? payload.get("refMsgId") : null;
        Object refText = payload != null ? payload.get("refText") : null;
        Long refMsgId = refId instanceof Number ? ((Number) refId).longValue() : null;
        String refTextS = refText instanceof String && !((String) refText).isEmpty()
                ? (String) refText : null;
        messageStore.saveChat(pairCode, userId, text, false, msg.getTimestamp(),
                "chat", refMsgId, refTextS);
        pairService.forwardToPeer(userId, msg.getDeviceId(), msg);
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
        pairService.forwardToPeer(userId, msg.getDeviceId(), msg);
    }

    /** chat_recall：2 分钟窗口校验后置 deleted，成功才转发（否则回错误给发送方） */
    private void handleChatRecall(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        Map<String, Object> payload = msg.getPayload();
        Object ts = payload != null ? payload.get("msgTs") : null;
        long msgTs = ts instanceof Number ? ((Number) ts).longValue() : 0L;
        if (messageStore.recallChat(pairCode, msgTs, userId)) {
            pairService.forwardToPeer(userId, msg.getDeviceId(), msg);
        } else {
            // 业务级失败走 system_tip：type=error 会被客户端误判为配对失效（清配对+跳配对页）
            pairService.sendMessage(session, new WsMessage("system_tip", msg.getDeviceId(), pairCode,
                    Map.of("text", "撤回失败：消息不存在或已超时"), System.currentTimeMillis()));
        }
    }

    private void handleSos(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        messageStore.saveSos(pairCode, userId, msg.getPayload(), msg.getTimestamp());
        pairService.forwardToPeer(userId, msg.getDeviceId(), msg);
    }

    /**
     * sos_ack：回执 +5（好感度钩子，内部容错），再转发给对端。
     * 回执与 SOS 本身一样按消息时间戳幂等，不影响原转发链路。
     */
    private void handleSosAck(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        if (pairCode == null) return;
        try {
            affectionService.onSosAck(pairCode, msg.getTimestamp());
        } catch (Exception ex) {
            log.warn("SOS 回执好感度钩子异常", ex);
        }
        pairService.forwardToPeer(userId, msg.getDeviceId(), msg);
    }

    /**
     * check_in：客户端打卡上报（window=morning|evening），鉴权后计分 +3（同 (pair,日期,时段,user) 幂等），
     * 回推 affection_sync 快照（幂等跳过时也回当前状态，客户端可刷新）；推送策略见 AffectionService。
     */
    private void handleCheckIn(WebSocketSession session, WsMessage msg, long userId) {
        if (userId <= 0) {
            pairService.sendMessage(session, WsMessage.createError(msg.getDeviceId(), null, "未鉴权"));
            return;
        }
        String pairCode = resolvePairCode(msg, userId);
        if (pairCode == null) {
            pairService.sendMessage(session, WsMessage.createError(msg.getDeviceId(), null, "未配对"));
            return;
        }
        Map<String, Object> payload = msg.getPayload();
        String window = payload != null && payload.get("window") instanceof String
                ? (String) payload.get("window") : null;
        if (!"morning".equals(window) && !"evening".equals(window)) {
            pairService.sendMessage(session, new WsMessage("system_tip", msg.getDeviceId(), pairCode,
                    Map.of("text", "打卡时段无效（morning/evening）"), System.currentTimeMillis()));
            return;
        }
        AffectionService.LevelInfo info = affectionService.onCheckIn(pairCode, userId, window);
        // 回推快照（请求方立即可见；双端广播由 onCheckIn 内的推送策略负责：升级必推/非升级 60 秒节流）
        pairService.sendMessage(session, WsMessage.createAffectionSync(
                pairCode, info.points, info.level, info.progress, info.title));
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
        pairService.forwardToPeer(userId, msg.getDeviceId(), msg);
    }

    /** 任务消息：TaskService 状态机校验+落库 → 成功才转发给 peer；业务失败回 system_tip */
    private void handleTaskMessage(WebSocketSession session, WsMessage msg, long userId) {
        String pairCode = resolvePairCode(msg, userId);
        TaskService.Result r = taskService.process(msg, userId, pairCode);
        if (r.forward) {
            pairService.forwardToPeer(userId, msg.getDeviceId(), msg);
            return;
        }
        if (r.error != null) {
            log.warn("任务消息被拒: type={}, error={}", msg.getType(), r.error);
            pairService.sendMessage(session, new WsMessage("system_tip", msg.getDeviceId(), pairCode,
                    Map.of("text", r.error), System.currentTimeMillis()));
        }
    }

    private void handleForward(WebSocketSession session, WsMessage msg, long userId) {
        // app_switch / request_peer_location / anniversary_sync / fence_sync /
        // user_profile / device_status / media_deleted
        log.debug("handleForward: type={}, deviceId={}, pairCode={}",
                msg.getType(), msg.getDeviceId(), msg.getPairCode());
        pairService.forwardToPeer(userId, msg.getDeviceId(), msg);
    }

    /**
     * F-02（安全加固 2026-10）：服务端权威——pairCode 一律先取该用户自己的配对；
     * 显式携带的 pairCode 必须 belongsToPair(userId)，否则返回 null（非成员无法注入他人配对）。
     */
    private String resolvePairCode(WsMessage msg, long userId) {
        String self = pairService.getPairCodeOfUser(userId);
        if (self != null) return self;
        if (msg.getPairCode() != null && !msg.getPairCode().isBlank()
                && pairService.belongsToPair(userId, msg.getPairCode())) {
            return msg.getPairCode();
        }
        return null;
    }

    /** P1-5：日志截断（防聊天全文/敏感内容入日志） */
    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // P1-2：单连接消息频率限流（60 条 / 10 秒滑动窗口）
    private static final int MSG_MAX_PER_WINDOW = 60;
    private static final long MSG_WINDOW_MS = 10_000L;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>> msgRates =
            new java.util.concurrent.ConcurrentHashMap<>();

    private boolean allowMessage(String sessionId) {
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<Long> q = msgRates.computeIfAbsent(sessionId, k -> new java.util.ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > MSG_WINDOW_MS) q.pollFirst();
            if (q.size() >= MSG_MAX_PER_WINDOW) return false;
            q.addLast(now);
            return true;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("连接关闭: sessionId={}, status={}", session.getId(), status);
        long userId = AuthUtil.userIdFromSession(session);
        if (userId > 0) {
            wsSessionManager.remove(userId, session);
        }
        // H-2（修复）：会话关闭即清理限流队列，防无界内存增长
        msgRates.remove(session.getId());
        pairService.onDisconnect(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("传输错误: sessionId={}, error={}", session.getId(), exception.getMessage());
        // L-2（修复）：传输错误不在此处通知离线（afterConnectionClosed 统一处理，防重复离线通知）
    }
}