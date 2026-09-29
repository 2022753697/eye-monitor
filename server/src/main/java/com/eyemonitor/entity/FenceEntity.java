package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 围栏表（eye_fences）：配置由双方同步，判定在观察方客户端 */
@Entity
@Table(name = "eye_fences", indexes = @Index(name = "idx_fence_pair", columnList = "pair_code"))
public class FenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pair_code", nullable = false, length = 16)
    private String pairCode;

    @Column(name = "owner_user")
    private Long ownerUser;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "center_lat", nullable = false)
    private double centerLat;

    @Column(name = "center_lng", nullable = false)
    private double centerLng;

    @Column(nullable = false)
    private double radius;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public Long getOwnerUser() { return ownerUser; }
    public void setOwnerUser(Long ownerUser) { this.ownerUser = ownerUser; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getCenterLat() { return centerLat; }
    public void setCenterLat(double centerLat) { this.centerLat = centerLat; }

    public double getCenterLng() { return centerLng; }
    public void setCenterLng(double centerLng) { this.centerLng = centerLng; }

    public double getRadius() { return radius; }
    public void setRadius(double radius) { this.radius = radius; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}