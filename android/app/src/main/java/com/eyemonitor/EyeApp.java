package com.eyemonitor;

import android.app.Application;

/**
 * Application 入口。
 * <p>
 * 全局初始化工作放在这里，但目前保持轻量。
 */
public class EyeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 后续可在此初始化全局组件
    }
}