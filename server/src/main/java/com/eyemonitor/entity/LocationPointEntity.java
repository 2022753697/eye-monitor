package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 位置点表（eye_location_points），轨迹回放数据源 */
@Entity
@Table(name = "eye_location_points", indexes = {
        @Index(name = "idx_loc_pair_user_ts", columnList = "pair_code,user_id,ts"),
        @Index(name = "idx_loc_ts", columnList = "ts")})
public class LocationPointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pair_code", nullable = false, length = 16)
    private String pairCode;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    private double lat;
    private double lng;
    private float accuracy;

    @Column(nullable = false)
    private long ts;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }

    public double getLng() { return lng; }
    public void setLng(double lng) { this.lng = lng; }

    public float getAccuracy() { return accuracy; }
    public void setAccuracy(float accuracy) { this.accuracy = accuracy; }

    public long getTs() { return ts; }
    public void setTs(long ts) { this.ts = ts; }
}