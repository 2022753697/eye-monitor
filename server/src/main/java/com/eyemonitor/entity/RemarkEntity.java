package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 备注表（eye_remarks）：我(user_id)对配对对象(peer_user_id)的私有备注，换机跟随账号 */
@Entity
@Table(name = "eye_remarks",
        uniqueConstraints = @UniqueConstraint(name = "uk_remark_user_peer",
                columnNames = {"user_id", "peer_user_id"}))
public class RemarkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 备注归属人（我的 userId，私有视角） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 被备注的配对对象 userId */
    @Column(name = "peer_user_id", nullable = false)
    private Long peerUserId;

    /** 备注内容；null/空 = 无备注 */
    @Column(length = 64)
    private String remark;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getPeerUserId() { return peerUserId; }
    public void setPeerUserId(Long peerUserId) { this.peerUserId = peerUserId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}