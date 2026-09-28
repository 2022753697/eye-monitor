package com.eyemonitor.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.service.MonitorService;

/**
 * 开机自启接收器。
 * <p>
 * 设备重启后自动启动监控服务（如果之前已配对）。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        Log.i(TAG, "设备开机，检查是否需要启动监控服务");

        PrefsManager prefs = new PrefsManager(context);
        if (!prefs.isPaired()) {
            Log.d(TAG, "未配对，不启动服务");
            return;
        }

        // 启动前台监控服务
        Intent serviceIntent = new Intent(context, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
        Log.i(TAG, "监控服务已自动启动");
    }
}