package com.eyemonitor.service;

import com.eyemonitor.entity.AffectionEventLogEntity;
import com.eyemonitor.entity.AffectionStateEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.repository.AffectionEventLogRepo;
import com.eyemonitor.repository.AffectionStateRepo;
import com.eyemonitor.repository.ChatMessageRepo;
import com.eyemonitor.repository.PairRepo;
import com.eyemonitor.security.WsSessionManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 好感度积分规则引擎单元测试（纯逻辑注入式，不依赖真库：仓储/PairService 全 Mockito mock）。
 * <p>
 * 覆盖：回合交替（A发B回=1、A连发=0）、每日 50 上限截断、打卡幂等（连点/重发只 +1）、
 * 等级阈值 0/200/600/1500/3000/6000、首字自动打卡规则（「早上好呀」✓ /「今晚去看电影吗」✗ /「晚安啦么么」✓）。
 */
class AffectionServiceTest {

    private static final String PAIR = "123456";
    /** 固定时钟：2025-10-02 08:30（早窗口） */
    private static final Clock MORNING_CLOCK =
            Clock.fixed(Instant.parse("2025-10-02T00:30:00Z"), ZoneId.of("Asia/Shanghai"));
    /** 固定时钟：2025-10-02 22:30（晚窗口） */
    private static final Clock EVENING_CLOCK =
            Clock.fixed(Instant.parse("2025-10-02T14:30:00Z"), ZoneId.of("Asia/Shanghai"));

    private final AffectionStateRepo stateRepo = mock(AffectionStateRepo.class);
    private final AffectionEventLogRepo eventLogRepo = mock(AffectionEventLogRepo.class);
    private final PairRepo pairRepo = mock(PairRepo.class);
    private final ChatMessageRepo chatMessageRepo = mock(ChatMessageRepo.class);
    private final PairService pairService = mock(PairService.class);
    private final WsSessionManager wsSessionManager = mock(WsSessionManager.class);

    private AffectionService service(Clock clock) {
        return new AffectionService(stateRepo, eventLogRepo, pairRepo,
                chatMessageRepo, pairService, wsSessionManager, clock);
    }

    // ==================== 回合交替 ====================

    @Test
    void alternatingMessagesAwardOneRound() {
        AffectionService svc = service(MORNING_CLOCK);
        svc.onChatMessage(PAIR, 1L, "在吗", false, "chat", 1000L);   // A：首条不计
        svc.onChatMessage(PAIR, 2L, "在呢", false, "chat", 2000L);   // B：归属不同 → 回合 +1

        ArgumentCaptor<AffectionEventLogEntity> logCap = ArgumentCaptor.forClass(AffectionEventLogEntity.class);
        verify(eventLogRepo, times(1)).save(logCap.capture());
        assertEquals(AffectionService.SOURCE_CHAT, logCap.getValue().getSource());
        assertEquals(1, logCap.getValue().getPoints());
        // 共享池：状态行 points=1
        verify(stateRepo, times(1)).save(any(AffectionStateEntity.class));
    }

    @Test
    void sameUserConsecutiveNoRound() {
        AffectionService svc = service(MORNING_CLOCK);
        svc.onChatMessage(PAIR, 1L, "在吗", false, "chat", 1000L);
        svc.onChatMessage(PAIR, 1L, "在吗在吗", false, "chat", 2000L); // 同方连发不计

        verify(eventLogRepo, never()).save(any(AffectionEventLogEntity.class));
        verify(stateRepo, never()).save(any(AffectionStateEntity.class));
    }

    @Test
    void firstMessageOfPairNoRound() {
        AffectionService svc = service(MORNING_CLOCK);
        svc.onChatMessage(PAIR, 1L, "第一条", false, "chat", 1000L);
        verify(eventLogRepo, never()).save(any(AffectionEventLogEntity.class));
    }

    @Test
    void alternatingMediaAlsoCountsAsRound() {
        AffectionService svc = service(MORNING_CLOCK);
        svc.onChatMessage(PAIR, 1L, "file-1", false, "media", 1000L);
        svc.onChatMessage(PAIR, 2L, "file-2", false, "media", 2000L); // 媒体/语音同价
        verify(eventLogRepo, times(1)).save(any(AffectionEventLogEntity.class));
    }

    // ==================== 每日 50 上限 ====================

    @Test
    void dailyCapBlocksAt50() {
        AffectionService svc = service(MORNING_CLOCK);
        when(eventLogRepo.sumPointsBetween(anyString(), anyLong(), anyLong())).thenReturn(50L);

        boolean awarded = svc.awardPoints(PAIR, AffectionService.SOURCE_CHECK_IN,
                3, "checkin:" + PAIR + ":2025-10-02:morning:1");
        assertFalse(awarded);
        verify(eventLogRepo, never()).save(any(AffectionEventLogEntity.class));
        verify(stateRepo, never()).save(any(AffectionStateEntity.class));
    }

    @Test
    void dailyCapTruncatesToRemaining() {
        AffectionService svc = service(MORNING_CLOCK);
        when(eventLogRepo.sumPointsBetween(anyString(), anyLong(), anyLong())).thenReturn(49L);

        boolean awarded = svc.awardPoints(PAIR, AffectionService.SOURCE_CHECK_IN,
                3, "checkin:" + PAIR + ":2025-10-02:morning:1");
        assertTrue(awarded);
        ArgumentCaptor<AffectionEventLogEntity> logCap = ArgumentCaptor.forClass(AffectionEventLogEntity.class);
        verify(eventLogRepo).save(logCap.capture());
        assertEquals(1, logCap.getValue().getPoints()); // 只剩 1 分额度
        // 状态只加 1 分
        verify(stateRepo).save(org.mockito.ArgumentMatchers.argThat(s -> s.getPoints() == 1L));
    }

    // ==================== 打卡幂等 ====================

    @Test
    void checkInIdempotentSameUserOnlyOnce() {
        AffectionService svc = service(MORNING_CLOCK);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        when(eventLogRepo.existsByDedupKey(anyString()))
                .thenAnswer(inv -> seen.contains(inv.getArgument(0)));
        doAnswer(inv -> {
            seen.add(((AffectionEventLogEntity) inv.getArgument(0)).getDedupKey());
            return null;
        }).when(eventLogRepo).save(any(AffectionEventLogEntity.class));

        svc.onCheckIn(PAIR, 1L, "morning");
        svc.onCheckIn(PAIR, 1L, "morning"); // 连点/重发只 +1

        verify(eventLogRepo, times(1)).save(any(AffectionEventLogEntity.class));
        verify(stateRepo, times(1)).save(any(AffectionStateEntity.class));
    }

    @Test
    void twoUsersCanEachCheckIn() {
        AffectionService svc = service(MORNING_CLOCK);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        when(eventLogRepo.existsByDedupKey(anyString()))
                .thenAnswer(inv -> seen.contains(inv.getArgument(0)));
        doAnswer(inv -> {
            seen.add(((AffectionEventLogEntity) inv.getArgument(0)).getDedupKey());
            return null;
        }).when(eventLogRepo).save(any(AffectionEventLogEntity.class));

        svc.onCheckIn(PAIR, 1L, "morning");
        svc.onCheckIn(PAIR, 2L, "morning"); // 另一人各自打卡（dedupKey 含 user）

        verify(eventLogRepo, times(2)).save(any(AffectionEventLogEntity.class));
    }

    // ==================== 等级阈值 ====================

    @Test
    void levelThresholds() {
        assertLevel(0, 1, "初识", 0.0);
        assertLevel(199, 1, "初识", 199.0 / 200.0);
        assertLevel(200, 2, "心动", 0.0);
        assertLevel(599, 2, "心动", (599.0 - 200) / (600.0 - 200));
        assertLevel(600, 3, "热恋", 0.0);
        assertLevel(1499, 3, "热恋", (1499.0 - 600) / (1500.0 - 600));
        assertLevel(1500, 4, "情深", 0.0);
        assertLevel(2999, 4, "情深", (2999.0 - 1500) / (3000.0 - 1500));
        assertLevel(3000, 5, "挚爱", 0.0);
        assertLevel(5999, 5, "挚爱", (5999.0 - 3000) / (6000.0 - 3000));
        assertLevel(6000, 6, "永恒", 1.0);      // 满级进度恒为 1.0
        assertLevel(99999, 6, "永恒", 1.0);
    }

    private static void assertLevel(long points, int level, String title, double progress) {
        AffectionService.LevelInfo info = AffectionService.levelOf(points);
        assertEquals(level, info.level, "points=" + points);
        assertEquals(title, info.title);
        assertEquals(progress, info.progress, 1e-9, "progress@points=" + points);
    }

    // ==================== 首字自动打卡 ====================

    @Test
    void autoCheckInFirstCharRules() {
        // 「早上好呀」：早窗口内 → 早安打卡
        assertEquals("morning", AffectionService.detectAutoCheckInWindow("早上好呀", 8));
        assertEquals("morning", AffectionService.detectAutoCheckInWindow("早", 5));      // 下边界 5:00
        assertNull(AffectionService.detectAutoCheckInWindow("早上好呀", 4));              // 5:00 前不算
        assertNull(AffectionService.detectAutoCheckInWindow("早上好呀", 11));             // 11:00 起已出早窗口
        // 「今晚去看电影吗」：以「今」开头天然不算（无词表）
        assertNull(AffectionService.detectAutoCheckInWindow("今晚去看电影吗", 20));
        // 「晚安啦么么」：晚窗口内 → 晚安打卡
        assertEquals("evening", AffectionService.detectAutoCheckInWindow("晚安啦么么", 22));
        assertEquals("evening", AffectionService.detectAutoCheckInWindow("晚安啦么么", 19)); // 下边界 19:00
        assertNull(AffectionService.detectAutoCheckInWindow("晚安啦么么", 18));            // 18:00 未到晚窗口
        // 长度上限 ≤20 字
        assertNull(AffectionService.detectAutoCheckInWindow("早" + "啊".repeat(25), 8));
        assertNull(AffectionService.detectAutoCheckInWindow("", 8));
        assertNull(AffectionService.detectAutoCheckInWindow(null, 8));
    }

    @Test
    void autoCheckInViaChatMessageMorning() {
        AffectionService svc = service(MORNING_CLOCK); // 08:30 早窗口
        svc.onChatMessage(PAIR, 1L, "早上好呀", false, "chat", 1000L);
        ArgumentCaptor<AffectionEventLogEntity> logCap = ArgumentCaptor.forClass(AffectionEventLogEntity.class);
        verify(eventLogRepo, times(1)).save(logCap.capture());
        assertEquals(AffectionService.SOURCE_CHECK_IN, logCap.getValue().getSource());
        assertEquals(3, logCap.getValue().getPoints());
    }

    @Test
    void autoCheckInViaChatMessageEvening() {
        AffectionService svc = service(EVENING_CLOCK); // 22:30 晚窗口
        svc.onChatMessage(PAIR, 1L, "晚安啦么么", false, "chat", 1000L);
        ArgumentCaptor<AffectionEventLogEntity> logCap = ArgumentCaptor.forClass(AffectionEventLogEntity.class);
        verify(eventLogRepo, times(1)).save(logCap.capture());
        assertEquals(AffectionService.SOURCE_CHECK_IN, logCap.getValue().getSource());
    }

    @Test
    void chatNotMatchingFirstCharNoAutoCheckIn() {
        AffectionService svc = service(EVENING_CLOCK); // 22:30 晚窗口
        svc.onChatMessage(PAIR, 1L, "今晚去看电影吗", false, "chat", 1000L);
        verify(eventLogRepo, never()).save(any(AffectionEventLogEntity.class));
    }

    // ==================== 推送策略 ====================

    @Test
    void firstAwardPushesSnapshotToPair() {
        AffectionService svc = service(MORNING_CLOCK);
        svc.onCheckIn(PAIR, 1L, "morning");
        ArgumentCaptor<WsMessage> msgCap = ArgumentCaptor.forClass(WsMessage.class);
        verify(pairService, times(1)).broadcastToPair(org.mockito.ArgumentMatchers.eq(PAIR), msgCap.capture());
        assertEquals("affection_sync", msgCap.getValue().getType());
    }
}
