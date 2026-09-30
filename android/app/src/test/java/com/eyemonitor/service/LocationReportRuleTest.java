package com.eyemonitor.service;

import org.junit.Test;

import static com.eyemonitor.service.LocationReportRule.Decision.SEND;
import static com.eyemonitor.service.LocationReportRule.Decision.SKIP_RATE_LIMITED;
import static com.eyemonitor.service.LocationReportRule.Decision.SKIP_STATIC;
import static org.junit.Assert.assertEquals;

/**
 * 位置上报门控（R1 静止去重 + R2 最小间隔/大位移即时）单元测试。
 */
public class LocationReportRuleTest {

    private static final long T0 = 1_000_000L;
    private static final double LAT = 39.9;
    private static final double LNG = 116.4;

    @Test
    public void firstReportAlwaysSends() {
        assertEquals(SEND, LocationReportRule.shouldReport(0, 0, 0, LAT, LNG, T0));
    }

    @Test
    public void staticPositionSkipped() {
        // 同坐标：位移 0 < 20m → 静止去重
        assertEquals(SKIP_STATIC,
                LocationReportRule.shouldReport(T0, LAT, LNG, LAT, LNG, T0 + 10_000));
    }

    @Test
    public void tinyMoveSkipped() {
        // 0.0001° ≈ 11m < 20m → 仍判静止
        assertEquals(SKIP_STATIC,
                LocationReportRule.shouldReport(T0, LAT, LNG, LAT + 0.0001, LNG, T0 + 10_000));
    }

    @Test
    public void smallMoveWithinIntervalRateLimited() {
        // 0.001° ≈ 111m（>20m 非静止，<200m 非大位移）+ 30s → 限频
        assertEquals(SKIP_RATE_LIMITED,
                LocationReportRule.shouldReport(T0, LAT, LNG, LAT + 0.001, LNG, T0 + 30_000));
    }

    @Test
    public void bigMoveImmediateSend() {
        // 0.01° ≈ 1.1km > 200m → 30s 内也立即上报（行车/高铁保轨迹）
        assertEquals(SEND,
                LocationReportRule.shouldReport(T0, LAT, LNG, LAT + 0.01, LNG, T0 + 30_000));
    }

    @Test
    public void smallMoveAfterIntervalSends() {
        // 111m + 61s → 超过 60s 最小间隔 → 上报
        assertEquals(SEND,
                LocationReportRule.shouldReport(T0, LAT, LNG, LAT + 0.001, LNG, T0 + 61_000));
    }

    @Test
    public void outsideFenceRangeNormal() {
        // 0.002° ≈ 222m：刚超过大位移阈值，任何间隔都应上报
        assertEquals(SEND,
                LocationReportRule.shouldReport(T0, LAT, LNG, LAT + 0.002, LNG, T0 + 5_000));
    }
}