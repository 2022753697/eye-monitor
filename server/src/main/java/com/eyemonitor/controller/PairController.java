package com.eyemonitor.controller;

import com.eyemonitor.entity.UserEntity;
import com.eyemonitor.repository.AnniversaryRepo;
import com.eyemonitor.repository.ChatMessageRepo;
import com.eyemonitor.repository.FenceRepo;
import com.eyemonitor.repository.LocationPointRepo;
import com.eyemonitor.repository.MediaFileRepo;
import com.eyemonitor.repository.SosLogRepo;
import com.eyemonitor.repository.TaskRepo;
import com.eyemonitor.repository.UserRepo;
import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.service.MediaService;
import com.eyemonitor.service.PairService;
import com.eyemonitor.service.PairService.PairInfo;
import com.eyemonitor.util.ProfileView;
import com.eyemonitor.web.ApiResponse;
import com.eyemonitor.web.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** 配对查询 / 解除配对（业务数据清理） */
@RestController
@RequestMapping("/api/pairs")
public class PairController {

    private final PairService pairService;
    private final UserRepo userRepo;
    private final ChatMessageRepo chatMessageRepo;
    private final LocationPointRepo locationPointRepo;
    private final AnniversaryRepo anniversaryRepo;
    private final FenceRepo fenceRepo;
    private final MediaFileRepo mediaFileRepo;
    private final SosLogRepo sosLogRepo;
    private final TaskRepo taskRepo;
    private final MediaService mediaService;

    public PairController(PairService pairService, UserRepo userRepo,
                          ChatMessageRepo chatMessageRepo, LocationPointRepo locationPointRepo,
                          AnniversaryRepo anniversaryRepo, FenceRepo fenceRepo,
                          MediaFileRepo mediaFileRepo, SosLogRepo sosLogRepo, MediaService mediaService,
                          TaskRepo taskRepo) {
        this.pairService = pairService;
        this.userRepo = userRepo;
        this.chatMessageRepo = chatMessageRepo;
        this.locationPointRepo = locationPointRepo;
        this.anniversaryRepo = anniversaryRepo;
        this.fenceRepo = fenceRepo;
        this.mediaFileRepo = mediaFileRepo;
        this.sosLogRepo = sosLogRepo;
        this.taskRepo = taskRepo;
        this.mediaService = mediaService;
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        PairInfo pair = pairService.getPairOfUser(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (pair == null) {
            m.put("pairCode", null);
            m.put("peerProfile", null);
            return ApiResponse.ok(m);
        }
        m.put("pairCode", pair.getPairCode());
        Long peerUserId = pair.getPeerUser(userId);
        Map<String, Object> peerProfile = null;
        if (peerUserId != null) {
            UserEntity peer = userRepo.findById(peerUserId).orElse(null);
            if (peer != null) peerProfile = ProfileView.of(peer);
        }
        m.put("peerProfile", peerProfile);
        return ApiResponse.ok(m);
    }

    /** 解除配对：人员身份 + 该 pair 全部业务数据 + 磁盘媒体文件一并清理 */
    @Transactional
    @DeleteMapping("/{pairCode}")
    public ApiResponse<Void> unpair(@PathVariable String pairCode, HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        if (!pairService.belongsToPair(userId, pairCode)) {
            throw new BizException(403, "无权解除该配对");
        }
        // 媒体磁盘文件先删（由 pair 的元数据行定位）
        mediaFileRepo.findByPairCode(pairCode).forEach(f -> {
            if (f.getPath() != null && !f.isDeleted()) {
                mediaService.deleteFileByRelPath(f.getPath());
            }
            f.setDeleted(true);
        });
        mediaFileRepo.deleteByPairCode(pairCode);
        // 业务数据
        chatMessageRepo.deleteByPairCode(pairCode);
        locationPointRepo.deleteByPairCode(pairCode);
        anniversaryRepo.deleteByPairCode(pairCode);
        fenceRepo.deleteByPairCode(pairCode);
        sosLogRepo.deleteByPairCode(pairCode);
        taskRepo.deleteByPairCode(pairCode);
        // 配对身份
        pairService.unpair(pairCode);
        return ApiResponse.ok(null);
    }
}