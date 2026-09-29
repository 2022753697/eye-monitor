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
}
