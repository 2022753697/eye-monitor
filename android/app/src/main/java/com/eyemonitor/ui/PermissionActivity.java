package com.eyemonitor.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.eyemonitor.R;
import com.eyemonitor.receiver.BootReceiver;
import com.eyemonitor.service.AppUsageTracker;
import com.eyemonitor.util.AccessibilityDiagnostic;

/**
 * 权限中心（原 MonitorActivity 改造）。
 * <p>
 * 列出 App 所需的全部权限：运行时权限（通知/定位/录音/蓝牙）直接请求，
 * 系统设置类（使用情况访问/无障碍/电池优化/自启动）跳对应设置页。
 * 进入页面与 onResume 时刷新状态。
 */
public class PermissionActivity extends BaseActivity {

    private static final String TAG = "PermissionActivity";
    private static final int REQ_PERMISSION = 6601;

    private TextView tvNotification, tvLocation, tvAudio, tvBluetooth;
    private TextView tvUsage, tvAccessibility, tvBattery, tvBoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission);

        tvNotification = findViewById(R.id.tv_perm_notification);
        tvLocation = findViewById(R.id.tv_perm_location);
        tvAudio = findViewById(R.id.tv_perm_audio);
        tvBluetooth = findViewById(R.id.tv_perm_bluetooth);
        tvUsage = findViewById(R.id.tv_perm_usage);
        tvAccessibility = findViewById(R.id.tv_perm_accessibility);
        tvBattery = findViewById(R.id.tv_perm_battery);
        tvBoot = findViewById(R.id.tv_perm_boot);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        findViewById(R.id.row_perm_notification).setOnClickListener(v ->
                requestMissing(new String[]{Manifest.permission.POST_NOTIFICATIONS}));
        findViewById(R.id.row_perm_location).setOnClickListener(v ->
                requestMissing(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION}));
        findViewById(R.id.row_perm_audio).setOnClickListener(v ->
                requestMissing(new String[]{Manifest.permission.RECORD_AUDIO}));
        findViewById(R.id.row_perm_bluetooth).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestMissing(new String[]{Manifest.permission.BLUETOOTH_CONNECT});
            }
        });
        findViewById(R.id.row_perm_usage).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                Log.w(TAG, "无法跳转使用情况访问设置", e);
            }
        });
        findViewById(R.id.row_perm_accessibility).setOnClickListener(v ->
                AccessibilityDiagnostic.openAccessibilitySettings(this));
        findViewById(R.id.row_perm_battery).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Log.w(TAG, "无法跳转电池优化设置", e);
            }
        });
        findViewById(R.id.row_perm_boot).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Log.w(TAG, "无法跳转应用详情", e);
            }
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        // 运行时权限
        int notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS);
        setStatus(tvNotification,
                notif == PackageManager.PERMISSION_GRANTED,
                R.string.perm_granted, R.string.perm_not_granted);

        boolean locOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        setStatus(tvLocation, locOk, R.string.perm_granted, R.string.perm_not_granted);

        boolean audioOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        setStatus(tvAudio, audioOk, R.string.perm_granted, R.string.perm_not_granted);

        boolean btOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        setStatus(tvBluetooth, btOk, R.string.perm_granted, R.string.perm_not_granted);

        // 系统设置类
        setStatus(tvUsage, AppUsageTracker.hasUsageStatsPermission(this),
                R.string.perm_on, R.string.perm_off);
        setStatus(tvAccessibility, AccessibilityDiagnostic.isAccessibilityEnabled(this),
                R.string.perm_on, R.string.perm_off);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        setStatus(tvBattery, pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()),
                R.string.perm_exempt, R.string.perm_not_exempt);

        // 开机自启：默认组件开启；用户可在应用详情禁停
        boolean bootOk = getPackageManager().getComponentEnabledSetting(
                new ComponentName(this, BootReceiver.class))
                != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        setStatus(tvBoot, bootOk, R.string.perm_always_on, R.string.perm_disabled, false);
    }

    /** 状态文案 + 颜色（绿=已授权，橙=未授权；isClickableHint=false 时保持次级灰） */
    private void setStatus(TextView tv, boolean ok, int okTextRes, int notOkTextRes) {
        setStatus(tv, ok, okTextRes, notOkTextRes, true);
    }

    private void setStatus(TextView tv, boolean ok, int okTextRes, int notOkTextRes,
                           boolean colored) {
        tv.setText(getString(ok ? okTextRes : notOkTextRes));
        tv.setTextColor(getColor(ok ? R.color.status_ok
                : (colored ? R.color.status_warn : R.color.text_secondary)));
    }

    private void requestMissing(String[] perms) {
        java.util.List<String> pending = new java.util.ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                pending.add(p);
            }
        }
        if (!pending.isEmpty()) {
            requestPermissions(pending.toArray(new String[0]), REQ_PERMISSION);
        }
    }
}