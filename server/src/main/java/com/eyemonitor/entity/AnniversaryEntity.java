package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** 纪念日表（eye_anniversaries）：repeat 为 true 表示每年重复 */
@Entity
@Table(name = "eye_anniversaries", indexes = @Index(name = "idx_anni_pair", columnList = "pair_code"))
public class AnniversaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pair_code", nullable = false, length = 16)
    private String pairCode;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false)
    private LocalDate date;

    /** 是否每年重复；DB 列名 is_repeat（repeat 是 MySQL 保留字） */
    @Column(name = "is_repeat", nullable = false)
    private boolean repeat;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public boolean isRepeat() { return repeat; }
    public void setRepeat(boolean repeat) { this.repeat = repeat; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}