package com.eyemonitor.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 亲密度/等级缓存（affection_cache，单行）。
 * <p>
 * 服务器为权威（MySQL affection_state 按 pairCode 一行，双方共享同一份 points/level/progress），
 * 本表仅作离线缓存与 UI 渲染数据源：affection_sync 推送 / GET /affection 兜底拉取时 upsert。
 * 固定主键 id=1 保证单行。
 */
@Entity(tableName = "affection_cache")
public class AffectionCacheEntity {

    /** 固定单行主键 */
    @PrimaryKey
    public int id = 1;

    /** 共享积分（pair 级唯一值） */
    @ColumnInfo(name = "points")
    public int points;

    /** 共享等级（1-6） */
    @ColumnInfo(name = "level")
    public int level;

    /** 当前等级进度（0~1） */
    @ColumnInfo(name = "progress")
    public double progress;

    /** 等级称号（服务端下发，如 心动/热恋/情深） */
    @ColumnInfo(name = "title")
    public String title;

    /** 快照更新时间（epoch ms） */
    @ColumnInfo(name = "updatedAt")
    public long updatedAt;
}
