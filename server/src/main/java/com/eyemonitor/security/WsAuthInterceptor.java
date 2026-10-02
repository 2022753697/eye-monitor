package com.eyemonitor.security;

import com.eyemonitor.entity.UserEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.repository.UserRepo;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WS 握手鉴权（F-03 安全加固 2026-10）：
 * 优先读请求头 X-Auth-Token（新客户端）；兼容 query ?token=（旧客户端过渡期）。
 * 校验 typ=access 且 ver 与用户当前版本一致；失败返回 403 拒绝握手。
 * 成功后把 userId 放入 session attributes。
 */
@Component
public class WsAuthInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsAuthInterceptor.class);
    private static final String HEADER_TOKEN = "X-Auth-Token";

    private final JwtUtil jwtUtil;
    private final UserRepo userRepo;

    public WsAuthInterceptor(JwtUtil jwtUtil, UserRepo userRepo) {
        this.jwtUtil = jwtUtil;
        this.userRepo = userRepo;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 1) Header 优先（新客户端，token 不进访问日志）
        String token = request.getHeaders().getFirst(HEADER_TOKEN);
        if (token == null || token.isBlank()) {
            // 2) 兼容旧客户端：?token=<accessToken>
            String query = request.getURI().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int idx = pair.indexOf('=');
                    if (idx > 0 && "token".equals(pair.substring(0, idx))) {
                        token = pair.substring(idx + 1);
                        break;
                    }
                }
            }
        }
        if (token == null || token.isBlank()) {
            return refuse(response);
        }
        try {
            Claims claims = jwtUtil.parse(token.trim());
            if (JwtUtil.TYPE_ACCESS.equals(claims.get("typ", String.class))) {
                long userId = Long.parseLong(claims.getSubject());
                UserEntity user = userRepo.findById(userId).orElse(null);
                Integer ver = claims.get("ver", Integer.class);
                if (user != null && ver != null && ver.equals(user.getVer() == null ? 0 : user.getVer())) {
                    attributes.put(AuthUtil.ATTR_USER_ID, userId);
                    return true;
                }
            }
            log.warn("WS 握手被拒：token 类型/版本校验失败");
        } catch (Exception e) {
            log.warn("WS 握手 token 校验失败: {}", e.getMessage());
        }
        return refuse(response);
    }

    private boolean refuse(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}