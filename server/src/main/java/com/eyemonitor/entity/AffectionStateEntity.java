package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 亲密度状态表（affection_state）。
 * <p>
 * 【共享性硬约束】pair 唯一一行：双方的经验（积分）与等级永远是同一个值，
 * 任何一方行为（聊天回合/打卡/任务/SOS 回执/纪念日/在线/连续天数）都写入同一行，
 * 绝不存在「我的经验 / 你的经验」的个人行。
 */
@Entity
@Table(name = "affection_state")
public class AffectionStateEntity {

    /** 6 位配对码（pair 维度主键，一行一 pair） */
    @Id
    @Column(name = "pair_code", nullable = false, length = 16)
    private String pairCode;

    /** 共享积分（只增不减） */
    @Column(nullable = false)
    private long points;

    /** 共享等级（1-6，由积分门槛推导） */
    @Column(nullable = false)
    private int level;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public long getPoints() { return points; }
    public void setPoints(long points) { this.points = points; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
