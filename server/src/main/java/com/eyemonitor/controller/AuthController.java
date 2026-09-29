package com.eyemonitor.controller;

import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.service.AuthService;
import com.eyemonitor.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 认证接口：注册 / 登录 / 无感刷新 / 登出 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(authService.register(
                str(body, "username"), str(body, "password"), str(body, "nickname"),
                str(body, "gender"), str(body, "birthday"), str(body, "bio")));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(authService.login(str(body, "username"), str(body, "password")));
    }

    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(authService.refresh(str(body, "refreshToken")));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        if (userId > 0) {
            authService.logout(userId);
        }
        return ApiResponse.ok(null);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}