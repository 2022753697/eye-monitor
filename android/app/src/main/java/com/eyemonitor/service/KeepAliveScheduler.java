package com.eyemonitor.service;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * 轻量保活调度器（P1）：注册 15 分钟周期自检 Worker。
 * <p>
 * 幂等：unique name + KEEP 策略，重复调用只保留一个周期任务。
 * 15 分钟是 WorkManager 周期下限；验证期可临时调短。
 */
public final class KeepAliveScheduler {

    private static final String TAG = "KeepAliveScheduler";
    private static final String UNIQUE_NAME = "eye_keepalive";
    /** 周期（分钟）——WorkManager 周期任务下限为 15 分钟 */
    private static final long PERIOD_MINUTES = 15L;

    private KeepAliveScheduler() {}

    /** 幂等注册（服务启动/登录成功后重复调用安全） */
    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(KeepAliveWorker.class, PERIOD_MINUTES, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
        Log.i(TAG, "15 分钟周期保活已注册");
    }
}