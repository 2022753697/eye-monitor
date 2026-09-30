package com.eyemonitor.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.eyemonitor.config.PrefsManager;

/**
 * 轻量保活 Worker（P1）：周期自检监控服务存活。
 * <p>
 * 主保活 = {@code MonitorService} 前台服务（通知常驻）+ {@code WSClient} 指数退避重连；
 * 本 Worker 兜底「服务被系统回收」场景：已配对但服务不在 → 重新拉起
 * （与 BootReceiver 相同的 startForegroundService 路径）。
 */
public class KeepAliveWorker extends Worker {

    private static final String TAG = "KeepAliveWorker";

    public KeepAliveWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        PrefsManager prefs = new PrefsManager(ctx);
        if (!prefs.isPaired()) {
            Log.d(TAG, "未配对，无需保活");
            return Result.success();
        }
        if (MonitorService.isRunning()) {
            Log.d(TAG, "监控服务存活，wsConnected=" + MonitorService.isWsConnected()
                    + "（断线重连由 WSClient 指数退避处理）");
            return Result.success();
        }
        Log.w(TAG, "监控服务不在运行，尝试拉起");
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, MonitorService.class));
            return Result.success();
        } catch (Exception e) {
            // Android 12+ 后台 FGS 启动限制下可能被拒；记录并等下一周期重试
            Log.e(TAG, "拉起监控服务失败（后台限制？），下一周期重试", e);
            return Result.success();
        }
    }
}