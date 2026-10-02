package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** SOS 记录表（eye_sos_logs，首版可选启用） */
@Entity
@Table(name = "eye_sos_logs", indexes = {
        @Index(name = "idx_sos_pair", columnList = "pair_code"),
        @Index(name = "idx_sos_ts", columnList = "ts")})
public class SosLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pair_code", nullable = false, length = 16)
    private String pairCode;

    @Column(name = "from_user")
    private Long fromUser;

    @Column(length = 500)
    private String text;

    private Double lat;
    private Double lng;

    @Column(nullable = false)
    private long ts;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public Long getFromUser() { return fromUser; }
    public void setFromUser(Long fromUser) { this.fromUser = fromUser; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }

    public long getTs() { return ts; }
    public void setTs(long ts) { this.ts = ts; }
}