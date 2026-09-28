package com.eyemonitor.handler;

import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.PairService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket 消息处理器。
 * <p>
 * 负责：消息解析、类型分发、心跳回复、生命周期管理。
 * 实际业务逻辑（配对、路由）委托给 PairService。
 */
@Component
public class EyeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EyeWebSocketHandler.class);

    private final PairService pairService;

    public EyeWebSocketHandler(PairService pairService) {
        this.pairService = pairService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("新连接: sessionId={}, remote={}", session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String raw = message.getPayload();
        if (raw == null || raw.isBlank()) return;

        // 解析 JSON
        WsMessage msg = WsMessage.fromJson(raw);
        if (msg == null) {
            log.warn("消息解析失败: session={}, raw={}", session.getId(), raw);
            pairService.sendMessage(session, WsMessage.createError(null, "消息格式错误"));
            return;
        }

        // 校验必填字段
        if (msg.getType() == null || msg.getDeviceId() == null) {
            log.warn("消息缺少必填字段: session={}, raw={}", session.getId(), raw);
            pairService.sendMessage(session, WsMessage.createError(null, "缺少 type 或 deviceId"));
            return;
        }

        // 按类型分发
        switch (msg.getType()) {
            case "pair_request"          -> handlePairRequest(session, msg);
            case "pair_recover"          -> handlePairRecover(session, msg);
            case "app_switch"            -> handleForward(session, msg);
            case "location"              -> handleForward(session, msg);
            case "request_peer_location" -> handleForward(session, msg);
            case "chat"                  -> handleForward(session, msg);
            case "ping"                  -> handlePing(session, msg);
            case "pong"                  -> handlePong(session, msg);
            default -> {
                log.warn("未知消息类型: type={}, device={}", msg.getType(), msg.getDeviceId());
                pairService.sendMessage(session,
                        WsMessage.createError(msg.getDeviceId(), "未知消息类型: " + msg.getType()));
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("连接关闭: sessionId={}, status={}", session.getId(), status);
        pairService.onDisconnect(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("传输错误: sessionId={}, error={}", session.getId(), exception.getMessage());
        pairService.onDisconnect(session);
    }

    // --- 消息处理 ---

    private void handlePairRequest(WebSocketSession session, WsMessage msg) {
        WsMessage response = pairService.handlePairRequest(
                msg.getDeviceId(), msg.getPairCode(), session);
        pairService.sendMessage(session, response);
    }

    private void handlePairRecover(WebSocketSession session, WsMessage msg) {
        WsMessage response = pairService.handlePairRecover(
                msg.getDeviceId(), msg.getPairCode(), session);
        pairService.sendMessage(session, response);
    }

    private void handleForward(WebSocketSession session, WsMessage msg) {
        log.info("handleForward: session={}, type={}, deviceId={}, pairCode={}",
                session.getId(), msg.getType(), msg.getDeviceId(), msg.getPairCode());
        pairService.forwardToPeer(msg.getDeviceId(), msg);
    }

    private void handlePing(WebSocketSession session, WsMessage msg) {
        pairService.sendMessage(session, WsMessage.createPong(msg.getDeviceId()));
    }

    private void handlePong(WebSocketSession session, WsMessage msg) {
        // pong 不需要处理，心跳由底层 WebSocket 框架维持
    }
}