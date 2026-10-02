package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 媒体文件元数据表（eye_media_files）；文件本体存磁盘 data/media/yyyy/MM/dd/{fileId}.{ext} */
@Entity
@Table(name = "eye_media_files", indexes = @Index(name = "idx_media_pair", columnList = "pair_code"))
public class MediaFileEntity {

    @Id
    @Column(name = "file_id", length = 64)
    private String fileId;

    @Column(name = "pair_code", nullable = false, length = 16)
    private String pairCode;

    @Column(name = "uploader")
    private Long uploader;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(length = 64)
    private String mime;

    private long size;

    private Long duration;

    @Column(length = 255)
    private String path;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    /** 所属图库文件夹 id；null = 未分类 */
    @Column(name = "folder_id")
    private Long folderId;

    @Column(nullable = false)
    private boolean deleted;

    /** 任务专用媒体（发布任务配图）：true=不进共享图库列表、不广播媒体气泡（仅任务气泡渲染用） */
    @Column(name = "task_only", nullable = false)
    private boolean taskOnly;

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public Long getUploader() { return uploader; }
    public void setUploader(Long uploader) { this.uploader = uploader; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public Long getFolderId() { return folderId; }
    public void setFolderId(Long folderId) { this.folderId = folderId; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public boolean isTaskOnly() { return taskOnly; }
    public void setTaskOnly(boolean taskOnly) { this.taskOnly = taskOnly; }
}