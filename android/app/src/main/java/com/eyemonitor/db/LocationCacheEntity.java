package com.eyemonitor.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 位置点缓存实体（服务器真源 eye_location_points，本表为离线缓存，轨迹回放读用）。
 */
@Entity(tableName = "location_cache")
public class LocationCacheEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public double lat;
    public double lng;
    public float accuracy;
    public long ts;

    public LocationCacheEntity() {}
}