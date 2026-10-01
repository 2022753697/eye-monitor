package com.eyemonitor.util;

/**
 * 地理计算纯工具（无 Android 依赖，可单测）。
 * <p>
 * 由 {@code LocationTracker} / {@code LocationReportRule} / 围栏判定共用，
 * 常量与判定规则集中在 P0 抽提，保证行为与重构前完全一致。
 */
public final class GeoMath {

    private static final double EARTH_RADIUS_M = 6371000.0;

    /** R3 精度过滤：已有位置后 accuracy &gt; 80m 的点不采纳（防漂移污染轨迹） */
    public static final float ACCURACY_FILTER_M = 80f;

    /** R4' 跳点过滤：推算速度 &gt; 2000km/h（≈555.6 m/s），远高于飞机 900km/h，视为 GPS 抽风 */
    public static final float MAX_SPEED_MPS = 555.6f;

    /** 有效坐标判定：拒绝越界值和 (0,0)（几内亚湾，绝非真实定位；定位未就绪时的垃圾值） */
    public static boolean isValidLatLng(double lat, double lng) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return false;
        }
        return Math.abs(lat) > 0.0001 || Math.abs(lng) > 0.0001;
    }

    private GeoMath() {}

    /** 两点大圆距离（米） */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }

    /**
     * 跳点判定（R4'）：dt 毫秒内位移 dist 米，推算速度是否物理不可能（&gt; MAX_SPEED_MPS）。
     * dt &lt;= 0 时返回 false（无法判定不拦截）。
     */
    public static boolean isImpossibleJump(double distM, long dtMs) {
        return dtMs > 0 && distM / (dtMs / 1000.0) > MAX_SPEED_MPS;
    }
}