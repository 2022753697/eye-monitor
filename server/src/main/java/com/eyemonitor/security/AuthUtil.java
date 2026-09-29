package com.eyemonitor.security;

/**
 * 身份上下文常量与读取辅助
 * <p>
 * HTTP：AuthInterceptor 把 userId 写入 request attribute；
 * WS：WsAuthInterceptor 把 userId 写入 session attributes。
 */
public final class AuthUtil {

    public static final String ATTR_USER_ID = "eyeUserId";

    private AuthUtil() {}

    public static long currentUserId(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) return 0L;
        Object v = request.getAttribute(ATTR_USER_ID);
        return v instanceof Long ? (Long) v : 0L;
    }

    public static long userIdFromSession(org.springframework.web.socket.WebSocketSession session) {
        if (session == null) return 0L;
        Object v = session.getAttributes().get(ATTR_USER_ID);
        return v instanceof Long ? (Long) v : 0L;
    }
}