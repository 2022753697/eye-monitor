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
 * 运行时权限请求（进入 App 第一时间一次性弹出全部缺失权限），分两批：
 * 批次1（安全集合）：通知(13+) / 媒体(READ_MEDIA_* 或 READ_EXTERNAL_STORAGE) /
 *                    前台精确定位 / 蓝牙连接(12+) / 录音。
 * 批次2（后台定位，单独一批）：Android 11+ 规定后台定位必须在前台定位已授权后
 *                    单独请求，与其它权限同批会导致 PermissionController 报错并关闭整个弹窗流。
 * 全部批次结束后才弹监控设置引导（使用情况访问/无障碍，系统设置类，无法运行时申请）。
 * 引导最多提示 3 次，两项都开启前每次从设置页返回都会复查。
 */
public final class PermissionHelper {

    private static final String TAG = "PermissionHelper";
    public static final int REQ_RUNTIME_BASIC = 100;
    public static final int REQ_RUNTIME_BG = 101;
    private static final int MONITOR_PROMPT_MAX = 3;

    private PermissionHelper() {}

    /** 启动权限流程入口：批次1 → 必要时批次2（后台定位）→ 监控设置引导 */
    public static void startEntryPermissionFlow(Activity activity) {
        requestMissing(activity);
        // 批次1无需弹（全部已授权）时，直接处理批次2或引导
        if (!hasMissingRuntime(activity)) {
            maybeRequestBackgroundOrGuide(activity);
        }
    }

    /** 批次1结束后回调各活动；requestCode 落入本流程时继续后继步骤 */
    public static void onEntryFlowStep(Activity activity, int requestCode) {
        if (requestCode == REQ_RUNTIME_BASIC) {
            maybeRequestBackgroundOrGuide(activity);
        } else if (requestCode == REQ_RUNTIME_BG) {
            postGuide(activity);
        }
    }

    /** 批次2：前台定位已授权且后台定位仍缺时，单独请求后台定位；否则直接进入引导 */
    private static void maybeRequestBackgroundOrGuide(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && activity.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "单独请求后台定位（Android 11+ 必须与前台定位分批次）");
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQ_RUNTIME_BG);
            return;
        }
        postGuide(activity);
    }

    private static void postGuide(Activity activity) {
        activity.getWindow().getDecorView()
                .postDelayed(() -> guideMonitorSettings(activity), 300);
    }

    /** 批次1安全集合：请求缺失的运行时权限（不含后台定位，避免非法组合） */
    public static void requestMissing(Activity activity) {
        java.util.List<String> need = missingRuntime(activity);
        if (!need.isEmpty()) {
            Log.d(TAG, "启动一次性请求缺失权限: " + need);
            activity.requestPermissions(need.toArray(new String[0]), REQ_RUNTIME_BASIC);
        }
    }

    /** 批次1安全集合是否仍有缺失（不含后台定位；后台定位走批次2独立流程） */
    public static boolean hasMissingRuntime(Activity activity) {
        return !missingRuntime(activity).isEmpty();
    }

    private static java.util.List<String> missingRuntime(Activity activity) {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
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
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        return need;
    }

    /** 监控权限引导（使用情况访问/无障碍，系统设置类）。必须等运行时权限批次全部结束后调用。
     *  自定义弹窗：每行一个权限，点行跳设置，返回后自动打勾（√），两项都开启后自动关闭。
     *  最多提示 MONITOR_PROMPT_MAX 次。 */
    private static android.app.AlertDialog currentGuideDialog;

    public static void guideMonitorSettings(Activity activity) {
        boolean hasUsageStats = AppUsageTracker.hasUsageStatsPermission(activity);
        boolean hasAccessibility = AccessibilityDiagnostic.isAccessibilityEnabled(activity);
        PrefsManager prefs = new PrefsManager(activity);
        if (hasUsageStats && hasAccessibility) {
            prefs.setMonitorSettingsPending(false);
            return;
        }
        if (prefs.getMonitorPromptCount() >= MONITOR_PROMPT_MAX) {
            prefs.setMonitorSettingsPending(false);
            return;
        }
        prefs.setMonitorPromptCount(prefs.getMonitorPromptCount() + 1);
        prefs.setMonitorSettingsPending(true);

        android.view.View body = android.view.LayoutInflater.from(activity)
                .inflate(R.layout.dialog_monitor_permission, null);
        body.findViewById(R.id.row_guide_usage).setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(activity, R.string.dialog_monitor_permission_fallback,
                        Toast.LENGTH_LONG).show();
            }
        });
        body.findViewById(R.id.row_guide_accessibility).setOnClickListener(v ->
                AccessibilityDiagnostic.openAccessibilitySettings(activity));

        currentGuideDialog = new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_monitor_permission_title)
                .setView(body)
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    new PrefsManager(activity).setMonitorSettingsPending(false);
                    currentGuideDialog = null;
                })
                .create();
        currentGuideDialog.setOnDismissListener(d -> {
            if (currentGuideDialog == d) currentGuideDialog = null;
        });
        currentGuideDialog.show();
        refreshMonitorDialog(activity);
    }

    /** 刷新当前引导弹窗的勾选态：用户从设置页返回后调用（onResume）。
     *  弹窗保持打开不消失，哪项已开启就变 √；两项都开启自动关闭。 */
    public static void refreshMonitorDialog(Activity activity) {
        if (currentGuideDialog == null) {
            // 弹窗被关但流程仍在（如 Activity 重建）：重开一次
            PrefsManager prefs = new PrefsManager(activity);
            if (prefs.isMonitorSettingsPending()) guideMonitorSettings(activity);
            return;
        }
        if (!currentGuideDialog.isShowing()) return;
        boolean hasUsageStats = AppUsageTracker.hasUsageStatsPermission(activity);
        boolean hasAccessibility = AccessibilityDiagnostic.isAccessibilityEnabled(activity);
        android.widget.TextView tvUsage = currentGuideDialog.findViewById(R.id.tv_guide_usage_check);
        android.widget.TextView tvAcc = currentGuideDialog.findViewById(R.id.tv_guide_accessibility_check);
        setCheck(tvUsage, hasUsageStats);
        setCheck(tvAcc, hasAccessibility);
        if (hasUsageStats && hasAccessibility) {
            currentGuideDialog.dismiss();
            currentGuideDialog = null;
            new PrefsManager(activity).setMonitorSettingsPending(false);
        }
    }

    private static void setCheck(android.widget.TextView tv, boolean on) {
        if (tv == null) return;
        tv.setText(on ? "✓" : "");
        tv.setBackgroundResource(on ? R.drawable.bg_dot : R.drawable.bg_select_badge_off);
    }

    /** 从监控设置页返回后复查：刷新弹窗勾选态（弹窗保持打开，两项都开自动关闭） */
    public static void guideIfReturnedFromSettings(Activity activity) {
        PrefsManager prefs = new PrefsManager(activity);
        if (!prefs.isMonitorSettingsPending()) return;
        refreshMonitorDialog(activity);
    }
}