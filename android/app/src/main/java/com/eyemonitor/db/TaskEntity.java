package com.eyemonitor.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 情侣任务本地缓存（task_cache）。
 * <p>
 * 服务端为状态权威；本表为本地展示/离线缓存，聊天气泡按 taskId 关联渲染状态徽标。
 * 状态：PENDING（待响应）→ ACCEPTED（已接受）→ COMPLETED（已完成❤，发布方确认）
 *                                        ↘ REJECTED（已拒绝，含 reason）
 *                                        → REWARDED（已兑现❤，接收方确认）
 */
@Entity(tableName = "task_cache")
public class TaskEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_REWARDED = "REWARDED";

    @PrimaryKey
    @androidx.annotation.NonNull
    public String taskId;

    /** 任务内容（文字必填） */
    public String content;

    /** 可选照片 fileId（空=纯文字任务） */
    public String mediaFileId;

    /** 奖励预置类型（空=纯自定义文字） */
    public String rewardType;

    /** 奖励文字（展示用：自定义原文或预置项文案） */
    public String rewardText;

    /** 发布方昵称快照 */
    public String peerName;

    /** 我是否为发布方（决定详情页可操作按钮） */
    public boolean isMine;

    public String status;

    /** 拒绝理由（仅 REJECTED） */
    public String reason;

    public long ts;

    public long completedTs;

    public long rewardedTs;

    public TaskEntity() {}

    /** 简化构造：发布本地记录用 */
    @androidx.room.Ignore
    public TaskEntity(String taskId, String content, String mediaFileId,
                      String rewardType, String rewardText, String peerName,
                      boolean isMine, String status, String reason, long ts) {
        this.taskId = taskId;
        this.content = content;
        this.mediaFileId = mediaFileId;
        this.rewardType = rewardType;
        this.rewardText = rewardText;
        this.peerName = peerName;
        this.isMine = isMine;
        this.status = status;
        this.reason = reason;
        this.ts = ts;
    }
}
