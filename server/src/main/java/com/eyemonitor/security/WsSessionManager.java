package com.eyemonitor.security;

import com.eyemonitor.model.WsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃 WS 会话管理：userId -> session（单设备）。
 * 单设备登录：新登录使旧会话收到 KICKED 并被关闭。
 */
@Component
public class WsSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WsSessionManager.class);

    private final ConcurrentHashMap<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        WebSocketSession old = sessions.put(userId, session);
        if (old != null && old != session && old.isOpen()) {
            try {
                old.close(CloseStatus.NORMAL);
            } catch (Exception ignored) {}
        }
    }

    public void remove(Long userId, WebSocketSession session) {
        sessions.remove(userId, session);
    }

    /** 在线判定：该 userId 当前是否有存活 WS 会话（好感度「同时在线」检测用） */
    public boolean isOnline(Long userId) {
        if (userId == null) return false;
        WebSocketSession s = sessions.get(userId);
        return s != null && s.isOpen();
    }

    /** 踢下线：通知 KICKED 并关闭旧会话 */
    public boolean kick(Long userId) {
        WebSocketSession session = sessions.get(userId);
        if (session == null) {
            return false;
        }
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(
                        WsMessage.createError(null, "KICKED", "账号在其他设备登录，请重新登录").toJson()));
                session.close(CloseStatus.NORMAL);
            }
        } catch (Exception e) {
            log.warn("踢下线失败: userId={}, err={}", userId, e.getMessage());
        }
        sessions.remove(userId, session);
        return true;
    }
}