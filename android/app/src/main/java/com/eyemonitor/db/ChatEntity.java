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

    /** P2：对方已读我的消息（chat_read 落地） */
    @ColumnInfo(name = "peer_read", defaultValue = "0")
    public boolean peerRead;

    /** P2：已撤回（本地+对方+离线补收均渲染「已撤回」） */
    @ColumnInfo(name = "deleted", defaultValue = "0")
    public boolean deleted;

    /** P2：被引用消息时间戳（0=无引用；两端一致的消息标识） */
    @ColumnInfo(name = "ref_msg_id", defaultValue = "0")
    public long refMsgId;

    /** P2：引用摘要文本 */
    @ColumnInfo(name = "ref_text")
    public String refText;

    /** 发送状态（自己消息）：sent=已送达服务器 / pending=未送达待重发（重启后标记不丢） */
    @ColumnInfo(name = "send_state", defaultValue = "'sent'")
    public String sendState = "sent";

    public ChatEntity() {}

    @androidx.room.Ignore
    public ChatEntity(String kind, String text, String fromName, boolean isSelf, long timestamp) {
        this.kind = kind;
        this.text = text;
        this.fromName = fromName;
        this.isSelf = isSelf;
        this.timestamp = timestamp;
    }

    @androidx.room.Ignore
    public ChatEntity(String kind, String text, String fromName, boolean isSelf, long timestamp,
                      long refMsgId, String refText) {
        this(kind, text, fromName, isSelf, timestamp);
        this.refMsgId = refMsgId;
        this.refText = refText;
    }
}
