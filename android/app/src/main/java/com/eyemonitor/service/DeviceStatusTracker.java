package com.eyemonitor.service;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 设备状态追踪器（五件套：电量 / 充电 / 网络 / 蓝牙；在线由连接状态与 pair_confirm 驱动）。
 * <p>
 * - 电量：BatteryManager.BATTERY_PROPERTY_CAPACITY 初始读取 + ACTION_BATTERY_CHANGED 监听
 * - 充电：ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED（BATTERY_CHANGED 的 status 兜底）
 * - 网络：ConnectivityManager.getActiveNetworkInfo + registerDefaultNetworkCallback 监听
 * - 蓝牙：BluetoothAdapter.isEnabled() + ACTION_STATE_CHANGED 监听（缺权限时回退 30s 周期轮询）
 * <p>
 * 状态变化通过 {@link Listener} 回调（主线程），由 MonitorService 触发 device_status 上报。
 */
public class DeviceStatusTracker {

    private static final String TAG = "DeviceStatusTracker";

    public interface Listener {
        /** 状态变化回调（主线程） */
        void onDeviceStatusChanged(DeviceStatus status);
    }

    /** 五件套状态快照（网络：wifi | mobile | none） */
    public static class DeviceStatus {
        public final int battery;      // 0-100，未知为 -1
        public final boolean charging;
        public final String network;   // NETWORK_WIFI | NETWORK_MOBILE | NETWORK_NONE
        public final boolean bluetooth;

        public DeviceStatus(int battery, boolean charging, String network, boolean bluetooth) {
            this.battery = battery;
            this.charging = charging;
            this.network = network;
            this.bluetooth = bluetooth;
        }
    }

    public static final String NETWORK_WIFI = "wifi";
    public static final String NETWORK_MOBILE = "mobile";
    public static final String NETWORK_NONE = "none";

    private final Context context;
    private final Listener listener;
    private final ConnectivityManager connectivityManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile int batteryLevel = -1;
    private volatile boolean charging;
    private volatile String network = NETWORK_NONE;
    private volatile boolean bluetoothOn;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            boolean changed = false;
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    int pct = Math.round(level * 100f / scale);
                    if (pct != batteryLevel) {
                        batteryLevel = pct;
                        changed = true;
                    }
                }
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                if (isCharging != charging) {
                    charging = isCharging;
                    changed = true;
                }
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                if (!charging) {
                    charging = true;
                    changed = true;
                }
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                if (charging) {
                    charging = false;
                    changed = true;
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                boolean on = state == BluetoothAdapter.STATE_ON;
                if (on != bluetoothOn) {
                    bluetoothOn = on;
                    changed = true;
                }
            }
            if (changed) {
                notifyChanged();
            }
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            notifyChanged();
        }

        @Override
        public void onLost(Network network) {
            notifyChanged();
        }

        @Override
        public void onCapabilitiesChanged(Network network, android.net.NetworkCapabilities networkCapabilities) {
            notifyChanged();
        }
    };

    public DeviceStatusTracker(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.connectivityManager =
                (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.bluetoothAdapter = safeGetBluetoothAdapter();
        readInitial();
        registerListeners();
    }

    private BluetoothAdapter safeGetBluetoothAdapter() {
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Exception e) {
            Log.w(TAG, "获取蓝牙适配器失败: " + e.getMessage());
            return null;
        }
    }

    private void readInitial() {
        // 电量：BatteryManager 属性（ACTION_BATTERY_CHANGED 为 sticky，注册后也会立即回调一次）
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (pct >= 0 && pct <= 100) {
                batteryLevel = pct;
            }
        } catch (Exception e) {
            Log.w(TAG, "读取电池电量失败: " + e.getMessage());
        }
        // 充电状态：BatteryManager.isCharging（API 23+）
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            charging = bm.isCharging();
        } catch (Exception e) {
            Log.w(TAG, "读取充电状态失败: " + e.getMessage());
        }
        network = readNetwork();
        bluetoothOn = readBluetooth();
    }

    private void registerListeners() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            context.registerReceiver(stateReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "注册状态广播失败（蓝牙监听可能因缺权限受限，回退 30s 周期上报）: " + e.getMessage());
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "注册网络回调失败: " + e.getMessage());
        }
    }

    public void stop() {
        try {
            context.unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {}
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
    }

    /** 获取当前五件套状态 */
    public DeviceStatus getStatus() {
        return new DeviceStatus(batteryLevel, charging, readNetwork(), readBluetooth());
    }

    private String readNetwork() {
        try {
            NetworkInfo info = connectivityManager.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return NETWORK_NONE;
            if (info.getType() == ConnectivityManager.TYPE_WIFI) return NETWORK_WIFI;
            if (info.getType() == ConnectivityManager.TYPE_MOBILE) return NETWORK_MOBILE;
            return NETWORK_WIFI; // 以太网等其他已连接类型按已联网展示
        } catch (Exception e) {
            return NETWORK_NONE;
        }
    }

    private boolean readBluetooth() {
        try {
            return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        } catch (SecurityException e) {
            Log.w(TAG, "读取蓝牙状态缺少 BLUETOOTH_CONNECT 权限: " + e.getMessage());
            return false;
        }
    }

    /** 状态变化统一出口：重读网络/蓝牙后回调（主线程） */
    private void notifyChanged() {
        network = readNetwork();
        bluetoothOn = readBluetooth();
        if (listener == null) return;
        final DeviceStatus status = getStatus();
        mainHandler.post(() -> listener.onDeviceStatusChanged(status));
    }
}
