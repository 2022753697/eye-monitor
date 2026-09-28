package com.eyemonitor.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 聊天消息 / 系统提示实体。
 * kind: chat（聊天气泡）| system（居中系统提示，如「对方打开了微信」）
 */
@Entity(tableName = "chat")
public class ChatEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** chat | system */
    @ColumnInfo(name = "kind")
    public String kind;

    @ColumnInfo(name = "text")
    public String text;

    /** 发送者昵称（气泡模式对方消息显示） */
    @ColumnInfo(name = "from_name")
    public String fromName;

    /** 是否自己发送 */
    @ColumnInfo(name = "is_self")
    public boolean isSelf;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    public ChatEntity() {}

    @androidx.room.Ignore
    public ChatEntity(String kind, String text, String fromName, boolean isSelf, long timestamp) {
        this.kind = kind;
        this.text = text;
        this.fromName = fromName;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
    }
}
