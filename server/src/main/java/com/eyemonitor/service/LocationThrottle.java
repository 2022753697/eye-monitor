package com.eyemonitor.service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 位置落库限流状态机（R5 静止去重 + R6 最小时距 + R7 精度过滤 + R8 每日上限），
 * 纯逻辑、无 Spring 依赖，可单测。
 * <p>
 * 原逻辑内联在 {@code MessageStore.saveLocation()}，P0 抽提为独立类，
 * 常量与判定顺序与重构前完全一致（内存态，按 pairCode 记录 [lastTs, lastLat, lastLng, day, count]）。
 */
public final class LocationThrottle {

    /** R5 静止去重：与最近已存点位移 &lt; 20m 视为静止 */
    public static final double DEDUPE_DIST_M = 20.0;

    /** R5 去重窗口：静止且距上次落库 &lt; 5min 不落库 */
    public static final long DEDUPE_WINDOW_MS = 5 * 60_000L;

    /** R6 最小时距：同 pair 两次落库至少间隔 60s（无论位移多大） */
    public static final long MIN_INTERVAL_MS = 60_000L;

    /** R7 精度过滤：accuracy &gt; 80m 的点不可靠，不落库 */
    public static final float ACCURACY_MAX_M = 80.0f;

    /** R8 每日上限：单 pair 每日最多落库条数 */
    public static final int DAILY_CAP = 1500;

    private static final long DAY_MS = 86_400_000L;

    /** 判定结果（仅 ACCEPT 会推进内部状态） */
    public enum Reason { ACCEPT, ACCURACY, DEDUPE, RATE_LIMITED, DAILY_CAP }

    /** 按 pairCode 的状态：[lastTs, lastLat, lastLng, day, count] */
    private final ConcurrentHashMap<String, Object[]> state = new ConcurrentHashMap<>();

    /** 每日上限（prod 默认 1500；测试可注入小值验证语义） */
    private final int dailyCap;

    public LocationThrottle() {
        this(DAILY_CAP);
    }

    LocationThrottle(int dailyCap) {
        this.dailyCap = dailyCap;
    }

    /**
     * 判定是否落库并（仅当 ACCEPT）记录状态。
     *
     * @param pairCode 配对码（key）
     * @param now      时间戳（毫秒）
     * @param lat lng 待落库坐标
     * @param accuracy 定位精度
     */
    public Reason decideAndRecord(String pairCode, long now,
                                  double lat, double lng, float accuracy) {
        if (accuracy > ACCURACY_MAX_M) {
            return Reason.ACCURACY;
        }
        Object[] st = state.computeIfAbsent(pairCode, k -> new Object[]{0L, 0d, 0d, -1, 0L});
        synchronized (st) {
            long lastTs = (Long) st[0];
            double lastLat = (Double) st[1];
            double lastLng = (Double) st[2];
            int day = (int) (now / DAY_MS);
            int lastDay = (Integer) st[3];
            long count = (Long) st[4];
            if (lastDay != day) {
                lastDay = day;
                count = 0;
            }
            if (count >= dailyCap) {
                return Reason.DAILY_CAP;
            }
            if (lastTs > 0) {
                double dist = haversine(lastLat, lastLng, lat, lng);
                long since = now - lastTs;
                // R5 静止去重：<20m 且 <5min
                if (dist < DEDUPE_DIST_M && since < DEDUPE_WINDOW_MS) {
                    return Reason.DEDUPE;
                }
                // R6 最小时距：<60s 一律不落库（大位移即时上报是客户端 R2 的职责）
                if (since < MIN_INTERVAL_MS) {
                    return Reason.RATE_LIMITED;
                }
            }
            st[0] = now;
            st[1] = lat;
            st[2] = lng;
            st[3] = lastDay;
            st[4] = count + 1;
            return Reason.ACCEPT;
        }
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}