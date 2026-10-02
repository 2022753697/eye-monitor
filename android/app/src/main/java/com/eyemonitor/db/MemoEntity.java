package com.eyemonitor.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 个人私密备忘录（纯本地，不同步服务端）。
 * <p>
 * 内容：正文（多行文字）+ 可选本地图片（逗号分隔绝对路径）+ 可勾选子项（MemoItemEntity）
 * + 一次性定时提醒（reminderAt）+ 置顶。
 */
@Entity(tableName = "memo")
public class MemoEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 正文（多行文字，必填非空） */
    public String content;

    /** 本地图片绝对路径，逗号分隔（可空） */
    public String images;

    /** 提醒时间戳 ms（null=无提醒） */
    public Long reminderAt;

    /** 置顶（列表排序最前） */
    public boolean isPinned;

    public long createdAt;

    public long updatedAt;

    /** 列表页角标：子项数（由 getAllWithCount 填充，非 DB 列） */
    @androidx.room.Ignore
    public int itemCount;

    public MemoEntity() {}
}
