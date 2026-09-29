package com.eyemonitor.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 围栏缓存实体（服务器真源 eye_fences，本表为离线缓存；判定仍在客户端实时做）。
 */
@Entity(tableName = "fence_cache")
public class FenceCacheEntity {

    @PrimaryKey
    public long serverId;

    public String name;
    public double lat;
    public double lng;
    public double radius;
    public boolean enabled;

    public FenceCacheEntity() {}
}