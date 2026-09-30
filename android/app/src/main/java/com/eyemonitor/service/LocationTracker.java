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

/**
 * 位置追踪器 - 双通道定位
 * <p>
 * 主通道：高德 SDK（需 API Key，调试签名下可能 auth fail）
 * 兜底通道：系统 LocationManager（GPS/NETWORK provider，不依赖高德 Key/Google）
 * 任一通道返回有效位置都会更新并上报。
 */
public class LocationTracker implements AMapLocationListener {

    private static final String TAG = "LocationTracker";
    private static final long UPDATE_INTERVAL_MS = 30_000L;
    private static final float MIN_DISTANCE_M = 50f;
    private static final long MIN_LOCATION_INTERVAL_MS = 15_000L; // 最小定位间隔15秒
    /** R3 精度过滤：已有位置后，精度>80m 的点不采纳（防漂移污染轨迹） */
    private static final float ACCURACY_FILTER_M = 80f;
    /** R4' 跳点过滤：推算速度 > 2000km/h（≈555.6m/s，远高于飞机 900km/h）视为 GPS 抽风 */
    private static final float MAX_SPEED_MPS = 555.6f;

    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

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
        locationOption.setInterval(UPDATE_INTERVAL_MS);
        locationOption.setNeedAddress(true);
        locationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
        locationOption.setGpsFirst(true);
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
            if (hasFine) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL_MS, MIN_DISTANCE_M, systemListener);
            }
            if (hasFine || hasCoarse) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        UPDATE_INTERVAL_MS, MIN_DISTANCE_M, systemListener);
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
        if (lastLat != 0 && accuracy > ACCURACY_FILTER_M) {
            Log.d(TAG, "精度过滤，跳过: accuracy=" + accuracy + "m");
            return;
        }
        // R4' 跳点过滤：物理不可能速度，视为 GPS 抽风（高铁/飞机速度远低于阈值）
        if (lastLat != 0) {
            double dist = distanceMeters(lastLat, lastLng, lat, lng);
            long dt = now - lastUpdateTime;
            if (dt > 0 && dist / (dt / 1000.0) > MAX_SPEED_MPS) {
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
