package com.eyemonitor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产凭据启动校验（WS1 / ADR-3）。
 * <p>
 * 拒绝以「缺失 / 等于历史默认值 / 用户名 root / JWT secret 过短」的凭据启动，
 * fail-fast 中止——避免部署时复用仓库历史中已公开的默认凭据（原 application.yml
 * 曾内置 DB 密码 EyeDev2025_local! 与默认 JWT secret，已视为公开）。
 * 本地开发亦须通过环境变量 DB_USER / DB_PASS / JWT_SECRET 提供，yml 不再回退。
 */
@Component
public class ProdCredentialValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProdCredentialValidator.class);

    /** 历史默认值：曾硬编码于 application.yml 且进入过仓库历史，视为已公开 */
    public static final String LEGACY_DEFAULT_DB_PASS = "EyeDev2025_local!";
    public static final String LEGACY_DEFAULT_JWT_SECRET = "eye-monitor-2025-local-dev-secret-key-change-in-prod-";

    /** HS256 密钥最短字节数（HMAC-SHA256 要求 ≥ 32 字节） */
    public static final int MIN_JWT_SECRET_BYTES = 32;

    private final Environment env;

    public ProdCredentialValidator(Environment env) {
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        String dbUser = env.getProperty("spring.datasource.username", "");
        String dbPass = env.getProperty("spring.datasource.password", "");
        String jwtSecret = env.getProperty("eye.jwt.secret", "");
        List<String> errors = validate(dbUser, dbPass, jwtSecret);
        if (!errors.isEmpty()) {
            for (String e : errors) {
                log.error("[凭据校验] {}", e);
            }
            log.error("====================================================================");
            log.error("[凭据校验] 启动中止：生产凭据未正确配置，勿以缺失/默认/弱凭据运行。");
            log.error("[凭据校验] 本地开发请通过环境变量 DB_USER / DB_PASS / JWT_SECRET 提供（application.yml 无内置回退值）。");
            log.error("====================================================================");
            throw new IllegalStateException("凭据校验失败（详情见日志），已拒绝启动");
        }
        log.info("[凭据校验] 通过：DB 账号非 root 且密码非历史默认，JWT secret ≥ {} 字节",
                MIN_JWT_SECRET_BYTES);
    }

    /** 纯校验逻辑（静态、无 IO），便于单测。返回错误列表，为空即通过。 */
    static List<String> validate(String dbUser, String dbPass, String jwtSecret) {
        List<String> errors = new ArrayList<>();
        if (dbUser == null || dbUser.isBlank()) {
            errors.add("DB 用户名缺失：请设置环境变量 DB_USER（独立低权限账号，勿用 root）");
        } else if ("root".equalsIgnoreCase(dbUser.trim())) {
            errors.add("DB 用户名不应为 root：请使用独立低权限账号（如 eye，仅授权 eye_monitor 库）");
        }
        if (dbPass == null || dbPass.isBlank()) {
            errors.add("DB 密码缺失：请设置环境变量 DB_PASS（生产环境必须为强随机值）");
        } else if (LEGACY_DEFAULT_DB_PASS.equals(dbPass)) {
            errors.add("DB 密码等于历史默认值，该值已进入仓库历史视为公开，禁止使用；请设置强随机 DB_PASS");
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            errors.add("JWT secret 缺失：请设置环境变量 JWT_SECRET（≥ " + MIN_JWT_SECRET_BYTES + " 字节强随机值）");
        } else if (LEGACY_DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            errors.add("JWT secret 等于历史默认值，该值已进入仓库历史视为公开；请设置强随机 JWT_SECRET");
        } else if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            errors.add("JWT secret 长度不足 " + MIN_JWT_SECRET_BYTES
                    + " 字节，当前 " + jwtSecret.getBytes(StandardCharsets.UTF_8).length + " 字节");
        }
        return errors;
    }
}