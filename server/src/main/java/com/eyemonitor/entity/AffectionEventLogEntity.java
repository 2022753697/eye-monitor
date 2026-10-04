package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 好感度积分流水表（affection_event_log）。
 * <p>
 * 幂等流水：dedupKey 唯一索引防重（客户端事件重发/连点只记一次）；
 * 同时承担每日上限聚合（按 pairCode + 本地日期 ts 区间 SUM(points)）。
 * source: chat / check_in / task / sos / anniversary / online / streak
 */
@Entity
@Table(name = "affection_event_log", indexes = {
        @Index(name = "idx_aff_log_pair_ts", columnList = "pair_code,ts"),
        @Index(name = "uk_aff_log_dedup", columnList = "dedup_key", unique = true)})
public class AffectionEventLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pair_code", nullable = false, length = 16)
    private String pairCode;

    /** 积分来源（chat/check_in/task/sos/anniversary/online/streak） */
    @Column(nullable = false, length = 16)
    private String source;

    /** 实际入账积分（每日上限截断后可能小于标准分值） */
    @Column(nullable = false)
    private int points;

    /** 幂等键（含 pairCode，全局唯一；格式见 AffectionService 各钩子） */
    @Column(name = "dedup_key", nullable = false, length = 128)
    private String dedupKey;

    @Column(nullable = false)
    private long ts;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }

    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }

    public long getTs() { return ts; }
    public void setTs(long ts) { this.ts = ts; }
}
