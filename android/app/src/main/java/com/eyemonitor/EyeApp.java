package com.eyemonitor;

import android.app.Application;

import com.eyemonitor.util.AppNameResolver;

/**
 * Application 入口。
 * <p>
 * 全局初始化：预热应用名映射缓存（包名→中文名，DB 驱动）。
 */
public class EyeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 应用名映射从本地 Room 预热（网络同步前的离线兜底）
        AppNameResolver.warmUp(this);
    }
}