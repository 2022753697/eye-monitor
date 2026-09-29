package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 图库文件夹（共享给配对双方）；媒体通过 eye_media_files.folder_id 归组，null = 未分类 */
@Entity
@Table(name = "eye_folders", indexes = @Index(name = "idx_folder_pair", columnList = "pair_code"))
public class FolderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pair_code", nullable = false, length = 16)
    private String pairCode;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(name = "creator")
    private Long creator;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getCreator() { return creator; }
    public void setCreator(Long creator) { this.creator = creator; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}