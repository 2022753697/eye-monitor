package com.eyemonitor.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：双 token（access + refresh）。
 * 思路参考若依 TokenService（无状态 JWT，claims 携带业务数据），
 * 但用「用户级版本号 ver」实现吊销（非若依的在线用户表方案）。
 * HH 注意：本机为 HS256 对称密钥，多服务器部署时需共享 secret。
 */
@Component
public class JwtUtil {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    @Value("${eye.jwt.secret}")
    private String secret;

    @Value("${eye.jwt.access-ttl-ms}")
    private long accessTtlMs;

    @Value("${eye.jwt.refresh-ttl-ms}")
    private long refreshTtlMs;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(long userId, int ver) {
        return build(userId, ver, TYPE_ACCESS, accessTtlMs);
    }

    public String createRefreshToken(long userId, int ver) {
        return build(userId, ver, TYPE_REFRESH, refreshTtlMs);
    }

    private String build(long userId, int ver, String type, long ttlMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("ver", ver)
                .claim("typ", type)
                // P1-3：jti 唯一 ID（秒级 iat 下也能保证每次签发 token 唯一，支撑 refresh 轮换哈希）
                .claim("jti", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttlMs))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** 解析并校验签名/有效期；失败抛异常 */
    public Claims parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key()).build()
                .parseClaimsJws(token).getBody();
    }
}