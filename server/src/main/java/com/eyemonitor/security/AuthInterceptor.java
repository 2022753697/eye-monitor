package com.eyemonitor.security;

import com.eyemonitor.entity.UserEntity;
import com.eyemonitor.repository.UserRepo;
import com.eyemonitor.web.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HTTP 鉴权拦截器：校验 Authorization: Bearer <accessToken>。
 * 除 /api/auth/** 外，/api/** 全部需要鉴权（WebMvcConfig 中注册）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtUtil jwtUtil;
    private final UserRepo userRepo;

    public AuthInterceptor(JwtUtil jwtUtil, UserRepo userRepo) {
        this.jwtUtil = jwtUtil;
        this.userRepo = userRepo;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(response);
        }
        String token = auth.substring(7).trim();
        if (token.isEmpty()) {
            return unauthorized(response);
        }

        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (Exception e) {
            return unauthorized(response);
        }
        if (!JwtUtil.TYPE_ACCESS.equals(claims.get("typ", String.class))) {
            return unauthorized(response);
        }

        long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            return unauthorized(response);
        }
        UserEntity user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return unauthorized(response);
        }
        Integer ver = claims.get("ver", Integer.class);
        Integer dbVer = user.getVer() == null ? 0 : user.getVer();
        if (ver == null || !ver.equals(dbVer)) {
            return unauthorized(response);
        }

        request.setAttribute(AuthUtil.ATTR_USER_ID, userId);
        return true;
    }

    private boolean unauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(ApiResponse.error(401, "未授权或登录已过期")));
        return false;
    }
}