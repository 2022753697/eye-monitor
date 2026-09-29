package com.eyemonitor.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** 图库文件夹缓存（服务器真源 eye_folders，共享给配对双方） */
@Entity(tableName = "folder_cache")
public class FolderCacheEntity {

    @PrimaryKey
    public long id;

    public String name;

    public long ts;

    public FolderCacheEntity() {}
}