package com.eyemonitor.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.AppAccessibilityService;
import com.eyemonitor.service.AppUsageTracker;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.util.AccessibilityDiagnostic;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 实时监控界面 - 显示对方设备 App 切换历史
 */
public class MonitorActivity extends AppCompatActivity {

    private static final String TAG = "MonitorActivity";
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private PrefsManager prefs;
    private RecyclerView recyclerView;
    private AppSwitchAdapter adapter;
    private List<AppSwitchItem> itemList;

    private TextView tvStatus;
    private TextView tvSwitchCount;
    private Button btnSettings;
    private Button btnBack;

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(MonitorService.EXTRA_EVENT_JSON);
            if (json != null) {
                WsMessage msg = WsMessage.fromJson(json);
                if (msg != null) {
                    onEventReceived(msg);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitor);

        prefs = new PrefsManager(this);

        recyclerView = findViewById(R.id.recycler_view);
        tvStatus = findViewById(R.id.tv_status);
        tvSwitchCount = findViewById(R.id.tv_switch_count);
        btnSettings = findViewById(R.id.btn_settings);
        btnBack = findViewById(R.id.btn_back);

        itemList = new ArrayList<>();
        adapter = new AppSwitchAdapter(itemList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // 注册事件广播
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(eventReceiver, filter);
        }

        btnSettings.setOnClickListener(v -> openSettings());
        btnBack.setOnClickListener(v -> finish());

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(eventReceiver);
        super.onDestroy();
    }

    private void updateStatus() {
        boolean isPaired = prefs.isPaired();
        boolean serviceRunning = isServiceRunning();

        if (!isPaired) {
            tvStatus.setText(R.string.status_not_paired);
            tvStatus.setTextColor(getColor(R.color.status_error));
        } else if (!serviceRunning) {
            tvStatus.setText(R.string.status_not_running);
            tvStatus.setTextColor(getColor(R.color.status_warn));
        } else {
            tvStatus.setText(R.string.status_running);
            tvStatus.setTextColor(getColor(R.color.status_ok));
        }

        // 更新切换次数统计
        if (tvSwitchCount != null) {
            tvSwitchCount.setText(String.valueOf(itemList.size()));
        }
    }

    private boolean isServiceRunning() {
        android.app.ActivityManager manager =
                (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo service :
                manager.getRunningServices(Integer.MAX_VALUE)) {
            if (MonitorService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void onEventReceived(WsMessage message) {
        switch (message.getType()) {
            case "app_switch":
                handleAppSwitch(message);
                break;
            case "pair_confirm":
                updateStatus();
                break;
            default:
                break;
        }
    }

    private void handleAppSwitch(WsMessage message) {
        String appName = getString(R.string.toast_unknown_app);
        String packageName = "";
        if (message.getPayload() != null) {
            Object name = message.getPayload().get("appName");
            Object pkg = message.getPayload().get("packageName");
            if (name instanceof String) {
                appName = (String) name;
            }
            if (pkg instanceof String) {
                packageName = (String) pkg;
            }
        }

        String time = TIME_FORMAT.format(new Date(message.getTimestamp()));
        itemList.add(0, new AppSwitchItem(appName, packageName, time));
        adapter.notifyDataSetChanged();

        // 更新统计计数
        tvSwitchCount.setText(String.valueOf(itemList.size()));

        // 显示 Toast
        Toast.makeText(this, getString(R.string.toast_peer_opened, appName), Toast.LENGTH_SHORT).show();
    }

    private void openSettings() {
        boolean hasUsageStats = AppUsageTracker.hasUsageStatsPermission(this);
        boolean hasAccessibility = AccessibilityDiagnostic.isAccessibilityEnabled(this);

        if (!hasUsageStats && !hasAccessibility) {
            showPermissionDialog();
        } else {
            // 打开无障碍设置
            AccessibilityDiagnostic.openAccessibilitySettings(this);
        }
    }

    private boolean hasAccessibilityEnabled() {
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || enabledServices.isEmpty()) {
                return false;
            }
            String myService = getPackageName() + "/" + AppAccessibilityService.class.getName();
            return enabledServices.contains(myService);
        } catch (Exception e) {
            return false;
        }
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_monitor_permission_title)
                .setMessage(R.string.dialog_monitor_permission_message)
                .setPositiveButton(R.string.go_settings, (dialog, which) -> {
                    AccessibilityDiagnostic.openAccessibilitySettings(this);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // --- 数据类 ---

    public static class AppSwitchItem {
        public final String appName;
        public final String packageName;
        public final String time;

        public AppSwitchItem(String appName, String packageName, String time) {
            this.appName = appName;
            this.packageName = packageName;
            this.time = time;
        }
    }

    // --- RecyclerView Adapter ---

    public static class AppSwitchAdapter extends RecyclerView.Adapter<AppSwitchAdapter.ViewHolder> {
        private final List<AppSwitchItem> items;

        public AppSwitchAdapter(List<AppSwitchItem> items) {
            this.items = items;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app_switch, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            AppSwitchItem item = items.get(position);
            holder.tvAppName.setText(item.appName);
            holder.tvPackageName.setText(item.packageName);
            holder.tvTime.setText(item.time);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvAppName;
            TextView tvPackageName;
            TextView tvTime;

            ViewHolder(View view) {
                super(view);
                tvAppName = view.findViewById(R.id.tv_app_name);
                tvPackageName = view.findViewById(R.id.tv_package_name);
                tvTime = view.findViewById(R.id.tv_time);
            }
        }
    }
}
