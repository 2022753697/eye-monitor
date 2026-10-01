package com.eyemonitor.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProdCredentialValidator 纯校验逻辑单测（WS1 / ADR-3）。
 * 覆盖：缺失、历史默认值、root 账号、过短 JWT secret、合法值通过。
 */
class ProdCredentialValidatorTest {

    private static final String STRONG_PASS = "K7!xQz9$mLw2#Rtn5@VdPq8&ZsT4*bNy";
    private static final String STRONG_SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef"; // 48 字节

    @Test
    void validCredentialPasses() {
        List<String> errors = ProdCredentialValidator.validate("eye", STRONG_PASS, STRONG_SECRET);
        assertTrue(errors.isEmpty(), "合法凭据不应产生错误: " + errors);
    }

    @Test
    void blankDbPassRejected() {
        List<String> errors = ProdCredentialValidator.validate("eye", "", STRONG_SECRET);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("DB 密码缺失"));
    }

    @Test
    void legacyDefaultDbPassRejected() {
        List<String> errors = ProdCredentialValidator.validate(
                "eye", ProdCredentialValidator.LEGACY_DEFAULT_DB_PASS, STRONG_SECRET);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("历史默认值"));
    }

    @Test
    void blankJwtSecretRejected() {
        List<String> errors = ProdCredentialValidator.validate("eye", STRONG_PASS, "");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("JWT secret 缺失"));
    }

    @Test
    void legacyDefaultJwtSecretRejected() {
        List<String> errors = ProdCredentialValidator.validate(
                "eye", STRONG_PASS, ProdCredentialValidator.LEGACY_DEFAULT_JWT_SECRET);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("历史默认值"));
    }

    @Test
    void shortJwtSecretRejected() {
        List<String> errors = ProdCredentialValidator.validate("eye", STRONG_PASS, "too-short-secret");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("长度不足"));
    }

    @Test
    void rootDbUserRejected() {
        List<String> errors = ProdCredentialValidator.validate("root", STRONG_PASS, STRONG_SECRET);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("不应为 root"));
    }

    @Test
    void missingEverythingProducesAllErrors() {
        List<String> errors = ProdCredentialValidator.validate(null, null, null);
        // 用户名缺失 + 密码缺失 + secret 缺失
        assertEquals(3, errors.size());
    }
}