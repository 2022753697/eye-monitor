package com.eyemonitor.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * GeoMath 地理计算纯工具单元测试。
 * 覆盖：Haversine 已知距离对、跳点判定（R4'）。
 */
public class GeoMathTest {

    @Test
    public void samePointZero() {
        assertEquals(0.0, GeoMath.distanceMeters(39.9, 116.4, 39.9, 116.4), 1e-6);
    }

    @Test
    public void knownDistanceAtLat40() {
        // 40°N 处 0.1° 经度差 ≈ 111.32km × cos(40°) × 0.1 ≈ 8.5km
        double d = GeoMath.distanceMeters(40.0, 116.0, 40.0, 116.1);
        assertEquals(8520, d, 500);
    }

    @Test
    public void latitudeDegreeApprox111km() {
        // 1° 纬度差 ≈ 111.19km
        double d = GeoMath.distanceMeters(30.0, 120.0, 31.0, 120.0);
        assertEquals(111000, d, 3000);
    }

    @Test
    public void jumpFilter() {
        // 5000km / 30s → 物理不可能（约 60 万 km/h）
        assertTrue(GeoMath.isImpossibleJump(5_000_000, 30_000));
        // 5km / 30s ≈ 600km/h → 高铁/飞机正常，保留
        assertFalse(GeoMath.isImpossibleJump(5_000, 30_000));
        // dt=0 无法判定，不拦截
        assertFalse(GeoMath.isImpossibleJump(10_000, 0));
    }
}