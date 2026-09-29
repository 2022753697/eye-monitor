package com.eyemonitor.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 纪念日缓存实体（服务器真源 eye_anniversaries，本表为离线缓存）。
 */
@Entity(tableName = "anniversary_cache")
public class AnniversaryCacheEntity {

    /** 服务器端纪念日 ID（与账号无关，属配对级） */
    @PrimaryKey
    public long serverId;

    public String name;

    /** yyyy-MM-dd */
    public String date;

    /** 是否每年重复 */
    public boolean repeat;

    /** 是否本人添加（本地标记；服务器同步数据默认取 peer 视角可空） */
    public boolean isMine;

    public long updatedAt;

    public AnniversaryCacheEntity() {}
}