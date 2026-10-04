package com.eyemonitor.service;

import com.eyemonitor.entity.AffectionEventLogEntity;
import com.eyemonitor.entity.AffectionStateEntity;
import com.eyemonitor.entity.PairEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.repository.AffectionEventLogRepo;
import com.eyemonitor.repository.AffectionStateRepo;
import com.eyemonitor.repository.ChatMessageRepo;
import com.eyemonitor.repository.PairRepo;
import com.eyemonitor.security.WsSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 好感度/等级规则引擎（服务端记账为权威）。
 * <p>
 * 【共享性硬约束】积分与等级是 pair 级唯一值：affection_state 按 pairCode 一行，
 * 任何一方行为都写入同一行；升级是共同事件（affection_sync 推给双方）。
 * <p>
 * 规则：聊天回合 +1（交替检测）/ 打卡（手动+首字自动）+3 / 任务 +2/+5/+3 /
 * SOS 回执 +5 / 纪念日新增 +10 / 同时在线 +2 每 10 分钟 / 连续配对天数 +5 每天；
 * 每日上限 50 分（截断）；等级门槛 0/200/600/1500/3000/6000 → Lv.1 初识 … Lv.6 永恒。
 * <p>
 * 容错：所有钩子 try/catch 包住，绝不影响原链路（仿 {@link MessageStore#saveChat} 风格）。
 * <p>
 * 推送策略：每次计分后升级必推 affection_sync 给 pair 双方；非升级同 pair 60 秒节流，
 * 仅状态变化才推；走现有 {@link PairService#sendMessage} 通道。
 */
@Service
public class AffectionService {

    private static final Logger log = LoggerFactory.getLogger(AffectionService.class);

    // ==================== 分值/上限常量（服务端硬编码） ====================

    /** 每日积分上限：50 分/天（宽松防刷，正常情侣几乎触不到） */
    public static final int DAILY_CAP = 50;

    /** 聊天回合 +1 */
    public static final int POINTS_CHAT_ROUND = 1;
    /** 打卡（早安/晚安各） +3 */
    public static final int POINTS_CHECK_IN = 3;
    /** 任务接受 +2 */
    public static final int POINTS_TASK_ACCEPT = 2;
    /** 任务完成 +5 */
    public static final int POINTS_TASK_COMPLETE = 5;
    /** 任务兑现 +3 */
    public static final int POINTS_TASK_REWARD = 3;
    /** SOS 回执 +5 */
    public static final int POINTS_SOS_ACK = 5;
    /** 纪念日新增 +10 */
    public static final int POINTS_ANNIVERSARY = 10;
    /** 同时在线 +2 / 10 分钟 */
    public static final int POINTS_ONLINE = 2;
    /** 连续配对天数 +5 / 天 */
    public static final int POINTS_STREAK = 5;

    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_CHECK_IN = "check_in";
    public static final String SOURCE_TASK = "task";
    public static final String SOURCE_SOS = "sos";
    public static final String SOURCE_ANNIVERSARY = "anniversary";
    public static final String SOURCE_ONLINE = "online";
    public static final String SOURCE_STREAK = "streak";

    /** 等级门槛基准表：Lv.1 初识 0 / Lv.2 心动 200 / Lv.3 热恋 600 / Lv.4 情深 1500 / Lv.5 挚爱 3000 / Lv.6 永恒 6000 */
    public static final long[] LEVEL_THRESHOLDS = {0, 200, 600, 1500, 3000, 6000};
    /** 等级称号（与门槛一一对应） */
    public static final String[] LEVEL_TITLES = {"初识", "心动", "热恋", "情深", "挚爱", "永恒"};

    /** 首字自动打卡文本长度上限（≤20 字，全角/半角同价） */
    public static final int AUTO_CHECK_IN_MAX_LEN = 20;

    /** 早窗口 5:00-11:00 / 晚窗口 19:00-24:00 */
    static final int MORNING_START_HOUR = 5;
    static final int MORNING_END_HOUR = 11;
    static final int EVENING_START_HOUR = 19;
    static final int EVENING_END_HOUR = 24;

    /** 同时在线结算粒度：10 分钟/档（+2） */
    static final long ONLINE_TICK_MINUTES = 10;

    /** 非升级推送节流：同 pair 60 秒内不重复推 */
    static final long PUSH_THROTTLE_MS = 60_000L;

    private final Clock clock;

    // ==================== 依赖 ====================

    private final AffectionStateRepo stateRepo;
    private final AffectionEventLogRepo eventLogRepo;
    private final PairRepo pairRepo;
    private final ChatMessageRepo chatMessageRepo;
    private final PairService pairService;
    private final WsSessionManager wsSessionManager;

    @Autowired
    public AffectionService(AffectionStateRepo stateRepo, AffectionEventLogRepo eventLogRepo,
                            PairRepo pairRepo, ChatMessageRepo chatMessageRepo,
                            PairService pairService, WsSessionManager wsSessionManager) {
        this(stateRepo, eventLogRepo, pairRepo, chatMessageRepo, pairService, wsSessionManager,
                Clock.systemDefaultZone());
    }

    /** 测试用：注入固定 Clock 使时段/日期规则确定化（生产走 systemDefaultZone） */
    AffectionService(AffectionStateRepo stateRepo, AffectionEventLogRepo eventLogRepo,
                     PairRepo pairRepo, ChatMessageRepo chatMessageRepo,
                     PairService pairService, WsSessionManager wsSessionManager,
                     Clock clock) {
        this.stateRepo = stateRepo;
        this.eventLogRepo = eventLogRepo;
        this.pairRepo = pairRepo;
        this.chatMessageRepo = chatMessageRepo;
        this.pairService = pairService;
        this.wsSessionManager = wsSessionManager;
        this.clock = clock;
    }

    // ==================== 内存态 ====================

    /** pairCode → 该 pair 最后一条聊天消息归属 userId（回合交替检测；重启丢失可接受，与 PairService 内存态哲学一致） */
    private final ConcurrentHashMap<String, Long> lastChatFrom = new ConcurrentHashMap<>();
    /** pairCode → 上次推送 affection_sync 时间戳（非升级节流） */
    private final ConcurrentHashMap<String, Long> lastPushAt = new ConcurrentHashMap<>();
    /** pairCode → 计分锁（pair 级串行化：幂等判定/上限聚合/状态更新原子） */
    private final ConcurrentHashMap<String, Object> pairLocks = new ConcurrentHashMap<>();

    // ==================== 等级计算（纯逻辑） ====================

    /** 等级快照：points / level(1-6) / title / progress(0.0-1.0，Lv.6 恒为 1.0) */
    public static class LevelInfo {
        public final int level;
        public final String title;
        public final long points;
        public final double progress;

        public LevelInfo(int level, String title, long points, double progress) {
            this.level = level;
            this.title = title;
            this.points = points;
            this.progress = progress;
        }
    }

    /**
     * 纯逻辑：积分 → 等级/称号/进度。
     * progress = (points - 当前级门槛) / (下一级门槛 - 当前级门槛)；Lv.6 为 1.0。
     */
    public static LevelInfo levelOf(long points) {
        int level = 1;
        for (int i = 0; i < LEVEL_THRESHOLDS.length; i++) {
            if (points >= LEVEL_THRESHOLDS[i]) level = i + 1;
        }
        if (level >= LEVEL_THRESHOLDS.length) {
            return new LevelInfo(level, LEVEL_TITLES[level - 1], points, 1.0);
        }
        long cur = LEVEL_THRESHOLDS[level - 1];
        long next = LEVEL_THRESHOLDS[level];
        double progress = (double) (points - cur) / (double) (next - cur);
        return new LevelInfo(level, LEVEL_TITLES[level - 1], points, progress);
    }

    /** 当前共享快照（无状态行时按 0 分 Lv.1 返回） */
    public LevelInfo currentLevel(String pairCode) {
        try {
            if (pairCode == null) return levelOf(0);
            Optional<AffectionStateEntity> st = stateRepo.findById(pairCode);
            return st.map(e -> levelOf(e.getPoints())).orElseGet(() -> levelOf(0));
        } catch (Exception ex) {
            log.error("好感度快照读取失败 pair={}", pairCode, ex);
            return levelOf(0);
        }
    }

    // ==================== 计分核心 ====================

    /**
     * 计分（幂等 + 每日上限截断 + 共享状态更新 + 推送）。
     *
     * @return true=实际入账（可能因上限截断而小于 points）
     */
    public boolean awardPoints(String pairCode, String source, int points, String dedupKey) {
        if (pairCode == null || dedupKey == null || points <= 0) return false;
        Object lock = pairLocks.computeIfAbsent(pairCode, k -> new Object());
        synchronized (lock) {
            try {
                // 幂等：dedupKey 唯一索引防重（客户端事件连点/重发只记一次）
                if (eventLogRepo.existsByDedupKey(dedupKey)) return false;
                // 每日上限：当日 (pairCode, 本地日期) 聚合 ≥50 即截断
                long[] range = dayRange(LocalDate.now(clock));
                long today = eventLogRepo.sumPointsBetween(pairCode, range[0], range[1]);
                if (today >= DAILY_CAP) return false;
                int actual = (int) Math.min(points, DAILY_CAP - today);
                if (actual <= 0) return false;

                AffectionEventLogEntity logRow = new AffectionEventLogEntity();
                logRow.setPairCode(pairCode);
                logRow.setSource(source);
                logRow.setPoints(actual);
                logRow.setDedupKey(dedupKey);
                logRow.setTs(System.currentTimeMillis());
                eventLogRepo.save(logRow);

                // 共享池：pair 唯一一行，任何一方行为写入同一行
                AffectionStateEntity state = stateRepo.findById(pairCode).orElseGet(() -> {
                    AffectionStateEntity s = new AffectionStateEntity();
                    s.setPairCode(pairCode);
                    s.setPoints(0);
                    s.setLevel(1);
                    s.setUpdatedAt(System.currentTimeMillis());
                    return s;
                });
                int oldLevel = state.getLevel();
                state.setPoints(state.getPoints() + actual);
                LevelInfo info = levelOf(state.getPoints());
                state.setLevel(info.level);
                state.setUpdatedAt(System.currentTimeMillis());
                stateRepo.save(state);

                // 推送：升级必推；非升级 60 秒节流（状态已变化）
                pushIfNeeded(pairCode, info, info.level > oldLevel);
                return true;
            } catch (Exception ex) {
                // 容错：好感度是叠加层，绝不影响原链路
                log.error("好感度计分失败 pair={} source={} dedupKey={}", pairCode, source, dedupKey, ex);
                return false;
            }
        }
    }

    // ==================== 各类来源钩子 ====================

    /**
     * 聊天落库钩子（MessageStore.saveChat 调用）：
     * ① 回合交替检测 +1（ConcurrentHashMap 记最后消息归属，对方消息到来且归属不同 → 回合+1；
     *    同方连发不计；无时间窗口）；
     * ② 首字自动打卡 +3（「早」开头且在早窗口 / 「晚」开头且在晚窗口，长度 ≤20 字）。
     */
    public void onChatMessage(String pairCode, long fromUser, String text,
                              boolean isSystem, String kind, long ts) {
        try {
            if (pairCode == null || fromUser <= 0 || isSystem) return;
            long msgTs = ts > 0 ? ts : System.currentTimeMillis();
            Long last = lastChatFrom.get(pairCode);
            if (last != null && last != fromUser) {
                awardPoints(pairCode, SOURCE_CHAT, POINTS_CHAT_ROUND,
                        "chat:" + pairCode + ":" + fromUser + ":" + msgTs);
            }
            lastChatFrom.put(pairCode, fromUser);
            // 首字自动打卡：仅文本聊天（media 的 text 是 fileId，不参与）
            if (text != null && ("chat".equals(kind) || kind == null)) {
                String window = detectAutoCheckInWindow(text, LocalDateTime.now(clock).getHour());
                if (window != null) {
                    awardPoints(pairCode, SOURCE_CHECK_IN, POINTS_CHECK_IN,
                            checkInDedupKey(pairCode, window, fromUser));
                }
            }
        } catch (Exception ex) {
            log.error("聊天好感度钩子失败 pair={}", pairCode, ex);
        }
    }

    /**
     * 手动打卡（WS check_in，window=morning|evening）：+3。
     * 同 (pair, 本地日期, 时段, user) 幂等——两人可各自打卡（dedupKey 含 user），积分都进共享池。
     *
     * @return 打卡后的共享快照（幂等跳过时也为当前状态，客户端可据此刷新）
     */
    public LevelInfo onCheckIn(String pairCode, long userId, String window) {
        if (pairCode == null || userId <= 0 || window == null) return currentLevel(pairCode);
        awardPoints(pairCode, SOURCE_CHECK_IN, POINTS_CHECK_IN, checkInDedupKey(pairCode, window, userId));
        return currentLevel(pairCode);
    }

    /** 任务钩子：accept +2（TaskService 实际状态迁移处调用） */
    public void onTaskAccepted(String pairCode, String taskId) {
        awardPoints(pairCode, SOURCE_TASK, POINTS_TASK_ACCEPT,
                "task:" + pairCode + ":" + taskId + ":accept");
    }

    /** 任务钩子：complete +5 */
    public void onTaskCompleted(String pairCode, String taskId) {
        awardPoints(pairCode, SOURCE_TASK, POINTS_TASK_COMPLETE,
                "task:" + pairCode + ":" + taskId + ":complete");
    }

    /** 任务钩子：reward +3 */
    public void onTaskRewarded(String pairCode, String taskId) {
        awardPoints(pairCode, SOURCE_TASK, POINTS_TASK_REWARD,
                "task:" + pairCode + ":" + taskId + ":reward");
    }

    /** SOS 回执 +5（EyeWebSocketHandler sos_ack 分支调用；按回执时间戳幂等） */
    public void onSosAck(String pairCode, long ackTs) {
        long ts = ackTs > 0 ? ackTs : System.currentTimeMillis();
        awardPoints(pairCode, SOURCE_SOS, POINTS_SOS_ACK, "sos:" + pairCode + ":" + ts);
    }

    /** 纪念日新增 +10（AnniversaryController.create 调用；按纪念日 id 幂等） */
    public void onAnniversaryCreated(String pairCode, long anniversaryId) {
        if (anniversaryId <= 0) return;
        awardPoints(pairCode, SOURCE_ANNIVERSARY, POINTS_ANNIVERSARY,
                "anniversary:" + pairCode + ":" + anniversaryId);
    }

    // ==================== 定时任务（仿 DataCleanupService 风格） ====================

    /**
     * 同时在线 +2/10 分钟：以 WS 会话存在判定在线（WsSessionManager），
     * 按 (pair, 本地日期, 小时, 10 分钟桶) 幂等，10 分钟粒度累计。
     */
    @Scheduled(fixedRate = ONLINE_TICK_MINUTES * 60 * 1000L, initialDelay = ONLINE_TICK_MINUTES * 60 * 1000L)
    public void tickOnlineTime() {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            String bucket = now.getHour() + ":" + (now.getMinute() / 10);
            for (PairEntity pair : pairRepo.findAll()) {
                if (pair.getUserA() == null || pair.getUserB() == null) continue;
                if (!wsSessionManager.isOnline(pair.getUserA()) || !wsSessionManager.isOnline(pair.getUserB())) {
                    continue;
                }
                awardPoints(pair.getPairCode(), SOURCE_ONLINE, POINTS_ONLINE,
                        "online:" + pair.getPairCode() + ":" + now.toLocalDate() + ":" + bucket);
            }
        } catch (Exception ex) {
            log.error("同时在线计分失败", ex);
        }
    }

    /**
     * 连续配对天数 +5/天：每日 3 点结算「昨日」（配对存活整天即累计，与打卡连续互不影响）；
     * 按 (pair, 日期) 幂等。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void grantPairStreak() {
        try {
            LocalDate yesterday = LocalDate.now(clock).minusDays(1);
            long endOfYesterday = yesterday.plusDays(1).atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
            for (PairEntity pair : pairRepo.findAll()) {
                if (!PairEntity.STATUS_COMPLETE.equals(pair.getStatus())) continue;
                if (pair.getCreatedAt() <= 0 || pair.getCreatedAt() >= endOfYesterday) continue;
                awardPoints(pair.getPairCode(), SOURCE_STREAK, POINTS_STREAK,
                        "streak:" + pair.getPairCode() + ":" + yesterday);
            }
        } catch (Exception ex) {
            log.error("连续配对天数计分失败", ex);
        }
    }

    // ==================== 周对比统计（GET /api/affection） ====================

    /**
     * 近 7 天互动统计（资料页互动对比卡 A vs B 的数据源，与共享等级无关，不产生积分）：
     * chats=chat 表按 fromUser 计数；checkIns=check_in 事件按 user 计数；
     * onlineMinutes=pair 共同在线分钟（online 档数 × 10 分钟）。
     */
    public Map<String, Object> weekStats(String pairCode, long meUserId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        try {
            long since = LocalDate.now(clock).minusDays(6).atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
            long peer = 0;
            PairEntity pair = pairRepo.findByPairCode(pairCode);
            if (pair != null) peer = pair.peerOf(meUserId);

            long meChats = chatMessageRepo.countChatsByUserSince(pairCode, meUserId, since);
            long peerChats = peer > 0 ? chatMessageRepo.countChatsByUserSince(pairCode, peer, since) : 0;

            long meCheckIns = 0;
            long peerCheckIns = 0;
            for (AffectionEventLogEntity e : eventLogRepo
                    .findByPairCodeAndSourceAndTsGreaterThanEqual(pairCode, SOURCE_CHECK_IN, since)) {
                long u = parseUserFromCheckInKey(e.getDedupKey());
                if (u == meUserId) meCheckIns++;
                else if (peer > 0 && u == peer) peerCheckIns++;
            }

            long onlineTicks = eventLogRepo
                    .countByPairCodeAndSourceAndTsGreaterThanEqual(pairCode, SOURCE_ONLINE, since);
            long onlineMinutes = onlineTicks * ONLINE_TICK_MINUTES;

            Map<String, Object> chats = new LinkedHashMap<>();
            chats.put("me", meChats);
            chats.put("peer", peerChats);
            Map<String, Object> checkIns = new LinkedHashMap<>();
            checkIns.put("me", meCheckIns);
            checkIns.put("peer", peerCheckIns);
            stats.put("chats", chats);
            stats.put("checkIns", checkIns);
            stats.put("onlineMinutes", onlineMinutes);
        } catch (Exception ex) {
            log.error("周对比统计失败 pair={}", pairCode, ex);
            stats.put("chats", Map.of("me", 0L, "peer", 0L));
            stats.put("checkIns", Map.of("me", 0L, "peer", 0L));
            stats.put("onlineMinutes", 0L);
        }
        return stats;
    }

    // ==================== 推送策略 ====================

    /** 升级必推；非升级同 pair 60 秒内不重复推（本方法仅在计分成功后调用，状态必已变化） */
    private void pushIfNeeded(String pairCode, LevelInfo info, boolean leveledUp) {
        try {
            if (leveledUp) {
                pushSnapshot(pairCode, info);
                return;
            }
            Long last = lastPushAt.get(pairCode);
            if (last == null || System.currentTimeMillis() - last >= PUSH_THROTTLE_MS) {
                pushSnapshot(pairCode, info);
            }
        } catch (Exception ex) {
            log.error("affection_sync 推送失败 pair={}", pairCode, ex);
        }
    }

    /** 推给 pair 双方（broadcastToPair 内部即现有 sendMessage 通道，utf-8 已就位） */
    private void pushSnapshot(String pairCode, LevelInfo info) {
        pairService.broadcastToPair(pairCode,
                WsMessage.createAffectionSync(pairCode, info.points, info.level, info.progress, info.title));
        lastPushAt.put(pairCode, System.currentTimeMillis());
    }

    // ==================== 纯规则（单测直达） ====================

    /**
     * 首字自动打卡判定（纯逻辑）：
     * 文本以「早」开头且在早窗口 5:00-11:00 → "morning"；以「晚」开头且在晚窗口 19:00-24:00 → "evening"；
     * 长度 &gt;20 字或首字不匹配 → null。「今晚/今早/明早」以他字开头天然不算，无需词表。
     */
    public static String detectAutoCheckInWindow(String text, int hour) {
        if (text == null || text.isEmpty()) return null;
        if (text.codePointCount(0, text.length()) > AUTO_CHECK_IN_MAX_LEN) return null;
        int cp = text.codePointAt(0);
        if (cp == '早' && hour >= MORNING_START_HOUR && hour < MORNING_END_HOUR) return "morning";
        if (cp == '晚' && hour >= EVENING_START_HOUR && hour < EVENING_END_HOUR) return "evening";
        return null;
    }

    /** 打卡幂等键：pair + 本地日期 + 时段 + user（两人可各自打卡，积分都进共享池） */
    public static String checkInDedupKey(String pairCode, String window, long userId) {
        return "checkin:" + pairCode + ":" + LocalDate.now(ZoneId.systemDefault()) + ":" + window + ":" + userId;
    }

    /** check_in dedupKey 末尾为用户 id（checkin:{pair}:{date}:{window}:{userId}） */
    private static long parseUserFromCheckInKey(String dedupKey) {
        if (dedupKey == null) return 0;
        int i = dedupKey.lastIndexOf(':');
        if (i < 0 || i == dedupKey.length() - 1) return 0;
        try {
            return Long.parseLong(dedupKey.substring(i + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 本地日期 [start, end) 毫秒区间（与 clock 时区一致） */
    private long[] dayRange(LocalDate d) {
        ZoneId z = clock.getZone();
        long start = d.atStartOfDay(z).toInstant().toEpochMilli();
        long end = d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();
        return new long[]{start, end};
    }
}
