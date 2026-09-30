package com.eyemonitor.ui;

import android.app.DatePickerDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;
import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.LocationCacheEntity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

/**
 * 轨迹回放界面（Wave-2）：只回放对方轨迹。
 * <p>
 * 时间段选择（今天 / 近7天 / 自定义）+ 播放/暂停 + 倍速 0.5x/1x/2x。
 * 数据源：先读本地 LocationCacheEntity（对端位置实时入缓存），无则查服务端
 * GET /api/tracks/{pairCode}?start&end&device。
 * 渲染：AMap Polyline 按时间顺序逐点追加（动画渐长），播放完自动复位。
 */
public class TrackReplayActivity extends AppCompatActivity {

    private static final String TAG = "TrackReplayActivity";
    private static final long BASE_TICK_MS = 600L;   // 1x 速度下每点间隔
    private static final int MAX_PLAYBACK_POINTS = 1000; // 点数过多时均匀抽稀，控制动画时长
    private static final float[] SPEEDS = {0.5f, 1f, 2f};

    private static final int RANGE_TODAY = 0;
    private static final int RANGE_7D = 1;
    private static final int RANGE_CUSTOM = 2;

    private PrefsManager prefs;
    private MapView mapView;
    private AMap aMap;
    private Button btnRangeToday;
    private Button btnRange7d;
    private Button btnRangeCustom;
    private Button btnPlayPause;
    private Button btnSpeed;
    private TextView tvProgress;

    private final List<TrackPoint> points = new ArrayList<>();
    private int currentIndex = 0;
    private boolean playing = false;
    private int speedIndex = 1; // 默认 1x
    private float speed = 1f;

    private Polyline polyline;
    private Marker playMarker;

    private long rangeStart = 0;
    private long rangeEnd = 0;
    private int selectedRange = RANGE_TODAY;
    private long pickStart = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!playing) return;
            // 从 idx-1 平滑移动到 idx（线性插值 + 相机跟随），完成后推进下一拍
            animateSegment(currentIndex);
        }
    };

    /** 平滑移动 marker 从 points[idx-1] 到 points[idx]；到位后绘制折线、推进进度、进入下一拍 */
    private void animateSegment(final int idx) {
        if (!playing || idx <= 0 || idx >= points.size()) {
            finishPlayback();
            return;
        }
        final TrackPoint from = points.get(idx - 1);
        final TrackPoint to = points.get(idx);
        if (playMarker == null) {
            playMarker = aMap.addMarker(new MarkerOptions()
                    .position(new LatLng(from.lat, from.lng))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE))
                    .anchor(0.5f, 0.5f));
        }
        final long duration = Math.max(80L, tickIntervalMs());
        final long startAt = System.currentTimeMillis();
        final int[] frameCount = {0}; // 用于相机跟随节流
        final Runnable frame = new Runnable() {
            @Override
            public void run() {
                if (!playing) return;
                float t = Math.min(1f, (System.currentTimeMillis() - startAt) / (float) duration);
                double lat = from.lat + (to.lat - from.lat) * t;
                double lng = from.lng + (to.lng - from.lng) * t;
                playMarker.setPosition(new LatLng(lat, lng));
                if (t >= 1f) {
                    drawPolylineUpTo(idx);
                    currentIndex = idx + 1; // 关键：推进到下一段（原来漏掉导致永远播同一段）
                    // 到达当前点：marker 气泡显示到达时刻
                    String arrive = formatTrackTime(points.get(idx).ts);
                    if (playMarker != null) {
                        playMarker.setTitle(arrive);
                        playMarker.showInfoWindow();
                    }
                    updateProgress(currentIndex);
                    if (idx + 1 >= points.size()) {
                        finishPlayback();
                    } else {
                        handler.postDelayed(tickRunnable, 0);
                    }
                    return;
                }
                // 相机平滑跟随（约每 100ms 一次，避免每帧触发动画堆积）
                frameCount[0]++;
                if (frameCount[0] % 6 == 0) {
                    aMap.animateCamera(CameraUpdateFactory.newLatLng(new LatLng(lat, lng)), 100, null);
                }
                handler.postDelayed(this, 16L);
            }
        };
        handler.postDelayed(frame, 0);
    }

    private final Runnable resetRunnable = new Runnable() {
        @Override
        public void run() {
            resetPlayback();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_track_replay);

        prefs = new PrefsManager(this);
        try {
            MapsInitializer.initialize(getApplicationContext());
            // 高德SDK隐私合规：必须在使用任何接口前调用
            MapsInitializer.updatePrivacyShow(getApplicationContext(), true, true);
            MapsInitializer.updatePrivacyAgree(getApplicationContext(), true);
        } catch (Exception e) {
            Log.e(TAG, "MapsInitializer初始化失败", e);
        }

        mapView = findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState);
        btnRangeToday = findViewById(R.id.btn_range_today);
        btnRange7d = findViewById(R.id.btn_range_7d);
        btnRangeCustom = findViewById(R.id.btn_range_custom);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnSpeed = findViewById(R.id.btn_speed);
        tvProgress = findViewById(R.id.tv_progress);

        btnRangeToday.setOnClickListener(v -> selectRange(RANGE_TODAY));
        btnRange7d.setOnClickListener(v -> selectRange(RANGE_7D));
        btnRangeCustom.setOnClickListener(v -> selectRange(RANGE_CUSTOM));
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnSpeed.setOnClickListener(v -> cycleSpeed());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        initMap();
        selectRange(RANGE_TODAY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        clearPolyline();
        removePlayMarker();
        if (mapView != null) mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    private void initMap() {
        if (aMap == null) {
            aMap = mapView.getMap();
            aMap.getUiSettings().setZoomControlsEnabled(true);
            aMap.getUiSettings().setCompassEnabled(true);
        }
    }

    // --- 时间段选择 ---

    private void selectRange(int index) {
        selectedRange = index;
        updateRangeButtonState();
        long now = System.currentTimeMillis();
        if (index == RANGE_TODAY) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            rangeStart = cal.getTimeInMillis();
            rangeEnd = now;
            loadTracks();
        } else if (index == RANGE_7D) {
            rangeStart = now - 7L * 24 * 3600 * 1000;
            rangeEnd = now;
            loadTracks();
        } else {
            showCustomRangeDialog();
        }
    }

    private void showCustomRangeDialog() {
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(this, (v, y, m, d) -> {
            Calendar start = Calendar.getInstance();
            start.set(y, m, d, 0, 0, 0);
            start.set(Calendar.MILLISECOND, 0);
            pickStart = start.getTimeInMillis();
            showEndPicker();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showEndPicker() {
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(this, (v, y, m, d) -> {
            Calendar end = Calendar.getInstance();
            end.set(y, m, d, 23, 59, 59);
            end.set(Calendar.MILLISECOND, 999);
            long endTs = end.getTimeInMillis();
            if (endTs < pickStart) {
                Toast.makeText(this, R.string.track_custom_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            rangeStart = pickStart;
            rangeEnd = endTs;
            loadTracks();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateRangeButtonState() {
        int[] ids = {R.id.btn_range_today, R.id.btn_range_7d, R.id.btn_range_custom};
        for (int i = 0; i < ids.length; i++) {
            Button b = findViewById(ids[i]);
            if (i == selectedRange) {
                b.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.primary)));
                b.setTextColor(getColor(R.color.text_on_primary));
            } else {
                b.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface)));
                b.setTextColor(getColor(R.color.primary));
            }
        }
    }

    // --- 数据加载：先读本地缓存，无则查服务端 ---

    private void loadTracks() {
        pausePlayback();
        clearPolyline();
        removePlayMarker();
        points.clear();
        currentIndex = 0;
        tvProgress.setText(R.string.track_loading);

        AppDatabase.dbExecutor.execute(() -> {
            List<LocationCacheEntity> local = AppDatabase.getInstance(TrackReplayActivity.this)
                    .cacheDao().getLocations(rangeStart, rangeEnd);
            runOnUiThread(() -> {
                if (local != null && !local.isEmpty()) {
                    applyPoints(convertCache(local));
                } else {
                    fetchServerTracks();
                }
            });
        });
    }

    private List<TrackPoint> convertCache(List<LocationCacheEntity> list) {
        List<TrackPoint> out = new ArrayList<>();
        for (LocationCacheEntity e : list) {
            out.add(new TrackPoint(e.lat, e.lng, e.accuracy, e.ts));
        }
        return out;
    }

    private void fetchServerTracks() {
        String pairCode = prefs.getPairCode();
        if (pairCode == null || pairCode.isEmpty()) {
            showEmptyState();
            return;
        }
        String device = prefs.getPeerDeviceId();
        if (device == null || device.isEmpty()) {
            // 对端 deviceId 未知：从 /api/pairs/me 的 peerProfile.deviceId 获取
            AuthManager.i(this).get(this, "/api/pairs/me", new AuthManager.Callback() {
                @Override
                public void onSuccess(JsonObject data) {
                    String peerDeviceId = null;
                    if (data.has("peerProfile") && !data.get("peerProfile").isJsonNull()) {
                        JsonObject peer = data.getAsJsonObject("peerProfile");
                        if (peer.has("deviceId") && !peer.get("deviceId").isJsonNull()) {
                            peerDeviceId = peer.get("deviceId").getAsString();
                        }
                    }
                    if (peerDeviceId != null && !peerDeviceId.isEmpty()) {
                        prefs.setPeerDeviceId(peerDeviceId);
                        fetchTracks(pairCode, peerDeviceId);
                    } else {
                        showEmptyState();
                    }
                }

                @Override
                public void onError(int code, String msg) {
                    showEmptyState();
                }
            });
        } else {
            fetchTracks(pairCode, device);
        }
    }

    private void fetchTracks(String pairCode, String device) {
        String path = "/api/tracks/" + pairCode + "?start=" + rangeStart + "&end=" + rangeEnd
                + "&device=" + android.net.Uri.encode(device);
        AuthManager.i(this).getElement(this, path, new AuthManager.ElementCallback() {
            @Override
            public void onSuccess(JsonElement data) {
                List<TrackPoint> list = new ArrayList<>();
                if (data.isJsonArray()) {
                    for (JsonElement e : data.getAsJsonArray()) {
                        JsonObject o = e.getAsJsonObject();
                        double lat = o.get("lat").getAsDouble();
                        double lng = o.get("lng").getAsDouble();
                        float accuracy = o.has("accuracy") && !o.get("accuracy").isJsonNull()
                                ? (float) o.get("accuracy").getAsDouble() : 0f;
                        long ts = o.has("ts") && !o.get("ts").isJsonNull()
                                ? o.get("ts").getAsLong() : 0L;
                        list.add(new TrackPoint(lat, lng, accuracy, ts));
                    }
                }
                final List<TrackPoint> result = list;
                runOnUiThread(() -> applyPoints(result));
            }

            @Override
            public void onError(int code, String msg) {
                showEmptyState();
            }
        });
    }

    private void applyPoints(List<TrackPoint> raw) {
        List<TrackPoint> valid = new ArrayList<>();
        for (TrackPoint p : raw) {
            if (p.lat == 0 && p.lng == 0) continue; // 过滤无效定位点
            valid.add(p);
        }
        valid.sort(Comparator.comparingLong(p -> p.ts));
        points.clear();
        points.addAll(valid);
        // 点数过多时均匀抽稀，控制动画时长（仍保持时间顺序）
        if (points.size() > MAX_PLAYBACK_POINTS) {
            int step = (int) Math.ceil((double) points.size() / MAX_PLAYBACK_POINTS);
            List<TrackPoint> sampled = new ArrayList<>();
            for (int i = 0; i < points.size(); i += step) {
                sampled.add(points.get(i));
            }
            if (sampled.get(sampled.size() - 1) != points.get(points.size() - 1)) {
                sampled.add(points.get(points.size() - 1));
            }
            points.clear();
            points.addAll(sampled);
        }

        if (points.size() < 2) {
            tvProgress.setText(R.string.track_empty);
            Toast.makeText(this, R.string.track_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        fitCameraToTracks();
        // 进入页面即显示对方该时间段内最近位置（未播放也可见，提升体验）
        showInitialPosition();
        tvProgress.setText(getString(R.string.track_progress, 0, points.size()));
        Log.i(TAG, "轨迹点已加载: " + points.size() + " 个 (start=" + rangeStart + ", end=" + rangeEnd + ")");
    }

    /** 进入即展示对方最近位置 marker 并聚焦（不播放也可见） */
    private void showInitialPosition() {
        if (points.isEmpty()) return;
        TrackPoint last = points.get(points.size() - 1);
        LatLng pos = new LatLng(last.lat, last.lng);
        if (playMarker == null) {
            playMarker = aMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE))
                    .anchor(0.5f, 0.5f));
        } else {
            playMarker.setPosition(pos);
        }
        aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos,
                Math.max(aMap.getCameraPosition().zoom, 15f)), 300, null);
    }

    private void showEmptyState() {
        runOnUiThread(() -> {
            tvProgress.setText(R.string.track_no_data);
            Toast.makeText(TrackReplayActivity.this, R.string.track_no_data, Toast.LENGTH_SHORT).show();
        });
    }

    private void fitCameraToTracks() {
        if (points.size() < 2) return;
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (TrackPoint p : points) {
            b.include(new LatLng(p.lat, p.lng));
        }
        aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100));
    }

    // --- 播放控制 ---

    private void togglePlayPause() {
        if (playing) {
            pausePlayback();
        } else {
            startPlayback();
        }
    }

    private void startPlayback() {
        if (points.size() < 2) {
            Toast.makeText(this, R.string.track_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        clearPolyline();
        removePlayMarker();
        currentIndex = 1;
        playing = true;
        btnPlayPause.setText(R.string.track_pause);
        drawPolylineUpTo(currentIndex);
        updateProgress(currentIndex + 1);
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(resetRunnable);
        handler.postDelayed(tickRunnable, tickIntervalMs());
        Log.i(TAG, "轨迹回放开始: speed=" + speed + "x");
    }

    private void pausePlayback() {
        playing = false;
        btnPlayPause.setText(R.string.track_play);
        handler.removeCallbacks(tickRunnable);
    }

    /** 播放到末尾：展示完整折线片刻后自动复位 */
    private void finishPlayback() {
        playing = false;
        btnPlayPause.setText(R.string.track_play);
        updateProgress(currentIndex);
        Log.i(TAG, "轨迹回放结束，自动复位");
        handler.removeCallbacks(resetRunnable);
        handler.postDelayed(resetRunnable, 1200L);
    }

    private void resetPlayback() {
        playing = false;
        btnPlayPause.setText(R.string.track_play);
        clearPolyline();
        removePlayMarker();
        currentIndex = 0;
        tvProgress.setText(getString(R.string.track_progress, 0, points.size()));
    }

    /** 调速即时生效：重排下一拍 */
    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        speed = SPEEDS[speedIndex];
        btnSpeed.setText(speedText(speed));
        if (playing) {
            handler.removeCallbacks(tickRunnable);
            handler.postDelayed(tickRunnable, tickIntervalMs());
        }
        Log.i(TAG, "回放倍速: " + speed + "x");
    }

    private String speedText(float s) {
        if (s == 0.5f) return getString(R.string.track_speed_half);
        if (s == 2f) return getString(R.string.track_speed_double);
        return getString(R.string.track_speed_one);
    }

    private long tickIntervalMs() {
        return (long) (BASE_TICK_MS / speed);
    }

    private void drawPolylineUpTo(int index) {
        int end = Math.min(index, points.size() - 1);
        List<LatLng> pts = new ArrayList<>();
        for (int i = 0; i <= end; i++) {
            pts.add(new LatLng(points.get(i).lat, points.get(i).lng));
        }
        if (polyline == null) {
            polyline = aMap.addPolyline(new PolylineOptions()
                    .addAll(pts)
                    .color(getColor(R.color.primary))
                    .width(8f)
                    .setUseTexture(true));
        } else {
            polyline.setPoints(pts);
        }
    }

    private void updatePlayMarker(int index) {
        TrackPoint p = points.get(Math.min(index, points.size() - 1));
        LatLng pos = new LatLng(p.lat, p.lng);
        if (playMarker == null) {
            playMarker = aMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE))
                    .anchor(0.5f, 1.0f));
        } else {
            playMarker.setPosition(pos);
        }
    }

    /** 进度标签：drawn = 已绘制的点数（含起点），附带到达时刻 */
    private void updateProgress(int drawn) {
        int shown = Math.min(drawn, points.size());
        String time = shown > 0 ? formatTrackTime(points.get(shown - 1).ts) : "";
        tvProgress.setText(getString(R.string.track_progress, shown, points.size(), time));
    }

    /** 到达时刻：当天 HH:mm，跨天 M月d日 HH:mm */
    private String formatTrackTime(long ts) {
        if (ts <= 0) return "--:--";
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar point = java.util.Calendar.getInstance();
        point.setTimeInMillis(ts);
        boolean sameDay = now.get(java.util.Calendar.YEAR) == point.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == point.get(java.util.Calendar.DAY_OF_YEAR);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                sameDay ? "HH:mm" : "M月d日 HH:mm", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(ts));
    }

    private void clearPolyline() {
        if (polyline != null) {
            polyline.remove();
            polyline = null;
        }
    }

    private void removePlayMarker() {
        if (playMarker != null) {
            playMarker.remove();
            playMarker = null;
        }
    }

    /** 轨迹点（lat/lng/accuracy/ts，ts 为毫秒时间戳，与协议一致） */
    private static class TrackPoint {
        final double lat;
        final double lng;
        final float accuracy;
        final long ts;

        TrackPoint(double lat, double lng, float accuracy, long ts) {
            this.lat = lat;
            this.lng = lng;
            this.accuracy = accuracy;
            this.ts = ts;
        }
    }
}
