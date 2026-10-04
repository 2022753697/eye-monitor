package com.eyemonitor.ui.chat;

/** 聊天列表消息条目（⑧ 切片重构：从 MainActivity 迁出）。 */
public class ChatItem {
    public static final int TYPE_SELF = 0;
    public static final int TYPE_PEER = 1;
    public static final int TYPE_SYSTEM = 2;
    public static final int TYPE_MEDIA_SELF = 3;
    public static final int TYPE_MEDIA_PEER = 4;
    public static final int TYPE_TASK_SELF = 5;
    public static final int TYPE_TASK_PEER = 6;

    public final int type;
    public final String text;
    public final String from;
    public final String time;
    public final long ts;
    /** P2：对方已读（自己消息） / 已撤回 / 引用 */
    public boolean peerRead;
    public boolean deleted;
    public long refMsgId;
    public String refText;
    /** 送达状态：发送失败（断网/未连接）时标红「未送达」，可点击重发 */
    public boolean failed;
    /** 引用跳转定位闪烁（瞬态 UI 标记：bind 时脉动高亮） */
    public boolean flash;
    /** 任务气泡（TYPE_TASK_*）：从 TaskEntity 拷贝的展示字段 */
    public String taskContent;
    public String taskReward;
    public String taskStatusText;
    public int taskStatusColor;
    public boolean taskMine;
    /** 任务配图（逗号串，取第一张渲染） */
    public String taskMediaIds;

    public ChatItem(int type, String text, String from, String time, long ts) {
        this(type, text, from, time, ts, 0, null);
    }

    public ChatItem(int type, String text, String from, String time, long ts,
                    long refMsgId, String refText) {
        this.type = type;
        this.text = text;
        this.from = from;
        this.time = time;
        this.ts = ts;
        this.refMsgId = refMsgId;
        this.refText = refText;
    }
}