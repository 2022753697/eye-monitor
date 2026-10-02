package com.eyemonitor.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/**
 * 聊天记录数据访问对象。
 */
@Dao
public interface ChatDao {

    @Insert
    void insert(ChatEntity entity);

    /** 按时间正序取全部记录 */
    @Query("SELECT * FROM chat ORDER BY timestamp ASC")
    List<ChatEntity> getAll();

    /** E5 只读：近 N 天消息时间戳（连续互聊聚合用，无写入） */
    @Query("SELECT timestamp FROM chat WHERE timestamp >= :since ORDER BY timestamp ASC")
    List<Long> getRecentTimestamps(long since);

    /** 清空聊天记录 */
    @Query("DELETE FROM chat")
    void clear();

    /** 删除 timestamp 早于 beforeTs 的记录（30 天本地缓存保留清理） */
    @Query("DELETE FROM chat WHERE timestamp < :beforeTs")
    void deleteBefore(long beforeTs);

    /** 删除指定媒体的聊天气泡（media_deleted 双向同步时清理本地） */
    @Query("DELETE FROM chat WHERE kind = 'media' AND text = :fileId")
    void deleteMediaChat(String fileId);

    /** 指定媒体聊天气泡数量（上传后服务器与发送端都可能广播 media，按 fileId 去重） */
    @Query("SELECT COUNT(*) FROM chat WHERE kind = 'media' AND text = :fileId")
    int countMediaChat(String fileId);

    /** 增量历史去重：本地已存在相同 (kind, ts, text) 则跳过（防自述消息被服务器回放成重复行） */
    @Query("SELECT COUNT(*) FROM chat WHERE kind = :kind AND timestamp = :ts AND text = :text")
    int countByKindTsText(String kind, long ts, String text);

    /** P2：标记自己发送、ts 不晚于 upToTs 的消息为对方已读 */
    @Query("UPDATE chat SET peer_read = 1 WHERE is_self = 1 AND timestamp <= :upToTs")
    void markOwnRead(long upToTs);

    /** P2：按时间戳标记消息已撤回 */
    @Query("UPDATE chat SET deleted = 1 WHERE timestamp = :msgTs")
    void markDeletedByTs(long msgTs);

    /** 发送状态：标记未送达（待重发） */
    @Query("UPDATE chat SET send_state = 'pending' WHERE is_self = 1 AND timestamp = :msgTs")
    void markSendPending(long msgTs);

    /** 发送状态：已送达（chat_ack 确认） */
    @Query("UPDATE chat SET send_state = 'sent' WHERE is_self = 1 AND timestamp = :msgTs")
    void markSendSent(long msgTs);

    /** 未送达的自己消息（重连后自动补发） */
    @Query("SELECT * FROM chat WHERE is_self = 1 AND kind = 'chat' AND send_state = 'pending' ORDER BY timestamp ASC")
    List<ChatEntity> getPendingSelf();
}
