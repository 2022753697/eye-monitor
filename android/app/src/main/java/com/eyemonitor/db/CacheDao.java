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

    @Query("SELECT * FROM fence_cache WHERE serverId = :serverId")
    FenceCacheEntity getFenceById(long serverId);

    @Query("DELETE FROM fence_cache WHERE serverId = :serverId")
    void deleteFence(long serverId);

    @Query("DELETE FROM fence_cache")
    void clearFences();

    // --- 媒体 ---

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertMedia(MediaCacheEntity entity);

    @Query("SELECT * FROM media_cache WHERE fileId = :fileId")
    MediaCacheEntity getMedia(String fileId);

    // 聊天元数据查询：必须返回全部媒体（含语音），Chat 气泡渲染依赖完整 mime/waveform
    @Query("SELECT * FROM media_cache ORDER BY ts DESC")
    List<MediaCacheEntity> getMedia();

    @Query("DELETE FROM media_cache WHERE fileId = :fileId")
    void deleteMedia(String fileId);

    /** 删除不在服务端列表中的陈旧缓存行（同步对账：服务端已删的本地行必须清掉，防图库幽灵图） */
    @Query("DELETE FROM media_cache WHERE fileId NOT IN (:ids)")
    void deleteMediaNotIn(java.util.List<String> ids);

    @Query("DELETE FROM media_cache")
    void clearMedia();

    // --- 图库文件夹 ---

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertFolder(FolderCacheEntity entity);

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertFolders(List<FolderCacheEntity> entities);

    @Query("SELECT * FROM folder_cache ORDER BY ts ASC, id ASC")
    List<FolderCacheEntity> getFolders();

    @Query("DELETE FROM folder_cache WHERE id = :id")
    void deleteFolder(long id);

    @Query("DELETE FROM folder_cache")
    void clearFolders();

    @Query("SELECT * FROM media_cache WHERE folderId = :folderId AND (mime IS NULL OR mime NOT LIKE 'audio/%') ORDER BY ts DESC")
    List<MediaCacheEntity> getMediaByFolder(Long folderId);

    @Query("SELECT * FROM media_cache WHERE folderId IS NULL AND (mime IS NULL OR mime NOT LIKE 'audio/%') ORDER BY ts DESC")
    List<MediaCacheEntity> getMediaUnfiled();

    // --- 聊天（from server 拉取历史时用） ---

    /** 本地最大聊天时间戳（增量同步 afterTs 用） */
    @Query("SELECT MAX(timestamp) FROM chat")
    long getMaxChatTs();

    /** 对方消息最大时间戳（增量同步安全游标：自己的消息不推进游标，防离线补发漏收） */
    @Query("SELECT MAX(timestamp) FROM chat WHERE is_self = 0")
    long getMaxPeerChatTs();

    // --- 应用名映射 ---

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertAppNames(List<AppNameCacheEntity> entities);

    @Query("SELECT * FROM app_name_cache")
    List<AppNameCacheEntity> getAppNames();

    // --- 亲密度/等级缓存（affection_cache 单行，服务器为权威） ---

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void upsertAffection(AffectionCacheEntity entity);

    @Query("SELECT * FROM affection_cache LIMIT 1")
    AffectionCacheEntity getAffection();
}