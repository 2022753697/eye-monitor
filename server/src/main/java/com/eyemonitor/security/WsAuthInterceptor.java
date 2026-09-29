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
 * WS 握手鉴权：?token=<accessToken>，校验 typ=access 且 ver 与用户当前版本一致。
 * 失败返回 403 拒绝握手。成功后把 userId 放入 session attributes。
 */
@Component
public class WsAuthInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsAuthInterceptor.class);

    private final JwtUtil jwtUtil;
    private final UserRepo userRepo;

    public WsAuthInterceptor(JwtUtil jwtUtil, UserRepo userRepo) {
        this.jwtUtil = jwtUtil;
        this.userRepo = userRepo;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = request.getURI().getQuery();
        if (token == null) {
            return refuse(response);
        }
        // 解析 ?token=xxx（query 可能含其他参数，逐个拆分）
        long userId = -1;
        for (String pair : token.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && "token".equals(pair.substring(0, idx))) {
                String t = pair.substring(idx + 1);
                try {
                    Claims claims = jwtUtil.parse(t);
                    if (JwtUtil.TYPE_ACCESS.equals(claims.get("typ", String.class))) {
                        userId = Long.parseLong(claims.getSubject());
                        UserEntity user = userRepo.findById(userId).orElse(null);
                        Integer ver = claims.get("ver", Integer.class);
                        if (user != null && ver != null && ver.equals(user.getVer() == null ? 0 : user.getVer())) {
                            attributes.put(AuthUtil.ATTR_USER_ID, userId);
                            return true;
                        }
                    }
                } catch (Exception e) {
                    log.warn("WS 握手 token 校验失败: {}", e.getMessage());
                }
                userId = -1;
                break;
            }
        }
        if (userId == -1) {
            log.warn("WS 握手被拒：缺少或伪造 token");
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