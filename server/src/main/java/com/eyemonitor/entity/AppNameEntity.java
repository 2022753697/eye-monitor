package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 应用名映射（eye_app_names）。
 * <p>
 * 把客户端原来写死的「包名 → 应用名」映射表迁到数据库，
 * 运行时通过 GET /api/app-names 下发，客户端缓存并按需更新，免改代码维护新应用名。
 */
@Entity
@Table(name = "eye_app_names")
public class AppNameEntity {

    @Id
    @Column(name = "package_name", length = 128,
            columnDefinition = "VARCHAR(128) NOT NULL COMMENT '应用包名（主键，唯一）'")
    private String packageName;

    @Column(name = "app_name", length = 64,
            columnDefinition = "VARCHAR(64) NOT NULL COMMENT '展示用应用名（如 微信）'")
    private String appName;

    @Column(name = "created_at",
            columnDefinition = "BIGINT NOT NULL COMMENT '创建时间（epoch ms）'")
    private long createdAt;

    public AppNameEntity() {}

    public AppNameEntity(String packageName, String appName, long createdAt) {
        this.packageName = packageName;
        this.appName = appName;
        this.createdAt = createdAt;
    }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
