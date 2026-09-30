package com.eyemonitor.service;

import com.eyemonitor.service.FenceEvaluator.Fence;
import com.eyemonitor.service.FenceEvaluator.Tracker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 围栏判定纯逻辑单元测试（点-圆判定 / 精度忽略 / 状态翻转 / 判重防抖 / 配置同步保状态）。
 */
public class FenceEvaluatorTest {

    private static final double CENTER_LAT = 30.0;
    private static final double CENTER_LNG = 120.0;

    private final List<Boolean> events = new ArrayList<>();

    private final Tracker tracker = new Tracker((fence, nowInside, lat, lng) -> events.add(nowInside));

    private void setOneFence(double radiusM) {
        tracker.setFences(Collections.singletonList(
                new Fence(1L, "家", CENTER_LAT, CENTER_LNG, radiusM)));
    }

    // --- 静态纯函数 ---

    @Test
    public void insideDetermination() {
        assertTrue(FenceEvaluator.isInside(CENTER_LAT, CENTER_LNG, CENTER_LAT, CENTER_LNG, 500));
        // ~111m 偏移在 500m 半径内
        assertTrue(FenceEvaluator.isInside(CENTER_LAT, CENTER_LNG, CENTER_LAT + 0.001, CENTER_LNG, 500));
        // ~1.1km 偏移在 500m 半径外
        assertFalse(FenceEvaluator.isInside(CENTER_LAT, CENTER_LNG, CENTER_LAT + 0.01, CENTER_LNG, 500));
        // 半径边界：恰好 500m 内（<=）算圈内
        assertTrue(FenceEvaluator.isInside(CENTER_LAT, CENTER_LNG, CENTER_LAT, CENTER_LNG, 0));
    }

    @Test
    public void accuracyIgnore() {
        assertTrue(FenceEvaluator.shouldIgnore(260f, 500)); // > 半径/2 不可信
        assertFalse(FenceEvaluator.shouldIgnore(10f, 500));
    }

    // --- 有状态 Tracker ---

    @Test
    public void firstPointOnlyRecordsNoEvent() {
        setOneFence(500);
        tracker.evaluate(CENTER_LAT, CENTER_LNG, 5f); // 首点圈内：只记录，不触发（防 App 启动误报）
        assertEquals(0, events.size());
    }

    @Test
    public void enterExitBothFire() {
        setOneFence(500);
        tracker.evaluate(CENTER_LAT, CENTER_LNG, 5f);      // 首点 inside（记录）
        assertEquals(1, tracker.evaluate(CENTER_LAT + 0.01, CENTER_LNG, 5f)); // 出圈 → exit
        assertEquals(false, events.get(0));
        assertEquals(1, tracker.evaluate(CENTER_LAT, CENTER_LNG, 5f));        // 回圈 → enter
        assertEquals(Boolean.TRUE, events.get(1));
    }

    @Test
    public void sameDirectionNoDuplicate() {
        setOneFence(500);
        tracker.evaluate(CENTER_LAT, CENTER_LNG, 5f);      // inside 记录
        assertEquals(1, tracker.evaluate(CENTER_LAT + 0.01, CENTER_LNG, 5f)); // exit
        // 仍在圈外（更远一点）：不重复触发（同方向防抖）
        assertEquals(0, tracker.evaluate(CENTER_LAT + 0.02, CENTER_LNG, 5f));
    }

    @Test
    public void badAccuracyPointIgnored() {
        setOneFence(500);
        tracker.evaluate(CENTER_LAT, CENTER_LNG, 5f);      // inside 记录
        // 精度 300m > 半径/2：该点跳过判定，不产生翻转
        assertEquals(0, tracker.evaluate(CENTER_LAT + 0.01, CENTER_LNG, 300f));
        assertEquals(0, events.size());
    }

    @Test
    public void configSyncKeepsState() {
        setOneFence(500);
        tracker.evaluate(CENTER_LAT, CENTER_LNG, 5f);      // inside 记录
        setOneFence(500);                                  // 配置重推（同 serverId）
        assertEquals(1, tracker.evaluate(CENTER_LAT + 0.01, CENTER_LNG, 5f)); // 状态保留，翻转仍回调
    }

    @Test
    public void removedFenceStopsTracking() {
        setOneFence(500);
        tracker.evaluate(CENTER_LAT, CENTER_LNG, 5f);
        tracker.setFences(Collections.emptyList());        // 围栏删除
        assertEquals(0, tracker.evaluate(CENTER_LAT + 0.01, CENTER_LNG, 5f)); // 不再评估
    }
}