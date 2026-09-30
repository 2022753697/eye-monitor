package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 聊天消息表（eye_chat_messages） */
@Entity
@Table(name = "eye_chat_messages", indexes = @Index(name = "idx_chat_pair_ts", columnList = "pair_code,ts"))
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pair_code", nullable = false, length = 16)
    private String pairCode;

    @Column(name = "from_user")
    private Long fromUser;

    @Column(nullable = false, length = 2000)
    private String text;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    @Column(nullable = false)
    private long ts;

    /** 消息种类：chat=文本 / media=媒体(fileId) / system=系统提示（历史同步按此重建聊天气泡） */
    @Column(nullable = false, length = 16)
    private String kind = "chat";

    /** 已读时间戳（对方看过我的消息；chat_read 时更新，null=未读） */
    @Column(name = "read_ts")
    private Long readTs;

    /** 已撤回标记（chat_recall 2 分钟窗内置位；拉历史带出，两端渲染「已撤回」） */
    @Column(nullable = false)
    private boolean deleted;

    /** 引用消息 id（客户端语义：被引用消息时间戳，两端一致；0=无引用） */
    @Column(name = "ref_msg_id")
    private Long refMsgId;

    /** 引用摘要文本（气泡内小字展示） */
    @Column(name = "ref_text")
    private String refText;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public Long getFromUser() { return fromUser; }
    public void setFromUser(Long fromUser) { this.fromUser = fromUser; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public boolean isSystem() { return isSystem; }
    public void setSystem(boolean system) { isSystem = system; }

    public long getTs() { return ts; }
    public void setTs(long ts) { this.ts = ts; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind == null ? "chat" : kind; }

    public Long getReadTs() { return readTs; }
    public void setReadTs(Long readTs) { this.readTs = readTs; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public Long getRefMsgId() { return refMsgId; }
    public void setRefMsgId(Long refMsgId) { this.refMsgId = refMsgId; }

    public String getRefText() { return refText; }
    public void setRefText(String refText) { this.refText = refText; }
}