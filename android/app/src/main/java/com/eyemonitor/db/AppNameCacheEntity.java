package com.eyemonitor.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 应用名映射缓存（服务器 eye_app_names 的本地镜像）。
 * <p>
 * 用于「对方打开了微信」这类中文应用名展示；
 * 由 SyncManager 在登录/重连时拉取 /api/app-names 写入，AppNameResolver 读内存缓存。
 */
@Entity(tableName = "app_name_cache")
public class AppNameCacheEntity {

    @PrimaryKey
    @androidx.annotation.NonNull
    @ColumnInfo(name = "package_name")
    public String packageName;

    @androidx.annotation.NonNull
    @ColumnInfo(name = "app_name")
    public String appName;

    public AppNameCacheEntity() {}

    @androidx.room.Ignore
    public AppNameCacheEntity(String packageName, String appName) {
        this.packageName = packageName;
        this.appName = appName;
    }
}
