package com.eyemonitor.db;

/**
 * 亲密度/等级内存镜像（进程内单例）。
 * <p>
 * Room 是权威缓存，但升级检测 / 主题解锁判定 / MapActivity 爪印 pin 都需要
 * 同步读等级（主线程禁 Room 查询），故在每次 affection_sync 与 GET /affection
 * 落库时同步刷新本镜像（volatile 字段，读写无锁）。
 */
public final class AffectionStateHolder {

    private static volatile int level;
    private static volatile int points;
    private static volatile double progress;
    private static volatile String title = "";
    private static volatile long updatedAt;

    private AffectionStateHolder() {}

    public static void update(AffectionCacheEntity e) {
        if (e == null) return;
        level = e.level;
        points = e.points;
        progress = e.progress;
        title = e.title != null ? e.title : "";
        updatedAt = e.updatedAt;
    }

    public static int getLevel() {
        return level;
    }

    public static int getPoints() {
        return points;
    }

    public static double getProgress() {
        return progress;
    }

    public static String getTitle() {
        return title;
    }

    public static long getUpdatedAt() {
        return updatedAt;
    }
}
