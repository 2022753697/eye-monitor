package com.eyemonitor.service;

import com.eyemonitor.util.GeoMath;

/**
 * 位置上报门控纯逻辑（R1 静止去重 + R2 最小间隔/大位移即时），无 Android 依赖，可单测。
 * <p>
 * 原逻辑内联在 {@code MonitorService.reportLocation()}，P0 抽提为纯函数，
 * 行为与重构前完全一致（常量原样迁移）。
 */
public final class LocationReportRule {

    /** R2 最小上报间隔：有位移时两次实际上报至少间隔 60s */
    public static final long MIN_REPORT_INTERVAL_MS = 60_000L;

    /** R1 静止去重：位移 &lt; 20m 视为静止，跳过上报 */
    public static final double SEND_DISTANCE_M = 20.0;

    /** R2 大位移即时：位移 &gt; 200m 时不受最小间隔限制，立即上报（行车/高铁保轨迹） */
    public static final double IMMEDIATE_DISTANCE_M = 200.0;

    public enum Decision { SKIP_STATIC, SKIP_RATE_LIMITED, SEND }

    private LocationReportRule() {}

    /**
     * 判定本轮是否上报。
     *
     * @param lastReportTs 上次实际上报时间戳；0 表示从未上报（首次必发）
     * @param lastReportLat / lastReportLng 上次上报位置
     * @param lat / lng 当前待上报位置
     * @param now 当前时间戳
     */
    public static Decision shouldReport(long lastReportTs,
                                        double lastReportLat, double lastReportLng,
                                        double lat, double lng, long now) {
        if (lastReportTs <= 0) {
            return Decision.SEND;
        }
        double dist = GeoMath.distanceMeters(lastReportLat, lastReportLng, lat, lng);
        // R1 静止去重：位移 < 20m 不上报
        if (dist < SEND_DISTANCE_M) {
            return Decision.SKIP_STATIC;
        }
        // R2 最小间隔：< 60s 且无大位移(>200m) 不上报（大位移=行车/高铁，立即上报保轨迹）
        if (now - lastReportTs < MIN_REPORT_INTERVAL_MS && dist < IMMEDIATE_DISTANCE_M) {
            return Decision.SKIP_RATE_LIMITED;
        }
        return Decision.SEND;
    }
}