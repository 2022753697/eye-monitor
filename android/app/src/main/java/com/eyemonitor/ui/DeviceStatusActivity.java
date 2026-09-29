package com.eyemonitor.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.DeviceStatusTracker;
import com.eyemonitor.service.MonitorService;

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

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

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

        Log.d(TAG, "刷新状态: online=" + online + ", battery=" + battery
                + ", charging=" + prefs.getPeerCharging() + ", network=" + network
                + ", bluetooth=" + prefs.getPeerBluetooth());
    }
}
