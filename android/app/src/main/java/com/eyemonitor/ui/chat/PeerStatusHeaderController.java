package com.eyemonitor.ui.chat;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.service.DeviceStatusTracker;
import com.eyemonitor.ui.DeviceStatusActivity;
import com.eyemonitor.ui.MainActivity;
import com.eyemonitor.util.Transitions;

/** 聊天头栏状态行控制器（⑧ 切片重构）：电量% · 充电 · 网络 · 蓝牙 · 在线，单击进设备状态详情页。 */
public class PeerStatusHeaderController {

    private final MainActivity activity;
    private final PrefsManager prefs;

    private View peerStatusBar;
    private ImageView ivPeerBattery;
    private TextView tvPeerBatteryPct;
    private ImageView ivPeerCharging;
    private TextView tvPeerNetwork;
    private ImageView ivPeerBluetooth;
    private TextView tvPeerOnline;

    public PeerStatusHeaderController(MainActivity activity, View root, PrefsManager prefs) {
        this.activity = activity;
        this.prefs = prefs;
        peerStatusBar = root.findViewById(R.id.peer_status_bar);
        ivPeerBattery = root.findViewById(R.id.iv_peer_battery);
        tvPeerBatteryPct = root.findViewById(R.id.tv_peer_battery_pct);
        ivPeerCharging = root.findViewById(R.id.iv_peer_charging);
        tvPeerNetwork = root.findViewById(R.id.tv_peer_network);
        ivPeerBluetooth = root.findViewById(R.id.iv_peer_bluetooth);
        tvPeerOnline = root.findViewById(R.id.tv_peer_online);
        // 顶栏状态行：单击进对方设备状态详情页（恢复原行为）
        peerStatusBar.setOnClickListener(v -> {
            activity.startActivity(new Intent(activity, DeviceStatusActivity.class));
            Transitions.push(activity);
        });
    }

    /** 刷新状态行：电量% · 充电 · 网络 · 蓝牙 · 在线（离线置灰） */
    public void refresh() {
        int battery = prefs.getPeerBattery();
        tvPeerBatteryPct.setText(battery >= 0
                ? activity.getString(R.string.percent_format, battery)
                : activity.getString(R.string.status_unknown));
        ivPeerBattery.setImageResource(batteryIconRes(battery));
        ivPeerCharging.setImageResource(prefs.getPeerCharging()
                ? R.drawable.ic_charging_on : R.drawable.ic_charging_off);
        ivPeerBluetooth.setImageResource(prefs.getPeerBluetooth()
                ? R.drawable.ic_bluetooth_on : R.drawable.ic_bluetooth_off);

        String network = prefs.getPeerNetwork();
        String networkText;
        if (DeviceStatusTracker.NETWORK_WIFI.equals(network)) {
            networkText = activity.getString(R.string.status_network_wifi);
        } else if (DeviceStatusTracker.NETWORK_MOBILE.equals(network)) {
            networkText = activity.getString(R.string.status_network_mobile);
        } else if (DeviceStatusTracker.NETWORK_NONE.equals(network)) {
            networkText = activity.getString(R.string.status_network_none);
        } else {
            networkText = activity.getString(R.string.status_unknown);
        }
        tvPeerNetwork.setText(networkText);

        boolean online = prefs.getPeerOnline();
        tvPeerOnline.setText(online ? R.string.status_online : R.string.status_offline);
        tvPeerOnline.setTextColor(online
                ? activity.getColor(R.color.status_success)
                : activity.getColor(R.color.text_on_primary_muted));
    }

    /** 按电量选择 5 级电池图标 */
    private int batteryIconRes(int battery) {
        if (battery < 20) return R.drawable.ic_battery_lv0;
        if (battery < 40) return R.drawable.ic_battery_lv1;
        if (battery < 60) return R.drawable.ic_battery_lv2;
        if (battery < 80) return R.drawable.ic_battery_lv3;
        return R.drawable.ic_battery_lv4;
    }
}