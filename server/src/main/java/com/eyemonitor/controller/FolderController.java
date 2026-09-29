package com.eyemonitor.controller;

import com.eyemonitor.entity.FolderEntity;
import com.eyemonitor.entity.MediaFileEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.repository.FolderRepo;
import com.eyemonitor.repository.MediaFileRepo;
import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.service.PairService;
import com.eyemonitor.web.ApiResponse;
import com.eyemonitor.web.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图库文件夹（共享给配对双方）。
 * 删除文件夹后其内媒体自动归「未分类」（folder_id = null），不丢数据。
 */
@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderRepo folderRepo;
    private final MediaFileRepo mediaFileRepo;
    private final PairService pairService;

    public FolderController(FolderRepo folderRepo, MediaFileRepo mediaFileRepo,
                            PairService pairService) {
        this.folderRepo = folderRepo;
        this.mediaFileRepo = mediaFileRepo;
        this.pairService = pairService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        String pairCode = pairService.getPairCodeOfUser(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (pairCode == null) return ApiResponse.ok(out);
        for (FolderEntity f : folderRepo.findByPairCode(pairCode)) {
            out.add(view(f));
        }
        return ApiResponse.ok(out);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        String pairCode = pairService.getPairCodeOfUser(userId);
        if (pairCode == null) throw new BizException(403, "未配对");
        String name = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
        if (name == null || name.isEmpty()) throw new BizException(400, "文件夹名称不能为空");
        if (name.length() > 30) throw new BizException(400, "文件夹名称不能超过 30 个字符");

        FolderEntity f = new FolderEntity();
        f.setPairCode(pairCode);
        f.setName(name);
        f.setCreator(userId);
        f.setCreatedAt(System.currentTimeMillis());
        folderRepo.save(f);

        pairService.forwardToPeerByUser(userId, WsMessage.createFolderSync(
                null, pairCode, "upsert", f.getId(), f.getName()));
        return ApiResponse.ok(view(f));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        FolderEntity f = folderRepo.findById(id).orElseThrow(() -> new BizException(404, "文件夹不存在"));
        if (!pairService.belongsToPair(userId, f.getPairCode())) {
            throw new BizException(403, "无权操作该文件夹");
        }
        String pairCode = f.getPairCode();
        // 内容归未分类（不删媒体）
        for (MediaFileEntity m : mediaFileRepo.findByFolderId(id)) {
            m.setFolderId(null);
            mediaFileRepo.save(m);
        }
        folderRepo.delete(f);

        pairService.forwardToPeerByUser(userId, WsMessage.createFolderSync(
                null, pairCode, "delete", f.getId(), null));
        return ApiResponse.ok(null);
    }

    private Map<String, Object> view(FolderEntity f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("name", f.getName());
        m.put("createdAt", f.getCreatedAt());
        return m;
    }
}