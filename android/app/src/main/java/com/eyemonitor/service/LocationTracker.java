package com.eyemonitor.service;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.util.GeoMath;

/**
 * 位置追踪器 - 双通道定位
 * <p>
 * 主通道：高德 SDK（需 API Key，调试签名下可能 auth fail）
 * 兜底通道：系统 LocationManager（GPS/NETWORK provider，不依赖高德 Key/Google）
 * 任一通道返回有效位置都会更新并上报。
 */
public class LocationTracker implements AMapLocationListener {

    private static final String TAG = "LocationTracker";
    /** 亮屏定位间隔 */
    private static final long UPDATE_INTERVAL_MS = 30_000L;
    /** 息屏定位间隔（省电 P1：60-120s 取中值 90s） */
    private static final long UPDATE_INTERVAL_SCREEN_OFF_MS = 90_000L;
    private static final float MIN_DISTANCE_M = 50f;
    private static final long MIN_LOCATION_INTERVAL_MS = 15_000L; // 最小定位间隔15秒

    private final Context context;
    private AMapLocationClient locationClient;
    private final AMapLocationClientOption locationOption;

    // 系统定位兜底
    private LocationManager locationManager;
    private final LocationListener systemListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location != null) {
                updateLocation(location.getLatitude(), location.getLongitude(), location.getAccuracy());
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    private volatile double lastLat;
    private volatile double lastLng;
    private volatile float lastAccuracy;
    private volatile long lastUpdateTime;
    private volatile boolean screenOn = true;

    public LocationTracker(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        this.locationOption = new AMapLocationClientOption();
        initLocationOption();
        try {
            this.locationClient = new AMapLocationClient(context);
            this.locationClient.setLocationListener(this);
        } catch (Exception e) {
            Log.e(TAG, "初始化高德定位客户端失败（将仅用系统定位）", e);
            this.locationClient = null;
        }
    }

    private void initLocationOption() {
        locationOption.setOnceLocation(false);
        locationOption.setInterval(currentIntervalMs());
        locationOption.setNeedAddress(true);
        if (screenOn) {
            locationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
        } else {
            // 息屏省电档：省电模式（平衡精度与功耗）
            locationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Battery_Saving);
        }
        locationOption.setGpsFirst(true);
    }

    /** 当前定位间隔（随屏态变化） */
    private long currentIntervalMs() {
        return screenOn ? UPDATE_INTERVAL_MS : UPDATE_INTERVAL_SCREEN_OFF_MS;
    }

    /** 屏态变化：息屏 → 90s 降频 + 省电模式；亮屏 → 30s 高频恢复（大位移立即报/静止不报规则不变） */
    public void setScreenOn(boolean on) {
        if (screenOn == on) return;
        screenOn = on;
        long interval = currentIntervalMs();
        Log.i(TAG, "屏态变化: " + (on ? "亮屏" : "息屏") + ", 定位间隔=" + interval + "ms, 模式="
                + (on ? "Hight_Accuracy" : "Battery_Saving"));
        // 系统通道：按新间隔重挂监听
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(systemListener);
            } catch (Exception ignored) {}
            startSystemLocation();
        }
        // 高德通道：更新 option 并重启（无 Key/认证失败时静默降级）
        if (locationClient != null) {
            try {
                locationClient.stopLocation();
                initLocationOption();
                locationClient.setLocationOption(locationOption);
                locationClient.startLocation();
                Log.d(TAG, "高德定位已按新档重启");
            } catch (Exception e) {
                Log.e(TAG, "高德定位切换档位失败（继续系统通道）", e);
            }
        }
    }

    public void start() {
        // 1. 高德定位（若有 Key 且认证通过）
        if (locationClient != null) {
            Log.d(TAG, "开始高德定位");
            locationClient.startLocation();
        }
        // 2. 系统定位兜底（GPS/NETWORK）
        startSystemLocation();
    }

    private void startSystemLocation() {
        if (locationManager == null) return;
        boolean hasFine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "无定位权限，跳过系统定位（需 ACCESS_FINE/COARSE_LOCATION）");
            return;
        }
        try {
            long interval = currentIntervalMs();
            if (hasFine) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        interval, MIN_DISTANCE_M, systemListener);
            }
            if (hasFine || hasCoarse) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        interval, MIN_DISTANCE_M, systemListener);
            }
            // 立即取一次最近位置（模拟器 geo fix 后 GPS 有值）
            Location last = hasFine
                    ? locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
            if (last == null) {
                last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (last != null) {
                Log.i(TAG, "系统定位取到最近位置: " + last.getLatitude() + "," + last.getLongitude());
                updateLocation(last.getLatitude(), last.getLongitude(), last.getAccuracy());
            }
            Log.d(TAG, "系统定位监听已启动");
        } catch (SecurityException e) {
            Log.e(TAG, "系统定位权限异常", e);
        } catch (Exception e) {
            Log.e(TAG, "启动系统定位失败", e);
        }
    }

    public void stop() {
        if (locationClient != null) {
            Log.d(TAG, "停止高德定位");
            locationClient.stopLocation();
        }
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(systemListener);
            } catch (Exception ignored) {}
        }
    }

    /** 高德定位回调 */
    @Override
    public void onLocationChanged(AMapLocation location) {
        if (location == null) {
            Log.w(TAG, "高德定位结果为空");
            return;
        }
        if (location.getErrorCode() != 0) {
            Log.w(TAG, "高德定位失败: " + location.getErrorInfo());
            return;
        }
        updateLocation(location.getLatitude(), location.getLongitude(), location.getAccuracy());
    }

    /** 统一位置更新入口（高德 + 系统双通道），带去重过滤 */
    private synchronized void updateLocation(double lat, double lng, float accuracy) {
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime < MIN_LOCATION_INTERVAL_MS) {
            return;
        }
        if (lastLat != 0 && Math.abs(lat - lastLat) < 0.0001
                && Math.abs(lng - lastLng) < 0.0001) {
            return;
        }
        // R3 精度过滤：首次定位例外（保证有初始值），之后精度>80m 的点不采纳
        if (lastLat != 0 && accuracy > GeoMath.ACCURACY_FILTER_M) {
            Log.d(TAG, "精度过滤，跳过: accuracy=" + accuracy + "m");
            return;
        }
        // R4' 跳点过滤：物理不可能速度，视为 GPS 抽风（高铁/飞机速度远低于阈值）
        if (lastLat != 0) {
            double dist = GeoMath.distanceMeters(lastLat, lastLng, lat, lng);
            long dt = now - lastUpdateTime;
            if (GeoMath.isImpossibleJump(dist, dt)) {
                Log.w(TAG, "跳点过滤，跳过: dist=" + (long) dist + "m dt=" + (dt / 1000) + "s");
                return;
            }
        }

        lastLat = lat;
        lastLng = lng;
        lastAccuracy = accuracy;
        lastUpdateTime = now;

        Log.i(TAG, String.format("定位成功: %.6f, %.6f, accuracy=%.0fm", lat, lng, accuracy));
    }

    public double getLastLat() { return lastLat; }
    public double getLastLng() { return lastLng; }
    public float getLastAccuracy() { return lastAccuracy; }
    public long getLastUpdateTime() { return lastUpdateTime; }
    public boolean hasLocation() { return lastLat != 0; }

    public WsMessage createLocationMessage(String deviceId, String pairCode) {
        return WsMessage.createLocation(
                deviceId, pairCode, lastLat, lastLng, lastAccuracy);
    }
}
