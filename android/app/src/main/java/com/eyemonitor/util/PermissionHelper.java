package com.eyemonitor.util;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.service.AppUsageTracker;
import com.eyemonitor.util.AccessibilityDiagnostic;
import com.eyemonitor.util.UiDialogs;

/**
 * 运行时权限请求（进入 App 第一时间一次性弹出全部缺失权限）：
 * 通知(POST_NOTIFICATIONS, 13+) / 媒体读取(READ_MEDIA_* 或 READ_EXTERNAL_STORAGE) /
 * 定位(前台+后台) / 蓝牙连接(12+) / 录音(RECORD_AUDIO)。
 * 系统一次只显示一个对话框，多次请求会依次排队弹出，即「一次性获取完」。
 * 系统设置类权限（使用情况访问/无障碍）无法运行时申请，需跳设置页，不在此列。
 */
public final class PermissionHelper {

    private static final String TAG = "PermissionHelper";
    public static final int REQ_RUNTIME_BASIC = 100;

    private PermissionHelper() {}

    /** 请求当前缺失的全部运行时权限（登录页与主界面首次进入都会调用，幂等） */
    public static void requestMissing(Activity activity) {
        java.util.List<String> need = new java.util.ArrayList<>();

        // 通知（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        // 媒体读取：13+ READ_MEDIA_*；9-12 READ_EXTERNAL_STORAGE；≤8 无需
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (activity.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        // 定位：前台精确定位 + 后台定位（10+）
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && activity.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }

        // 蓝牙连接（12+，桌面/手表联动等）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        // 录音（语音消息）
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!need.isEmpty()) {
            Log.d(TAG, "启动一次性请求缺失权限: " + need);
            activity.requestPermissions(need.toArray(new String[0]), REQ_RUNTIME_BASIC);
        }
    }

    /** 监控权限引导（使用情况访问 / 无障碍，系统设置类，无法运行时弹窗）：仅首次提示一次，
     *  进入 App 第一时间与运行时权限一起完成。已具备其一或已提示过则不打扰 */
    public static void guideMonitorSettings(Activity activity) {
        PrefsManager prefs = new PrefsManager(activity);
        if (prefs.isPermissionPrompted()) return;
        boolean hasUsageStats = AppUsageTracker.hasUsageStatsPermission(activity);
        boolean hasAccessibility = AccessibilityDiagnostic.isAccessibilityEnabled(activity);
        if (hasUsageStats || hasAccessibility) return;
        prefs.setPermissionPrompted(true);
        UiDialogs.actions(activity,
                activity.getString(R.string.dialog_monitor_permission_title),
                activity.getString(R.string.dialog_permission_hint_message),
                activity.getString(R.string.btn_open_accessibility),
                activity.getString(R.string.btn_open_usage_stats), false,
                () -> AccessibilityDiagnostic.openAccessibilitySettings(activity),
                () -> {
                    try {
                        activity.startActivity(
                                new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(activity,
                                R.string.dialog_monitor_permission_fallback,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }
}