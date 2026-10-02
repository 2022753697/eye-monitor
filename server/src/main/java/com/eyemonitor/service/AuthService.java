package com.eyemonitor.service;

import com.eyemonitor.entity.UserEntity;
import com.eyemonitor.repository.UserRepo;
import com.eyemonitor.security.JwtUtil;
import com.eyemonitor.security.WsSessionManager;
import com.eyemonitor.util.ProfileView;
import com.eyemonitor.web.BizException;
import io.jsonwebtoken.Claims;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证服务：注册 / 登录 / 刷新 / 登出。
 * <p>
 * 参考若依 TokenService 的无状态 token 思路；吊销用「用户级版本号 ver」：
 * 登录/登出时 ver+1，旧 token（无论 access/refresh）全部失效。
 */
@Service
public class AuthService {

    private final UserRepo userRepo;
    private final JwtUtil jwtUtil;
    private final WsSessionManager wsSessionManager;

    /** 线程安全的 BCrypt 编码器（仅 spring-security-crypto，无全家桶） */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    // F-05（安全加固 2026-10）：登录限流（IP + 用户名双维度，5 次失败锁 15 分钟）
    private static final int LOGIN_MAX_FAIL = 5;
    private static final long LOGIN_WINDOW_MS = 15 * 60_000L;
    private static final long LOGIN_LOCK_MS = 15 * 60_000L;
    private final SlidingWindowLimiter loginIpLimiter = new SlidingWindowLimiter(LOGIN_MAX_FAIL, LOGIN_WINDOW_MS, LOGIN_LOCK_MS);
    private final SlidingWindowLimiter loginUserLimiter = new SlidingWindowLimiter(LOGIN_MAX_FAIL, LOGIN_WINDOW_MS, LOGIN_LOCK_MS);
    /** 不存在用户时也执行一次假 BCrypt 匹配，抹平账号枚举时间差 */
    private static final String DUMMY_HASH = "$2a$10$cVE1lWv7M2v9xkKj8vzLxO9uKq3mQh3wXx3a0cB0n5U7kYz1m2o3K";

    public AuthService(UserRepo userRepo, JwtUtil jwtUtil, WsSessionManager wsSessionManager) {
        this.userRepo = userRepo;
        this.jwtUtil = jwtUtil;
        this.wsSessionManager = wsSessionManager;
    }

    @Transactional
    public Map<String, Object> register(String username, String password, String nickname,
                                        String gender, String birthday, String bio) {
        if (username == null || username.trim().length() < 3 || username.trim().length() > 32) {
            throw new BizException(400, "用户名长度需为 3-32 位");
        }
        if (password == null || password.length() < 6) {
            throw new BizException(400, "密码长度至少 6 位");
        }
        // F-05（安全加固）：新密码策略 ≥8 位且含字母+数字
        if (!isStrongPassword(password)) {
            throw new BizException(400, "密码需至少 8 位且包含字母和数字");
        }
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new BizException(400, "昵称不能为空");
        }
        if (userRepo.existsByUsername(username.trim())) {
            throw new BizException(400, "用户名已存在");
        }

        UserEntity u = new UserEntity();
        u.setUsername(username.trim());
        u.setPasswordHash(encoder.encode(password));
        u.setNickname(nickname.trim());
        u.setGender(normalizeGender(gender));
        u.setBirthday(parseDate(birthday));
        u.setBio(bio == null || bio.isBlank() ? null : bio.trim());
        u.setVer(0);
        u.setCreatedAt(System.currentTimeMillis());
        userRepo.save(u);
        return tokenBundle(u);
    }

    @Transactional
    public Map<String, Object> login(String username, String password, String ip) {
        if (username == null || password == null) {
            throw new BizException(400, "用户名或密码不能为空");
        }
        // F-05（安全加固）：登录限流（IP + 用户名双维度）
        String userKey = "user:" + username.trim();
        String ipKey = "ip:" + (ip == null ? "unknown" : ip);
        if (!loginIpLimiter.allowed(ipKey) || !loginUserLimiter.allowed(userKey)) {
            throw new BizException(429, "尝试过于频繁，请 15 分钟后再试");
        }
        UserEntity u = userRepo.findByUsername(username.trim());
        if (u == null) {
            // 抹平时间差：不存在用户也执行一次假 BCrypt
            encoder.matches(password, DUMMY_HASH);
            loginIpLimiter.recordFail(ipKey);
            loginUserLimiter.recordFail(userKey);
            throw new BizException(401, "用户名或密码错误");
        }
        if (!encoder.matches(password, u.getPasswordHash())) {
            loginIpLimiter.recordFail(ipKey);
            loginUserLimiter.recordFail(userKey);
            throw new BizException(401, "用户名或密码错误");
        }
        loginIpLimiter.recordSuccess(ipKey);
        loginUserLimiter.recordSuccess(userKey);
        // 单设备登录：ver+1 吊销该用户所有旧 token 族
        u.setVer((u.getVer() == null ? 0 : u.getVer()) + 1);
        userRepo.save(u);
        // 旧活跃 WS 会话踢下线（KICKED）
        wsSessionManager.kick(u.getId());
        return tokenBundle(u);
    }

    /** F-05：密码强度校验（≥8 位且含字母+数字） */
    private boolean isStrongPassword(String password) {
        if (password == null || password.length() < 8) return false;
        boolean hasLetter = false, hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) hasLetter = true;
            if (Character.isDigit(c)) hasDigit = true;
        }
        return hasLetter && hasDigit;
    }

    @Transactional
    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        Claims claims;
        try {
            claims = jwtUtil.parse(refreshToken);
        } catch (Exception e) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        if (!JwtUtil.TYPE_REFRESH.equals(claims.get("typ", String.class))) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        UserEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BizException(401, "登录已过期，请重新登录"));
        Integer ver = claims.get("ver", Integer.class);
        if (ver == null || !ver.equals(u.getVer() == null ? 0 : u.getVer())) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        return tokenBundle(u);
    }

    @Transactional
    public void logout(long userId) {
        UserEntity u = userRepo.findById(userId).orElse(null);
        if (u == null) return;
        u.setVer((u.getVer() == null ? 0 : u.getVer()) + 1);
        userRepo.save(u);
        wsSessionManager.kick(userId);
    }

    private Map<String, Object> tokenBundle(UserEntity u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessToken", jwtUtil.createAccessToken(u.getId(), u.getVer()));
        m.put("refreshToken", jwtUtil.createRefreshToken(u.getId(), u.getVer()));
        m.put("profile", ProfileView.of(u));
        return m;
    }

    public static String normalizeGender(String gender) {
        if ("female".equals(gender) || "male".equals(gender)) {
            return gender;
        }
        return null;
    }

    public static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw new BizException(400, "日期格式应为 yyyy-MM-dd");
        }
    }
}