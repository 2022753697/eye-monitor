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
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(authService.register(
                str(body, "username"), str(body, "password"), str(body, "nickname"),
                str(body, "gender"), str(body, "birthday"), str(body, "bio"),
                clientIp(request)));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(authService.login(str(body, "username"), str(body, "password"),
                clientIp(request)));
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

    /** P2-4：账号注销（删除配对数据/备注/头像/账号本体，注销后需重新注册） */
    @org.springframework.web.bind.annotation.DeleteMapping("/account")
    public ApiResponse<Void> deleteAccount(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        if (userId > 0) {
            authService.deleteAccount(userId);
        }
        return ApiResponse.ok(null);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 真实客户端 IP（nginx 已设置 X-Real-IP）；L-8：格式校验，防伪造头刷限流键 */
    private static String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (!isIp(ip)) ip = request.getHeader("X-Forwarded-For");
        if (ip != null && ip.indexOf(',') > 0) ip = ip.substring(0, ip.indexOf(',')).trim();
        if (!isIp(ip)) ip = request.getRemoteAddr();
        if (!isIp(ip)) ip = "unknown";
        return ip;
    }

    /** 简单 IPv4/IPv6 合法性校验 */
    private static boolean isIp(String s) {
        if (s == null || s.isBlank() || s.length() > 45) return false;
        return s.matches("[0-9a-fA-F:.]+");
    }
}