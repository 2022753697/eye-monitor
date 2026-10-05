package com.eyemonitor;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.util.AppNameResolver;

/**
 * Application 入口。
 * <p>
 * 全局初始化：应用名映射缓存预热（包名→中文名，DB 驱动）+ 深色模式三态应用。
 */
public class EyeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 深色模式三态（Phase 4）：follow_system / light / dark → AppCompatDelegate
        PrefsManager prefs = new PrefsManager(this);
        String mode = prefs.getNightMode();
        int night = PrefsManager.NIGHT_DARK.equals(mode)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : PrefsManager.NIGHT_LIGHT.equals(mode)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(night);
        // 应用名映射从本地 Room 预热（网络同步前的离线兜底）
        AppNameResolver.warmUp(this);
    }
}