package com.eyemonitor.service;

import com.eyemonitor.web.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件存储服务：
 * - 媒体：data/media/yyyy/MM/dd/{fileId}.{ext}（按天分目录）
 * - 头像：data/avatar/{userId}.{ext}
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    public static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;      // 5MB
    public static final long MAX_MEDIA_BYTES = 100L * 1024 * 1024;     // 100MB

    @Value("${app.media-dir}")
    private String mediaDir;

    @Value("${app.avatar-dir}")
    private String avatarDir;

    /** 保存媒体文件，返回相对路径 yyyy/MM/dd/{fileId}.{ext} */
    public String storeMedia(byte[] bytes, String ext) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(400, "文件为空");
        }
        String fileId = UUID.randomUUID().toString().replace("-", "");
        LocalDate today = LocalDate.now();
        String rel = today.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                + "/" + fileId + "." + ext;
        try {
            Path p = Paths.get(mediaDir, rel);
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        } catch (IOException e) {
            log.error("保存媒体文件失败", e);
            throw new BizException(500, "文件保存失败");
        }
        return rel;
    }

    /** 保存头像，返回文件名 {userId}.{ext}；avatar 字段存 avatar/{fileName} */
    public String storeAvatar(byte[] bytes, String ext, long userId) {
        String fileName = userId + "." + ext;
        try {
            Path p = Paths.get(avatarDir, fileName);
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        } catch (IOException e) {
            log.error("保存头像失败", e);
            throw new BizException(500, "头像保存失败");
        }
        return fileName;
    }

    public Path resolveMedia(String relPath) {
        sanitize(relPath);
        return Paths.get(mediaDir, relPath);
    }

    public Path resolveAvatar(String fileName) {
        sanitize(fileName);
        return Paths.get(avatarDir, fileName);
    }

    public void deleteFileByRelPath(String relPath) {
        try {
            sanitize(relPath);
            Files.deleteIfExists(Paths.get(mediaDir, relPath));
        } catch (IOException e) {
            log.warn("删除媒体文件失败: {}", relPath, e);
        }
    }

    /** 防路径穿越 */
    private void sanitize(String path) {
        if (path == null || path.isBlank()
                || path.contains("..") || path.startsWith("/") || path.startsWith("\\")) {
            throw new BizException(400, "非法文件路径");
        }
    }

    /** 按原始文件名后缀推断扩展名（Content-Type 缺失时兜底，如相册 heic/heif 文件） */
    public static String extOfFileName(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        String ext = fileName.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "jpg": case "jpeg": return "jpg";
            case "png": return "png";
            case "webp": return "webp";
            case "heic": return "heic";
            case "heif": return "heif";
            case "mp4": return "mp4";
            case "mov": return "mov";
            case "3gp": return "3gp";
            default: return null;
        }
    }

    /** 按 Content-Type 取扩展名 */
    public static String extOf(String contentType) {
        if (contentType == null) return null;
        switch (contentType.toLowerCase()) {
            case "image/jpeg": return "jpg";
            case "image/png": return "png";
            case "image/webp": return "webp";
            case "image/heic": return "heic";
            case "image/heif": return "heif";
            case "video/mp4": return "mp4";
            case "video/quicktime": return "mov";
            case "video/3gpp": return "3gp";
            default: return null;
        }
    }

    public static String contentTypeOf(String ext) {
        if (ext == null) return "application/octet-stream";
        switch (ext.toLowerCase()) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "mp4": return "video/mp4";
            case "mov": return "video/quicktime";
            case "3gp": return "video/3gpp";
            default: return "application/octet-stream";
        }
    }
}