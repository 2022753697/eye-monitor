package com.eyemonitor.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.AppNameCacheEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用名解析器：把包名解析为用户可读的应用名（如 com.android.chrome → 谷歌浏览器）。
 * <p>
 * 包名→应用名映射已从写死代码迁移到数据库（服务器 eye_app_names 表 + 本地 Room 缓存），
 * 由 SyncManager 拉取 /api/app-names 后经 updateFromCache 刷新内存表，App 启动时 warmUp 预热。
 * 解析顺序：
 * 1. 系统 ApplicationInfo 的 label（用户安装时看到的名称）
 * 2. DB 同步的应用名映射
 * 3. 原样返回包名
 */
public class AppNameResolver {

    private static final String TAG = "AppNameResolver";

    /** DB 驱动的包名→应用名内存表（volatile 引用整体替换，读侧无锁） */
    private static volatile Map<String, String> DB_NAMES = new HashMap<>();

    private AppNameResolver() {}

    /** 刷新内存映射表（由 SyncManager 在拉取 /api/app-names 后调用） */
    public static void updateFromCache(Map<String, String> map) {
        if (map != null) {
            DB_NAMES = map;
        }
    }

    /** 启动预热：从 Room 缓存加载映射（网络同步前的离线兜底，异步不阻塞） */
    public static void warmUp(Context context) {
        AppDatabase db = AppDatabase.getInstance(context);
        AppDatabase.dbExecutor.execute(() -> {
            try {
                List<AppNameCacheEntity> all = db.cacheDao().getAppNames();
                Map<String, String> map = new HashMap<>();
                for (AppNameCacheEntity e : all) {
                    if (e.packageName != null && e.appName != null) {
                        map.put(e.packageName, e.appName);
                    }
                }
                if (!map.isEmpty()) {
                    DB_NAMES = map;
                    Log.d(TAG, "应用名映射预热完成: " + map.size() + " 条");
                }
            } catch (Exception e) {
                Log.w(TAG, "应用名映射预热失败", e);
            }
        });
    }

    /**
     * 解析应用名：系统 label 优先，DB 映射兜底，最后回退包名。
     */
    public static String getAppName(Context context, String packageName) {
        if (packageName == null) return "未知应用";

        // 1. 系统 label（用户看到的应用名）
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            CharSequence label = ai.loadLabel(pm);
            if (label != null && label.length() > 0) {
                String s = label.toString().trim();
                // label 有效且不是包名本身时使用；否则继续走 DB 映射
                if (!s.isEmpty() && !s.equals(packageName)) {
                    return s;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "label 解析失败: " + packageName);
        }

        // 2. DB 应用名映射（来自服务器 eye_app_names，可在数据库维护）
        String known = DB_NAMES.get(packageName);
        if (known != null) return known;

        // 3. 回退包名
        return packageName;
    }
}
