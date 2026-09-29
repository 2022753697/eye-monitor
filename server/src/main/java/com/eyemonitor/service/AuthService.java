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
    public Map<String, Object> login(String username, String password) {
        if (username == null || password == null) {
            throw new BizException(400, "用户名或密码不能为空");
        }
        UserEntity u = userRepo.findByUsername(username.trim());
        if (u == null || !encoder.matches(password, u.getPasswordHash())) {
            throw new BizException(401, "用户名或密码错误");
        }
        // 单设备登录：ver+1 吊销该用户所有旧 token 族
        u.setVer((u.getVer() == null ? 0 : u.getVer()) + 1);
        userRepo.save(u);
        // 旧活跃 WS 会话踢下线（KICKED）
        wsSessionManager.kick(u.getId());
        return tokenBundle(u);
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