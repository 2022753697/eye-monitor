package com.eyemonitor.service;

import com.eyemonitor.repository.AnniversaryRepo;
import com.eyemonitor.repository.ChatMessageRepo;
import com.eyemonitor.repository.FenceRepo;
import com.eyemonitor.repository.FolderRepo;
import com.eyemonitor.repository.LocationPointRepo;
import com.eyemonitor.repository.MediaFileRepo;
import com.eyemonitor.repository.SosLogRepo;
import com.eyemonitor.repository.TaskRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配对数据清理（P2-4 安全加固 2026-10）：
 * 解除配对 / 账号注销共用——删除配对下全部业务数据行 + 磁盘媒体文件 + 配对身份。
 */
@Service
public class PairDataService {

    private final MediaFileRepo mediaFileRepo;
    private final MediaService mediaService;
    private final ChatMessageRepo chatMessageRepo;
    private final LocationPointRepo locationPointRepo;
    private final AnniversaryRepo anniversaryRepo;
    private final FenceRepo fenceRepo;
    private final SosLogRepo sosLogRepo;
    private final TaskRepo taskRepo;
    private final FolderRepo folderRepo;
    private final PairService pairService;

    public PairDataService(MediaFileRepo mediaFileRepo, MediaService mediaService,
                           ChatMessageRepo chatMessageRepo, LocationPointRepo locationPointRepo,
                           AnniversaryRepo anniversaryRepo, FenceRepo fenceRepo,
                           SosLogRepo sosLogRepo, TaskRepo taskRepo, FolderRepo folderRepo,
                           PairService pairService) {
        this.mediaFileRepo = mediaFileRepo;
        this.mediaService = mediaService;
        this.chatMessageRepo = chatMessageRepo;
        this.locationPointRepo = locationPointRepo;
        this.anniversaryRepo = anniversaryRepo;
        this.fenceRepo = fenceRepo;
        this.sosLogRepo = sosLogRepo;
        this.taskRepo = taskRepo;
        this.folderRepo = folderRepo;
        this.pairService = pairService;
    }

    /** 删除配对数据 + 磁盘媒体 + 配对身份（幂等：重复调用安全） */
    @Transactional
    public void deletePairData(String pairCode) {
        if (pairCode == null) return;
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
        folderRepo.deleteAll(folderRepo.findByPairCode(pairCode));
        // 配对身份（内存 + DB）
        pairService.unpair(pairCode);
    }
}