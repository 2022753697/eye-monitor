package com.eyemonitor.service;

import com.eyemonitor.repository.ChatMessageRepo;
import com.eyemonitor.repository.LocationPointRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 数据保留清理（P7）。
 * <p>
 * 聊天记录与轨迹点保留 30 天，每天凌晨 3 点自动清理；
 * 媒体文件永久保留（豁免清理），仅解除配对时删除。
 */
@Service
public class DataCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupService.class);
    /** 保留窗口：30 天（毫秒） */
    private static final long RETENTION_MS = 30L * 24 * 3600 * 1000;

    private final ChatMessageRepo chatMessageRepo;
    private final LocationPointRepo locationPointRepo;

    public DataCleanupService(ChatMessageRepo chatMessageRepo, LocationPointRepo locationPointRepo) {
        this.chatMessageRepo = chatMessageRepo;
        this.locationPointRepo = locationPointRepo;
    }

    /** 每天 03:00 清理过期聊天记录与轨迹点（媒体永久保留，不清理） */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanExpiredData() {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        try {
            long chats = chatMessageRepo.deleteByTsBefore(cutoff);
            long points = locationPointRepo.deleteByTsBefore(cutoff);
            log.info("数据保留清理完成: 删除聊天 {} 条, 轨迹点 {} 条 (cutoff={})",
                    chats, points, Instant.ofEpochMilli(cutoff));
        } catch (Exception e) {
            log.error("数据保留清理失败", e);
        }
    }
}
