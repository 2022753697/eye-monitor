package com.eyemonitor.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/**
 * 缓存数据访问对象（纪念日/位置/围栏/媒体 离线缓存）。
 */
@Dao
public interface CacheDao {

    // --- 纪念日 ---

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertAnniversary(AnniversaryCacheEntity entity);

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertAnniversaries(List<AnniversaryCacheEntity> entities);

    @Query("SELECT * FROM anniversary_cache ORDER BY date ASC")
    List<AnniversaryCacheEntity> getAnniversaries();

    @Query("DELETE FROM anniversary_cache WHERE serverId = :serverId")
    void deleteAnniversary(long serverId);

    @Query("DELETE FROM anniversary_cache")
    void clearAnniversaries();

    // --- 位置点 ---

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void insertLocation(LocationCacheEntity entity);

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void insertLocations(List<LocationCacheEntity> entities);

    @Query("SELECT * FROM location_cache WHERE ts >= :start AND ts <= :end ORDER BY ts ASC")
    List<LocationCacheEntity> getLocations(long start, long end);

    @Query("DELETE FROM location_cache WHERE ts < :beforeTs")
    void deleteLocationsBefore(long beforeTs);

    @Query("SELECT MAX(ts) FROM location_cache")
    long getMaxLocationTs();

    // --- 围栏 ---

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertFence(FenceCacheEntity entity);

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertFences(List<FenceCacheEntity> entities);

    @Query("SELECT * FROM fence_cache ORDER BY serverId ASC")
    List<FenceCacheEntity> getFences();

    @Query("DELETE FROM fence_cache WHERE serverId = :serverId")
    void deleteFence(long serverId);

    @Query("DELETE FROM fence_cache")
    void clearFences();

    // --- 媒体 ---

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertMedia(MediaCacheEntity entity);

    @Query("SELECT * FROM media_cache WHERE fileId = :fileId")
    MediaCacheEntity getMedia(String fileId);

    @Query("SELECT * FROM media_cache ORDER BY ts DESC")
    List<MediaCacheEntity> getMedia();

    @Query("DELETE FROM media_cache WHERE fileId = :fileId")
    void deleteMedia(String fileId);

    @Query("DELETE FROM media_cache")
    void clearMedia();

    // --- 聊天（from server 拉取历史时用） ---

    /** 本地最大聊天时间戳（增量同步 afterTs 用） */
    @Query("SELECT MAX(timestamp) FROM chat")
    long getMaxChatTs();

    // --- 应用名映射 ---

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertAppNames(List<AppNameCacheEntity> entities);

    @Query("SELECT * FROM app_name_cache")
    List<AppNameCacheEntity> getAppNames();
}