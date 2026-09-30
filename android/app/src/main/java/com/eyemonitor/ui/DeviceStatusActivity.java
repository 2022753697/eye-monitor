package com.eyemonitor.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.DeviceStatusTracker;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.util.UiDialogs;

/**
 * 对方设备状态详情页（完整五件套：电量 / 充电 / 网络 / 蓝牙 / 在线）。
 * <p>
 * 数据来自对方 device_status 上报（MonitorService 缓存到 PrefsManager），
 * 监听 ACTION_EVENT 广播实时刷新。
 */
public class DeviceStatusActivity extends AppCompatActivity {

    private static final String TAG = "DeviceStatusActivity";

    private PrefsManager prefs;
    private TextView tvTitle;
    private TextView tvOnline;
    private TextView tvBattery;
    private TextView tvCharging;
    private TextView tvNetwork;
    private TextView tvBluetooth;
    private TextView tvBatteryOpt;
    private TextView tvPeerRemark;

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(MonitorService.EXTRA_EVENT_JSON);
            if (json != null) {
                WsMessage msg = WsMessage.fromJson(json);
                if (msg != null) {
                    refresh();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_status);

        prefs = new PrefsManager(this);

        tvTitle = findViewById(R.id.tv_title);
        tvOnline = findViewById(R.id.tv_device_online);
        tvBattery = findViewById(R.id.tv_device_battery);
        tvCharging = findViewById(R.id.tv_device_charging);
        tvNetwork = findViewById(R.id.tv_device_network);
        tvBluetooth = findViewById(R.id.tv_device_bluetooth);
        tvBatteryOpt = findViewById(R.id.tv_battery_opt);
        tvPeerRemark = findViewById(R.id.tv_peer_remark);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // 电池优化白名单入口：点击跳系统设置（拒绝后可从此处后悔）
        tvBatteryOpt.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Log.w(TAG, "无法跳转电池优化设置", e);
            }
        });

        // 备注：点击弹编辑框（微信式，备注优先显示；留空清除）
        findViewById(R.id.row_peer_remark).setOnClickListener(v ->
                UiDialogs.showRemarkDialog(this, prefs, this::refresh));

        IntentFilter filter = new IntentFilter(MonitorService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(eventReceiver, filter);
        }

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(eventReceiver);
        super.onDestroy();
    }

    private void refresh() {
        String peer = prefs.getPeerNickname();
        tvTitle.setText(peer != null && !peer.isEmpty()
                ? peer : getString(R.string.device_status_title));

        boolean online = prefs.getPeerOnline();
        tvOnline.setText(getString(online ? R.string.status_online : R.string.status_offline));
        tvOnline.setTextColor(online ? getColor(R.color.status_ok) : getColor(R.color.text_secondary));

        int battery = prefs.getPeerBattery();
        tvBattery.setText(battery >= 0
                ? getString(R.string.percent_format, battery) : getString(R.string.status_unknown));

        tvCharging.setText(getString(prefs.getPeerCharging()
                ? R.string.status_charging : R.string.status_not_charging));

        String network = prefs.getPeerNetwork();
        if (DeviceStatusTracker.NETWORK_WIFI.equals(network)) {
            tvNetwork.setText(R.string.status_network_wifi);
        } else if (DeviceStatusTracker.NETWORK_MOBILE.equals(network)) {
            tvNetwork.setText(R.string.status_network_mobile);
        } else if (DeviceStatusTracker.NETWORK_NONE.equals(network)) {
            tvNetwork.setText(R.string.status_network_none);
        } else {
            tvNetwork.setText(R.string.status_unknown);
        }

        tvBluetooth.setText(getString(prefs.getPeerBluetooth()
                ? R.string.status_on : R.string.status_off));

        // 电池优化白名单（本机豁免状态）
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        tvBatteryOpt.setText(getString(exempt ? R.string.battery_opt_exempt : R.string.battery_opt_not_exempt));
        tvBatteryOpt.setTextColor(getColor(exempt ? R.color.status_ok : R.color.status_warn));

        // 备注（未设置=次级灰提示编辑；已设置=主色展示）
        String remark = prefs.getPeerRemark();
        if (remark != null && !remark.isEmpty()) {
            tvPeerRemark.setText(remark);
            tvPeerRemark.setTextColor(getColor(R.color.text_primary));
        } else {
            tvPeerRemark.setText(R.string.peer_remark_not_set);
            tvPeerRemark.setTextColor(getColor(R.color.text_secondary));
        }

        Log.d(TAG, "刷新状态: online=" + online + ", battery=" + battery
                + ", charging=" + prefs.getPeerCharging() + ", network=" + network
                + ", bluetooth=" + prefs.getPeerBluetooth());
    }
}
