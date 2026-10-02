package com.eyemonitor.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 情侣任务本地缓存 DAO。
 */
@Dao
public interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(TaskEntity entity);

    /** 全部任务（新→旧） */
    @Query("SELECT * FROM task_cache ORDER BY ts DESC")
    List<TaskEntity> getAll();

    @Query("SELECT * FROM task_cache WHERE taskId = :taskId")
    TaskEntity get(String taskId);

    @Query("SELECT COUNT(*) FROM task_cache WHERE taskId = :taskId")
    int count(String taskId);

    /** 更新状态 + 拒绝理由（响应/确认后的本地同步） */
    @Query("UPDATE task_cache SET status = :status, reason = :reason WHERE taskId = :taskId")
    void updateStatus(String taskId, String status, String reason);

    /** 更新状态 + 完成/兑现时间戳 */
    @Query("UPDATE task_cache SET status = :status, completedTs = :completedTs, rewardedTs = :rewardedTs WHERE taskId = :taskId")
    void updateStatusWithTs(String taskId, String status, long completedTs, long rewardedTs);

    @Query("DELETE FROM task_cache")
    void clear();
}
