package com.eyemonitor.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 围栏判定纯逻辑（无 Android 依赖，可单测）。
 * <p>
 * 点-圆判定采用 haversine 大圆距离：距离 <= 半径视为圈内。
 * accuracy 过大（> 半径/2）的位置点认为不可信，应忽略（不进判定）。
 * 状态翻转（in-&gt;out / out-&gt;in）事件回调；同一围栏同方向 60s 内不重复回调（判重防抖）。
 */
public final class FenceEvaluator {

    private static final double EARTH_RADIUS_M = 6371000.0;

    /** 同一围栏同一翻转方向的通知去重窗口（毫秒） */
    public static final long NOTIFY_DEDUP_MS = 60_000L;

    private FenceEvaluator() {}

    /** 两点大圆距离（米） */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 位置点是否在围栏内（dist <= radius 为圈内） */
    public static boolean isInside(double lat, double lng,
                                   double centerLat, double centerLng, double radiusM) {
        return distanceMeters(lat, lng, centerLat, centerLng) <= radiusM;
    }

    /** 精度是否过差：accuracy > radius/2 时位置点不可信，判定应忽略该点 */
    public static boolean shouldIgnore(float accuracy, double radiusM) {
        return accuracy > radiusM / 2.0;
    }

    /** 围栏快照：判定只依赖这些不变量，与数据库/网络实体解耦 */
    public static final class Fence {
        public final long serverId;
        public final String name;
        public final double centerLat;
        public final double centerLng;
        public final double radiusM;

        public Fence(long serverId, String name, double centerLat, double centerLng, double radiusM) {
            this.serverId = serverId;
            this.name = name;
            this.centerLat = centerLat;
            this.centerLng = centerLng;
            this.radiusM = radiusM;
        }
    }

    /** 翻转事件回调（nowInside=true 进入围栏，false 离开围栏），在调用线程回调 */
    public interface OnFenceCrossed {
        void onFenceCrossed(Fence fence, boolean nowInside, double lat, double lng);
    }

    /**
     * 有状态评估器：维护每个围栏的进出状态与最近一次通知时间。
     * <p>
     * 非线程安全：需由单一线程串行调用（生产环境走 Room 的 dbExecutor 单线程池）。
     * 配置通过 {@link #setFences} 按 serverId 合并/更新/删除，已有进出状态保留。
     */
    public static final class Tracker {

        private final OnFenceCrossed listener;
        private final Map<Long, Entry> entries = new HashMap<>();

        public Tracker(OnFenceCrossed listener) {
            this.listener = listener;
        }

        private static final class Entry {
            Fence fence;
            Boolean inside;           // null = 初始未知，首个位置点只记录不触发
            long lastEnterNotifyTs;
            long lastExitNotifyTs;
        }

        /** 同步围栏配置：新增/更新/删除均按 serverId 对齐，保留已跟踪的进出状态 */
        public void setFences(List<Fence> fences) {
            Map<Long, Fence> incoming = new HashMap<>();
            if (fences != null) {
                for (Fence f : fences) {
                    incoming.put(f.serverId, f);
                }
            }
            entries.keySet().retainAll(incoming.keySet());
            for (Map.Entry<Long, Fence> e : incoming.entrySet()) {
                Entry entry = entries.get(e.getKey());
                if (entry == null) {
                    entry = new Entry();
                    entries.put(e.getKey(), entry);
                }
                entry.fence = e.getValue();
            }
        }

        /** 是否有正在跟踪的围栏 */
        public boolean hasFences() {
            return !entries.isEmpty();
        }

        /**
         * 评估一个位置点：对每个围栏按自身半径做精度过滤，
         * 状态翻转且同方向未超去重窗口时回调 listener。返回触发回调的次数。
         */
        public int evaluate(double lat, double lng, float accuracy) {
            int crosses = 0;
            for (Entry entry : entries.values()) {
                Fence fence = entry.fence;
                if (fence.radiusM <= 0 || shouldIgnore(accuracy, fence.radiusM)) {
                    continue;
                }
                boolean inside = isInside(lat, lng, fence.centerLat, fence.centerLng, fence.radiusM);
                if (entry.inside == null) {
                    // 初始状态只记录，不触发（避免 App 启动瞬间误报）
                    entry.inside = inside;
                    continue;
                }
                if (inside == entry.inside) {
                    continue;
                }
                entry.inside = inside;
                long now = System.currentTimeMillis();
                long lastNotifyTs = inside ? entry.lastEnterNotifyTs : entry.lastExitNotifyTs;
                if (now - lastNotifyTs >= NOTIFY_DEDUP_MS) {
                    if (inside) {
                        entry.lastEnterNotifyTs = now;
                    } else {
                        entry.lastExitNotifyTs = now;
                    }
                    crosses++;
                    if (listener != null) {
                        listener.onFenceCrossed(fence, inside, lat, lng);
                    }
                }
            }
            return crosses;
        }
    }
}