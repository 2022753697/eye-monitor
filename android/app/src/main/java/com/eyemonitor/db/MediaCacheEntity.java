package com.eyemonitor.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 媒体缓存实体（服务器真源 eye_media_files + 磁盘文件；本表记录 fileId -> 本地缓存路径）。
 */
@Entity(tableName = "media_cache")
public class MediaCacheEntity {

    @PrimaryKey
    @NonNull
    public String fileId;

    public String serverFileName;
    public String mime;
    public long size;

    /** 时长毫秒（视频），照片为 0 */
    public long duration;

    /** 语音波形包络（WaveformAnalyzer 算的 40 段 CSV "0.31,0.52,..."）；旧消息/未分析为 null */
    public String waveform;

    /** 所属图库文件夹 id；null = 未分类 */
    public Long folderId;

    /** 本地缓存完整路径（getFilesDir()/media/{fileId}），未下载为 null */
    public String localPath;

    public long ts;

    public MediaCacheEntity() {}
}