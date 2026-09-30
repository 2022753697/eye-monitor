package com.eyemonitor.service;

import org.junit.jupiter.api.Test;

import static com.eyemonitor.service.LocationThrottle.Reason.ACCEPT;
import static com.eyemonitor.service.LocationThrottle.Reason.ACCURACY;
import static com.eyemonitor.service.LocationThrottle.Reason.DAILY_CAP;
import static com.eyemonitor.service.LocationThrottle.Reason.DEDUPE;
import static com.eyemonitor.service.LocationThrottle.Reason.RATE_LIMITED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 位置落库限流状态机（R5/R6/R7/R8）单元测试。
 * 覆盖：精度过滤、静止去重、最小时距、跨天重置、每日上限。
 */
class LocationThrottleTest {

    private static final long DAY_MS = 86_400_000L;
    private static final double LAT = 30.0;
    private static final double LNG = 120.0;
    private static final float GOOD_ACC = 10f;

    private final LocationThrottle throttle = new LocationThrottle();

    @Test
    void firstPointAlwaysAccepted() {
        assertEquals(ACCEPT, throttle.decideAndRecord("P1", 1_000L, LAT, LNG, GOOD_ACC));
    }

    @Test
    void accuracyAboveMaxRejected() {
        assertEquals(ACCURACY, throttle.decideAndRecord("P1", 1_000L, LAT, LNG, 90f));
    }

    @Test
    void staticPointDeduped() {
        throttle.decideAndRecord("P1", 1_000L, LAT, LNG, GOOD_ACC);
        // 同坐标 100s 后：位移 0 < 20m 且 < 5min → 静止去重
        assertEquals(DEDUPE, throttle.decideAndRecord("P1", 100_000L, LAT, LNG, GOOD_ACC));
    }

    @Test
    void movedButWithinMinIntervalRateLimited() {
        throttle.decideAndRecord("P1", 1_000L, LAT, LNG, GOOD_ACC);
        // ~1.1km 位移但仅 30s 后：R6 最小时距 60s 拦截（服务端无大位移例外，那是客户端 R2）
        assertEquals(RATE_LIMITED, throttle.decideAndRecord("P1", 31_000L, LAT + 0.01, LNG, GOOD_ACC));
    }

    @Test
    void movedAfterMinIntervalAccepted() {
        throttle.decideAndRecord("P1", 1_000L, LAT, LNG, GOOD_ACC);
        assertEquals(ACCEPT, throttle.decideAndRecord("P1", 61_000L, LAT + 0.01, LNG, GOOD_ACC));
    }

    @Test
    void staticAfterWindowAccepted() {
        // 静止但间隔超 5 分钟去重窗口 → 允许落库（长时间停留也留痕）
        throttle.decideAndRecord("P1", 1_000L, LAT, LNG, GOOD_ACC);
        assertEquals(ACCEPT, throttle.decideAndRecord("P1", 5 * 60_000L + 61_000L, LAT, LNG, GOOD_ACC));
    }

    @Test
    void dailyCapBlocksThenResets() {
        // prod 上限 1500 单日内不可达（60s 间隔最多 1441 点/天≈24h），
        // 注入小上限（=3）验证「满额拦截 + 跨天重置」语义。
        LocationThrottle small = new LocationThrottle(3);
        String pair = "P1";
        long t = 1_000L;
        assertEquals(ACCEPT, small.decideAndRecord(pair, t, LAT, LNG, GOOD_ACC));
        t += 61_000L;
        assertEquals(ACCEPT, small.decideAndRecord(pair, t, LAT + 0.01, LNG, GOOD_ACC));
        t += 61_000L;
        assertEquals(ACCEPT, small.decideAndRecord(pair, t, LAT + 0.02, LNG, GOOD_ACC));
        // 第 4 点（同日）→ 每日上限
        t += 61_000L;
        assertEquals(DAILY_CAP, small.decideAndRecord(pair, t, LAT + 0.03, LNG, GOOD_ACC));
        // 跨天 → 计数重置，可再次落库
        assertEquals(ACCEPT, small.decideAndRecord(pair, t + DAY_MS, LAT + 0.04, LNG, GOOD_ACC));
    }

    @Test
    void defaultDailyCapIs1500() {
        assertEquals(1500, LocationThrottle.DAILY_CAP);
    }
}