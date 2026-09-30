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
}