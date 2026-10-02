package com.eyemonitor.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 备忘录本地 DAO（纯本地，无服务端）。
 */
@Dao
public interface MemoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(MemoEntity memo);

    @Update
    void update(MemoEntity memo);

    @Query("DELETE FROM memo WHERE id = :id")
    void delete(long id);

    /** 全部备忘录：置顶在前，时间倒序 */
    @Query("SELECT * FROM memo ORDER BY isPinned DESC, updatedAt DESC")
    List<MemoEntity> getAll();

    /** 全部备忘录 + 子项数（列表角标） */
    @Query("SELECT memo.*, (SELECT COUNT(*) FROM memo_item WHERE memo_item.memoId = memo.id) AS itemCount " +
            "FROM memo ORDER BY memo.isPinned DESC, memo.updatedAt DESC")
    List<MemoRow> getAllWithCount();

    @Query("SELECT * FROM memo WHERE id = :id")
    MemoEntity getById(long id);

    /** 未触发的提醒（供开机重排） */
    @Query("SELECT * FROM memo WHERE reminderAt IS NOT NULL AND reminderAt > :now")
    List<MemoEntity> getPendingReminders(long now);

    /** 提醒触发后清除（一次性语义） */
    @Query("UPDATE memo SET reminderAt = NULL, updatedAt = :now WHERE id = :id")
    void clearReminder(long id, long now);

    // ---------- 子项 ----------

    @Insert
    void insertItems(List<MemoItemEntity> items);

    @Query("DELETE FROM memo_item WHERE memoId = :memoId")
    void deleteItems(long memoId);

    @Query("SELECT * FROM memo_item WHERE memoId = :memoId ORDER BY id")
    List<MemoItemEntity> getItems(long memoId);

    @Query("UPDATE memo_item SET checked = :checked WHERE id = :id")
    void setItemChecked(long id, boolean checked);
}
