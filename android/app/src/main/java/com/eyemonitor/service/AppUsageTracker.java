package com.eyemonitor.service;

import android.app.usage.UsageEvents;
import android.app.AppOpsManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * App 使用情况轮询器（省电优化 P1）。
 * <p>
 * 档位策略（{@link #effectiveIntervalMs(boolean, boolean)}，纯函数可单测）：
 * - 息屏：不排程（零轮询，亮屏立即恢复）
 * - 亮屏 + 无障碍未启用：5s（原 2s，切 App 最迟 5s 看到）
 * - 亮屏 + 无障碍已启用：60s 兜底（无障碍事件为主实时源）
 * <p>
 * 需要权限：android.permission.PACKAGE_USAGE_STATS
 */
public class AppUsageTracker {

    private static final String TAG = "AppUsageTracker";
    /** 亮屏无无障碍：5s */
    static final long POLL_INTERVAL_SCREEN_ON_MS = 5_000L;
    /** 亮屏 + 无障碍已启用：60s 兜底 */
    static final long POLL_INTERVAL_ACCESSIBILITY_MS = 60_000L;
    // 查询窗口需足够大（UsageStats 按天粒度聚合，短窗口常返回空数据）
    private static final long QUERY_WINDOW_MS = 24 * 60 * 60 * 1000L;

    public interface OnAppSwitchListener {
        void onAppSwitched(String packageName, String appName);
    }

    /**
     * 当前有效轮询间隔（纯逻辑，供单测）：
     * 息屏 → -1（不轮询）；亮屏+无障碍 → 60s；亮屏无无障碍 → 5s。
     */
    static long effectiveIntervalMs(boolean screenOn, boolean accessibilityEnabled) {
        if (!screenOn) return -1L;
        return accessibilityEnabled ? POLL_INTERVAL_ACCESSIBILITY_MS : POLL_INTERVAL_SCREEN_ON_MS;
    }

    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final PackageManager packageManager;
    private final OnAppSwitchListener listener;

    private ScheduledExecutorService scheduler;
    private String lastPackageName;
    private boolean running;
    private boolean permissionGranted = true;

    private volatile boolean screenOn = true;
    private volatile boolean accessibilityEnabled = false;

    public AppUsageTracker(Context context, OnAppSwitchListener listener) {
        this.context = context.getApplicationContext();
        this.usageStatsManager = (UsageStatsManager) this.context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.packageManager = this.context.getPackageManager();
        this.listener = listener;
    }

    /** 开始轮询 */
    public void start() {
        if (running) return;

        // 检查是否有权限
        if (!hasUsageStatsPermission(context)) {
            Log.e(TAG, "缺少 PACKAGE_USAGE_STATS 权限，尝试使用 AccessibilityService");
            permissionGranted = false;
            return;
        }

        running = true;
        lastPackageName = getCurrentForegroundPackage();
        Log.d(TAG, "开始轮询，当前前台App: " + lastPackageName);
        restartScheduler();
    }

    /** 停止轮询 */
    public void stop() {
        running = false;
        shutdownScheduler();
        Log.d(TAG, "停止轮询");
    }

    /** 是否正在运行 */
    public boolean isRunning() {
        return running;
    }

    /** 是否因权限问题无法运行 */
    public boolean hasPermissionIssue() {
        return !permissionGranted;
    }

    /** 屏态变化：息屏 → 停排程（零轮询）；亮屏 → 按当前档位立即恢复 */
    public void setScreenOn(boolean on) {
        if (screenOn == on) return;
        screenOn = on;
        Log.i(TAG, "屏态: " + (on ? "亮屏" : "息屏") + "，轮询档=" + effectiveIntervalMs(screenOn, accessibilityEnabled));
        restartScheduler();
    }

    /** 无障碍开关变化：开启 → 60s 兜底档；关闭 → 5s 档 */
    public void setAccessibilityEnabled(boolean enabled) {
        if (accessibilityEnabled == enabled) return;
        accessibilityEnabled = enabled;
        Log.i(TAG, "无障碍: " + (enabled ? "已启用(60s兜底)" : "未启用(5s)")
                + "，轮询档=" + effectiveIntervalMs(screenOn, accessibilityEnabled));
        restartScheduler();
    }

    /** 按当前档位重建排程：息屏/未运行 → 停排；运行中 → 立即按新间隔排 */
    private void restartScheduler() {
        shutdownScheduler();
        if (!running) return;
        long interval = effectiveIntervalMs(screenOn, accessibilityEnabled);
        if (interval <= 0) {
            Log.d(TAG, "息屏中：轮询暂停（零轮询）");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "app-tracker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 0, interval, TimeUnit.MILLISECONDS);
        Log.d(TAG, "轮询排程: interval=" + interval + "ms");
    }

    private void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** 轮询任务：查询当前前台 App，与上次比较 */
    private void poll() {
        if (!running) return;
        Log.d(TAG, "POLL tick"); // ASCII 计数标记（P4 基线对比用，避免中文编码问题）

        String currentPkg = getCurrentForegroundPackage();
        if (currentPkg == null) return;

        if (currentPkg.equals(lastPackageName)) return;

        lastPackageName = currentPkg;
        String appName = getAppName(currentPkg);
        Log.d(TAG, "App切换: " + appName + " (" + currentPkg + ")");

        if (listener != null) {
            listener.onAppSwitched(currentPkg, appName);
        }
    }

    private String getCurrentForegroundPackage() {
        try {
            long now = System.currentTimeMillis();
            // 事件流 API 比聚合查询更可靠（聚合查询在部分 ROM/模拟器上返回空）
            UsageEvents events = usageStatsManager.queryEvents(now - QUERY_WINDOW_MS, now);
            if (events == null) return null;
            String lastPkg = null;
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                int type = e.getEventType();
                // API 29+ 用 ACTIVITY_RESUMED，低版本用 MOVE_TO_FOREGROUND
                if (type == UsageEvents.Event.ACTIVITY_RESUMED
                        || type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastPkg = e.getPackageName();
                }
            }
            return lastPkg;
        } catch (SecurityException e) {
            Log.e(TAG, "缺少 PACKAGE_USAGE_STATS 权限", e);
            permissionGranted = false;
            return null;
        } catch (Exception e) {
            Log.e(TAG, "查询前台App异常", e);
            return null;
        }
    }

    private String getAppName(String packageName) {
        return com.eyemonitor.util.AppNameResolver.getAppName(context, packageName);
    }

    /**
     * 检查是否有 PACKAGE_USAGE_STATS 权限（真实 AppOps 判定，避免 ROM 假阳性）
     */
    public static boolean hasUsageStatsPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return true;
        }
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
            } else {
                mode = appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.e(TAG, "检查 UsageStats 权限异常", e);
            return false;
        }
    }
}