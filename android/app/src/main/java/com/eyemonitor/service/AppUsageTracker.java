package com.eyemonitor.service;

import android.app.usage.UsageEvents;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * App 使用情况轮询器。
 * <p>
 * 使用 UsageStatsManager 每 2 秒查询一次前台 App，
 * 发现变化时通过回调通知调用方。
 * <p>
 * 需要权限：android.permission.PACKAGE_USAGE_STATS
 */
public class AppUsageTracker {

    private static final String TAG = "AppUsageTracker";
    private static final long POLL_INTERVAL_MS = 2000;
    // 查询窗口需足够大（UsageStats 按天粒度聚合，短窗口常返回空数据）
    private static final long QUERY_WINDOW_MS = 24 * 60 * 60 * 1000L;

    public interface OnAppSwitchListener {
        void onAppSwitched(String packageName, String appName);
    }

    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final PackageManager packageManager;
    private final OnAppSwitchListener listener;
    private final ScheduledExecutorService scheduler;

    private String lastPackageName;
    private boolean running;
    private boolean permissionGranted = true;

    public AppUsageTracker(Context context, OnAppSwitchListener listener) {
        this.context = context.getApplicationContext();
        this.usageStatsManager = (UsageStatsManager) this.context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.packageManager = this.context.getPackageManager();
        this.listener = listener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "app-tracker");
            t.setDaemon(true);
            return t;
        });
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
        scheduler.scheduleWithFixedDelay(
                this::poll,
                0,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    /** 停止轮询 */
    public void stop() {
        running = false;
        scheduler.shutdown();
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

    /** 轮询任务：查询当前前台 App，与上次比较 */
    private void poll() {
        if (!running) return;

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
            Log.d(TAG, "queryEvents 返回: " + (events != null ? "非null" : "null"));
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
