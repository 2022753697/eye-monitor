package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 配对关系表（eye_pairs）。
 * <p>
 * 配对绑定 user（而非 device）：pair_request 时由 WS 握手中的 token 解析出 userId。
 * status: PENDING（仅 A）/ COMPLETE（AB 齐全）
 */
@Entity
@Table(name = "eye_pairs")
public class PairEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETE = "COMPLETE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pair_code", nullable = false, unique = true, length = 16)
    private String pairCode;

    @Column(name = "user_a")
    private Long userA;

    @Column(name = "user_b")
    private Long userB;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public Long getUserA() { return userA; }
    public void setUserA(Long userA) { this.userA = userA; }

    public Long getUserB() { return userB; }
    public void setUserB(Long userB) { this.userB = userB; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}