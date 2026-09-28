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
}
