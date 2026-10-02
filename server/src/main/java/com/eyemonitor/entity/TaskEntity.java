package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 情侣任务表（eye_tasks）。
 * <p>
 * 状态机：PENDING（已发布）→ ACCEPTED（已接受）→ COMPLETED（已完成❤，发布方确认）
 *                                    ↘ REJECTED（已拒绝，含 reason）
 *                                    → REWARDED（已兑现❤，接收方确认）
 * 长期有效，无过期；跨端状态以服务端为准（客户端乐观更新 + 回执校正，taskId 幂等）。
 */
@Entity
@Table(name = "eye_tasks", indexes = @Index(name = "idx_task_pair_ts", columnList = "pair_code,ts"))
public class TaskEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_REWARDED = "REWARDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客户端生成的幂等键（UUID 短串），跨端一致 */
    @Column(name = "task_id", nullable = false, unique = true, length = 48)
    private String taskId;

    @Column(name = "pair_code", nullable = false, length = 16)
    private String pairCode;

    /** 发布方 userId（仅发布方可确认「已完成」） */
    @Column(name = "publisher_user")
    private Long publisherUser;

    /** 接收方 userId（仅接收方可接受/拒绝/确认「已兑现」） */
    @Column(name = "receiver_user")
    private Long receiverUser;

    @Column(name = "content_text", nullable = false, length = 500)
    private String contentText;

    /** 可选照片 fileId（复用媒体通道；空=纯文字任务） */
    @Column(name = "media_file_id", length = 64)
    private String mediaFileId;

    /** 奖励预置类型（拥抱/亲亲/奶茶券/愿望券…）；空=纯自定义文字 */
    @Column(name = "reward_type", length = 32)
    private String rewardType;

    /** 奖励文字（自定义兜底；rewardType 存在时为其展示文案） */
    @Column(name = "reward_text", length = 200)
    private String rewardText;

    /** 发布方昵称快照（对方端气泡显示「xx 的任务」） */
    @Column(name = "peer_name", length = 64)
    private String peerName;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    /** 拒绝理由（仅 REJECTED） */
    @Column(name = "reason", length = 200)
    private String reason;

    @Column(nullable = false)
    private long ts;

    @Column(name = "completed_ts")
    private Long completedTs;

    @Column(name = "rewarded_ts")
    private Long rewardedTs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public Long getPublisherUser() { return publisherUser; }
    public void setPublisherUser(Long publisherUser) { this.publisherUser = publisherUser; }

    public Long getReceiverUser() { return receiverUser; }
    public void setReceiverUser(Long receiverUser) { this.receiverUser = receiverUser; }

    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }

    public String getMediaFileId() { return mediaFileId; }
    public void setMediaFileId(String mediaFileId) { this.mediaFileId = mediaFileId; }

    public String getRewardType() { return rewardType; }
    public void setRewardType(String rewardType) { this.rewardType = rewardType; }

    public String getRewardText() { return rewardText; }
    public void setRewardText(String rewardText) { this.rewardText = rewardText; }

    public String getPeerName() { return peerName; }
    public void setPeerName(String peerName) { this.peerName = peerName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status == null ? STATUS_PENDING : status; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public long getTs() { return ts; }
    public void setTs(long ts) { this.ts = ts; }

    public Long getCompletedTs() { return completedTs; }
    public void setCompletedTs(Long completedTs) { this.completedTs = completedTs; }

    public Long getRewardedTs() { return rewardedTs; }
    public void setRewardedTs(Long rewardedTs) { this.rewardedTs = rewardedTs; }
}
