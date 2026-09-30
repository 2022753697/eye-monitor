package com.eyemonitor.controller;

import com.eyemonitor.entity.MediaFileEntity;
import com.eyemonitor.entity.UserEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.repository.MediaFileRepo;
import com.eyemonitor.repository.UserRepo;
import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.service.MediaService;
import com.eyemonitor.service.PairService;
import com.eyemonitor.web.ApiResponse;
import com.eyemonitor.web.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 媒体上传 / 下载（支持 Range）/ 删除 / 列表 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaFileRepo mediaFileRepo;
    private final MediaService mediaService;
    private final PairService pairService;
    private final UserRepo userRepo;
    private final com.eyemonitor.repository.FolderRepo folderRepo;

    public MediaController(MediaFileRepo mediaFileRepo, MediaService mediaService,
                           PairService pairService, UserRepo userRepo,
                           com.eyemonitor.repository.FolderRepo folderRepo) {
        this.mediaFileRepo = mediaFileRepo;
        this.mediaService = mediaService;
        this.pairService = pairService;
        this.userRepo = userRepo;
        this.folderRepo = folderRepo;
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                   @RequestParam("pairCode") String pairCode,
                                                   @RequestParam(value = "folderId", required = false) Long folderId,
                                                   HttpServletRequest request) throws IOException {
        long userId = AuthUtil.currentUserId(request);
        if (!pairService.belongsToPair(userId, pairCode)) {
            throw new BizException(403, "无权上传到该配对");
        }
        if (file == null || file.isEmpty()) throw new BizException(400, "文件为空");
        if (file.getSize() > MediaService.MAX_MEDIA_BYTES) {
            throw new BizException(400, "文件不能超过 100MB");
        }
        String ext = MediaService.extOf(file.getContentType());
        if (ext == null) {
            // Content-Type 缺失/异常时按文件名后缀兜底（相册 heic/heif 等）
            ext = MediaService.extOfFileName(file.getOriginalFilename());
        }
        if (ext == null) {
            throw new BizException(400, "仅支持图片(jpg/png/webp)、视频(mp4/mov/3gp)或音频(m4a/mp3/aac)");
        }

        String relPath = mediaService.storeMedia(file.getBytes(), ext);
        MediaFileEntity e = new MediaFileEntity();
        e.setFileId(extractFileId(relPath));
        e.setPairCode(pairCode);
        e.setUploader(userId);
        e.setFileName(file.getOriginalFilename() == null ? file.getName() : file.getOriginalFilename());
        e.setMime(file.getContentType());
        e.setSize(file.getSize());
        e.setDuration(null);
        e.setPath(relPath);
        e.setCreatedAt(System.currentTimeMillis());
        e.setDeleted(false);
        e.setFolderId(folderId);
        mediaFileRepo.save(e);

        // 广播 media 元数据给配对对端（通知仅转发）
        UserEntity u = userRepo.findById(userId).orElse(null);
        String from = u == null ? null : u.getNickname();
        pairService.forwardToPeerByUser(userId, WsMessage.createMedia(
                u == null ? null : u.getDeviceId(), pairCode, e.getFileId(), e.getFileName(),
                e.getMime(), e.getSize(), e.getDuration(), from, e.getFolderId()));

        return ApiResponse.ok(view(e));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(value = "folderId", required = false) Long folderId,
                                                      HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        String pairCode = pairService.getPairCodeOfUser(userId);
        if (pairCode == null) return ApiResponse.ok(new ArrayList<>());
        List<MediaFileEntity> files = folderId == null
                ? mediaFileRepo.findByPairCode(pairCode)
                : mediaFileRepo.findByPairCodeAndFolderId(pairCode, folderId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaFileEntity e : files) {
            if (e.isDeleted()) continue;
            out.add(view(e));
        }
        return ApiResponse.ok(out);
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<ResourceRegion> download(@PathVariable String fileId,
                                                   HttpServletRequest request) throws IOException {
        MediaFileEntity e = mediaFileRepo.findById(fileId)
                .orElseThrow(() -> new BizException(404, "文件不存在"));
        if (e.isDeleted()) throw new BizException(404, "文件不存在");
        Path p = mediaService.resolveMedia(e.getPath());
        if (!Files.exists(p)) throw new BizException(404, "文件不存在");

        Resource resource = new FileSystemResource(p);
        long length = Files.size(p);
        MediaType mediaType = MediaType.parseMediaType(e.getMime() == null
                ? "application/octet-stream" : e.getMime());

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader == null) {
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(length)
                    .body(new ResourceRegion(resource, 0, length));
        }
        List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
        if (ranges.isEmpty()) {
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(length)
                    .body(new ResourceRegion(resource, 0, length));
        }
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(length);
        long end = range.getRangeEnd(length);
        long contentLength = end - start + 1;
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .contentLength(contentLength)
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length)
                .body(new ResourceRegion(resource, start, contentLength));
    }

    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> delete(@PathVariable String fileId, HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        MediaFileEntity e = mediaFileRepo.findById(fileId)
                .orElseThrow(() -> new BizException(404, "文件不存在"));
        if (!pairService.belongsToPair(userId, e.getPairCode())) {
            throw new BizException(403, "无权删除该文件");
        }
        if (e.getPath() != null) {
            mediaService.deleteFileByRelPath(e.getPath());
        }
        mediaFileRepo.delete(e);
        UserEntity u = userRepo.findById(userId).orElse(null);
        pairService.forwardToPeerByUser(userId, WsMessage.createMediaDeleted(
                u == null ? null : u.getDeviceId(), e.getPairCode(), fileId));
        return ApiResponse.ok(null);
    }

    /** 移动媒体到文件夹（folderId 为 null 或 0 → 未分类），只允许配对成员操作 */
    @PutMapping("/{fileId}/folder")
    public ApiResponse<Map<String, Object>> moveFolder(@PathVariable String fileId,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        MediaFileEntity e = mediaFileRepo.findById(fileId)
                .orElseThrow(() -> new BizException(404, "文件不存在"));
        if (e.isDeleted()) throw new BizException(404, "文件不存在");
        if (!pairService.belongsToPair(userId, e.getPairCode())) {
            throw new BizException(403, "无权操作该文件");
        }
        Object f = body.get("folderId");
        Long folderId = null;
        if (f instanceof Number && ((Number) f).longValue() > 0) {
            folderId = ((Number) f).longValue();
            if (folderRepo.findById(folderId).isEmpty()
                    || !pairService.belongsToPair(userId, pairCodeOf(folderId))) {
                throw new BizException(404, "文件夹不存在");
            }
        }
        e.setFolderId(folderId);
        mediaFileRepo.save(e);
        // 广播元数据（带 folderId）给对端，保持双方文件夹归类一致
        UserEntity u = userRepo.findById(userId).orElse(null);
        pairService.forwardToPeerByUser(userId, WsMessage.createMedia(
                u == null ? null : u.getDeviceId(), e.getPairCode(), e.getFileId(), e.getFileName(),
                e.getMime(), e.getSize(), e.getDuration(), u == null ? null : u.getNickname(), e.getFolderId()));
        return ApiResponse.ok(view(e));
    }

    private String pairCodeOf(Long folderId) {
        return folderRepo.findById(folderId)
                .map(com.eyemonitor.entity.FolderEntity::getPairCode).orElse(null);
    }

    // --- 辅助 ---

    private String extractFileId(String relPath) {
        String name = relPath.substring(relPath.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private Map<String, Object> view(MediaFileEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileId", e.getFileId());
        m.put("fileName", e.getFileName());
        m.put("mime", e.getMime());
        m.put("size", e.getSize());
        m.put("duration", e.getDuration());
        m.put("uploader", e.getUploader());
        m.put("createdAt", e.getCreatedAt());
        m.put("url", ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/media/{fileId}").buildAndExpand(e.getFileId()).toUriString());
        return m;
    }
}