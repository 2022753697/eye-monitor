package com.eyemonitor.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.Circle;
import com.amap.api.maps.model.CircleOptions;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.FenceCacheEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.MonitorService;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 地图界面 - 显示双方位置
 */
public class MapActivity extends AppCompatActivity {

    private static final String TAG = "MapActivity";
    private static final int LOCATION_REQ_CODE = 1001;

    private PrefsManager prefs;
    private MapView mapView;
    private AMap aMap;
    private ImageView ivAvatarSelf;
    private ImageView ivAvatarPeer;
    // 地图 marker 用小尺寸圆形头像（24dp，比顶部卡片 44dp 小一号）
    private com.amap.api.maps.model.BitmapDescriptor selfAvatarIcon;
    private com.amap.api.maps.model.BitmapDescriptor peerAvatarIcon;

    private final Map<String, Marker> peerMarkers = new HashMap<>();
    private Marker selfMarker;

    // --- 电子围栏（Wave2） ---
    private static final int MAX_FENCES = 3;
    private static final int MIN_FENCE_RADIUS_M = 100;
    private static final int MAX_FENCE_RADIUS_M = 50_000;

    private View btnMenuPeer;
    private View btnMenuSetFence;
    private View viewToolsMenu;
    private LinearLayout fenceSetupPanel;
    private TextView fenceCenterHint;
    private TextView fenceRadiusLabel;
    private SeekBar fenceRadiusSeek;
    private EditText fenceNameInput;
    private Button fenceCancelBtn;
    private Button fenceConfirmBtn;

    private boolean fenceMode = false;
    private LatLng fenceCenter;
    private Circle fencePreviewCircle;
    private final Map<Long, Circle> fenceCircles = new HashMap<>();
    // 圆环 → serverId（点击围栏定位到数据库记录，用于删除）
    private final Map<Circle, Long> fenceCircleIds = new HashMap<>();
    // dbExecutor 异步回调在 onDestroy 后可能到达，用该标志拦截 UI 操作
    private volatile boolean fenceUiAlive = true;
    private boolean userDraggingMap = false;
    private long lastSelfLocationTime = 0;
    private final Map<String, Long> peerLastLocationTime = new HashMap<>();
    private long lastSelfWsTime = 0;
    private static final long MIN_LOCATION_INTERVAL_MS = 10_000L;
    private static final float MIN_MOVE_DISTANCE_M = 5f; // 小于5米的位置变化忽略
    private static final long CAMERA_DEBOUNCE_MS = 3_000L;
    // 用户手动滑动地图后，停止滑动多少毫秒才恢复自动聚焦（防抢焦）
    private static final long MAP_IDLE_FOCUS_MS = 8_000L;
    private final Runnable dragIdleResetRunnable = () -> userDraggingMap = false;

    // 相机防抖
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable cameraAdjustRunnable = this::adjustCameraOnce;
    private long lastCameraAdjustTime = 0;

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MonitorService.ACTION_REQUEST_PEER_LOCATION.equals(action)) {
                // MapActivity请求对方位置，交给MonitorService处理
                Log.i(TAG, "收到请求对方位置请求");
                return;
            }
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
        setContentView(R.layout.activity_map);

        prefs = new PrefsManager(this);
        try {
            MapsInitializer.initialize(getApplicationContext());
            // 高德SDK隐私合规：必须在使用任何接口前调用
            com.amap.api.maps.MapsInitializer.updatePrivacyShow(getApplicationContext(), true, true);
            com.amap.api.maps.MapsInitializer.updatePrivacyAgree(getApplicationContext(), true);
        } catch (Exception e) {
            Log.e(TAG, "MapsInitializer初始化失败", e);
        }
        mapView = findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState);
        ivAvatarSelf = findViewById(R.id.iv_avatar_self);
        ivAvatarPeer = findViewById(R.id.iv_avatar_peer);
        viewToolsMenu = findViewById(R.id.top_tools_menu);

        // 点头像（两个都行）显示/隐藏工具菜单
        ivAvatarSelf.setOnClickListener(v -> toggleToolsMenu());
        ivAvatarPeer.setOnClickListener(v -> toggleToolsMenu());

        // 头像下小菜单（去找他 / 设围栏）+ 设围栏面板
        btnMenuPeer = findViewById(R.id.btn_menu_peer);
        btnMenuSetFence = findViewById(R.id.btn_menu_set_fence);
        fenceSetupPanel = findViewById(R.id.fence_setup_panel);
        fenceCenterHint = findViewById(R.id.fence_center_hint);
        fenceRadiusLabel = findViewById(R.id.fence_radius_label);
        fenceRadiusSeek = findViewById(R.id.fence_radius_seek);
        fenceNameInput = findViewById(R.id.fence_name_input);
        fenceCancelBtn = findViewById(R.id.fence_cancel_btn);
        fenceConfirmBtn = findViewById(R.id.fence_confirm_btn);
        btnMenuPeer.setOnClickListener(v -> {
            hideToolsMenu();
            goToPeer();
        });
        btnMenuSetFence.setOnClickListener(v -> {
            hideToolsMenu();
            enterFenceMode();
        });
        fenceCancelBtn.setOnClickListener(v -> exitFenceMode());
        fenceConfirmBtn.setOnClickListener(v -> confirmFenceCreate());
        fenceRadiusSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateFenceRadiusLabel();
                updateFencePreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // 顶部头像已改为 toggle 方式显示工具菜单（见 onCreate）

        initMap();
        requestLocationPermission();
        registerEventReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
        // 地图打开时主动请求对方位置 + 本机位置（本机用于尽快聚焦自己）
        requestPeerLocation();
        requestSelfLocation();
        // 围栏配置从 Room 缓存读并以 AMap Circle 渲染
        loadFenceCircles();
    }

    private void requestPeerLocation() {
        if (isServiceRunning()) {
            MonitorService.sendRequestPeerLocation(this);
            Log.i(TAG, "已发送请求对方位置");
        } else {
            Log.w(TAG, "MonitorService未运行，无法请求对方位置");
        }
    }

    /** 打开地图时立即触发一次本机位置上报（让 selfMarker 尽快出现并聚焦） */
    private void requestSelfLocation() {
        if (isServiceRunning()) {
            MonitorService.sendRequestSelfLocation(this);
            Log.i(TAG, "已请求本机位置上报");
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

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        fenceUiAlive = false;
        unregisterReceiver(eventReceiver);
        uiHandler.removeCallbacksAndMessages(null);
        cleanupMarkers();
        cleanupFenceCircles();
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
            // 启用高德自带的定位按钮（右下角圆圈箭头）
            aMap.getUiSettings().setMyLocationButtonEnabled(true);
            aMap.setMyLocationEnabled(true);

            // 按性别取色：女=粉 / 男=蓝；对方默认取反色（将来注册后可同步对方性别）
            boolean female = prefs.isFemale();
            int selfColor = female ? 0xFFFF6B6B : 0xFF5B8FF9;
            int peerColor = female ? 0xFF5B8FF9 : 0xFFFF6B6B;
            int selfAvatarRes = female ? R.drawable.avatar_female : R.drawable.avatar_male;
            int peerAvatarRes = female ? R.drawable.avatar_male : R.drawable.avatar_female;
            // 顶部头像随性别切换
            ivAvatarSelf.setImageResource(selfAvatarRes);
            ivAvatarPeer.setImageResource(peerAvatarRes);
            // 大头针 marker 图标（圆形头像 + 水滴尾巴，颜色随性别）
            selfAvatarIcon = createPinMarkerIcon(selfAvatarRes, selfColor, 26);
            peerAvatarIcon = createPinMarkerIcon(peerAvatarRes, peerColor, 26);
            MyLocationStyle style = new MyLocationStyle();
            // 只显示定位点，不自动移动相机（默认 LOCATE 类型会在每次定位时把相机居中到当前位置，
            // 绕过 userDraggingMap 拦截导致滑动被拉回）
            style.myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW);
            // 隐藏高德默认定位蓝点图标（否则与 selfMarker 重复显示两个自己图标），
            // 定位回调仍保留（selfMarker 位置由 onMyLocationChange 驱动）
            style.myLocationIcon(com.amap.api.maps.model.BitmapDescriptorFactory
                    .fromBitmap(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)));
            style.strokeColor(android.graphics.Color.argb(0, 0, 0, 0)); // 隐藏圆圈
            style.radiusFillColor(android.graphics.Color.argb(0, 0, 0, 0));
            aMap.setMyLocationStyle(style);

            // 自定义信息气泡（暖色圆角卡片）
            aMap.setInfoWindowAdapter(new AMap.InfoWindowAdapter() {
                @Override
                public View getInfoWindow(Marker marker) {
                    View v = LayoutInflater.from(MapActivity.this)
                            .inflate(R.layout.info_window, null);
                    TextView title = v.findViewById(R.id.info_title);
                    TextView snippet = v.findViewById(R.id.info_snippet);
                    title.setText(marker.getTitle());
                    String sn = marker.getSnippet();
                    snippet.setText(sn != null ? sn : "");
                    snippet.setVisibility(sn != null && !sn.isEmpty() ? View.VISIBLE : View.GONE);
                    return v;
                }

                @Override
                public View getInfoContents(Marker marker) {
                    return null;
                }
            });

            // 检测用户是否手动操作地图：按下即标记，抬起后 8 秒空闲才恢复自动聚焦
            aMap.setOnMapTouchListener(event -> {
                int action = event.getAction();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    // 触摸开始立即停止自动聚焦（滑动进行中位置更新不聚焦）
                    userDraggingMap = true;
                    uiHandler.removeCallbacks(dragIdleResetRunnable);
                    Log.d(TAG, "用户按下地图，停止自动聚焦");
                } else if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    // 触摸结束：8 秒空闲后恢复自动聚焦
                    userDraggingMap = true;
                    uiHandler.removeCallbacks(dragIdleResetRunnable);
                    uiHandler.postDelayed(dragIdleResetRunnable, MAP_IDLE_FOCUS_MS);
                    Log.d(TAG, "用户触摸地图结束，8秒内不自动聚焦");
                }
            });

            // 点击地图：设围栏模式选中心；普通模式点击落在围栏半径内 → 弹删除操作菜单
            aMap.setOnMapClickListener(latLng -> {
                if (fenceMode) {
                    selectFenceCenter(latLng);
                } else {
                    hideToolsMenu();
                    Long hit = findFenceAt(latLng);
                    if (hit != null) {
                        showFenceActions(hit);
                    }
                }
            });

            // 添加定位监听（仅更新已有marker位置，不创建marker，不移动相机）
            aMap.setOnMyLocationChangeListener(location -> {
                if (location == null) {
                    Log.w(TAG, "定位结果为null");
                    return;
                }

                long now = System.currentTimeMillis();
                if (now - lastSelfLocationTime < MIN_LOCATION_INTERVAL_MS) {
                    return;
                }
                lastSelfLocationTime = now;

                LatLng pos = new LatLng(location.getLatitude(), location.getLongitude());

                if (selfMarker == null) {
                    // 高德定位回调直接创建 selfMarker（不依赖 WS 上报时机），
                    // 对方位置未到时聚焦自己位置
                    selfMarker = aMap.addMarker(new MarkerOptions()
                            .position(pos)
                            .title(getString(R.string.map_marker_me))
                            .snippet(getString(R.string.map_marker_my_location))
                            .icon(selfAvatarIcon)
                            .anchor(0.5f, 1.0f));
                    Log.i(TAG, "高德定位创建selfMarker: " + pos.latitude + "," + pos.longitude);
                    if (peerMarkers.isEmpty() && !userDraggingMap) {
                        Log.i(TAG, "对方位置未到，自动聚焦自己位置");
                        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f));
                    }
                    return;
                }

                // 距离去重：与已有selfMarker位置差小于5米则忽略
                if (selfMarker != null) {
                    float[] dist = new float[1];
                    android.location.Location.distanceBetween(
                            selfMarker.getPosition().latitude, selfMarker.getPosition().longitude,
                            pos.latitude, pos.longitude, dist);
                    if (dist[0] < MIN_MOVE_DISTANCE_M) {
                        Log.d(TAG, "GPS位置变化太小(" + (int)dist[0] + "m)，忽略");
                        return;
                    }
                }

                if (selfMarker != null) {
                    selfMarker.setPosition(pos);
                    selfMarker.setSnippet(getString(R.string.map_snippet_gps_accuracy, (int)location.getAccuracy()));
                    Log.i(TAG, "GPS更新selfMarker位置: lat=" + location.getLatitude()
                            + ", lng=" + location.getLongitude());
                }
            });

            // 检查GPS是否开启
            checkGpsStatus();
        }
    }

    private void checkGpsStatus() {
        android.location.LocationManager locationManager =
                (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            boolean gpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
            boolean networkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
            Log.i(TAG, "GPS状态: GPS=" + gpsEnabled + ", Network=" + networkEnabled);
        }
    }

    private void requestLocationPermission() {
        boolean fineLocationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean backgroundLocationGranted = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (fineLocationGranted && backgroundLocationGranted) {
            Log.i(TAG, "定位权限已授予，开始定位");
            return;
        }

        // 直接弹出系统权限请求（不需要中间对话框）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }, LOCATION_REQ_CODE);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQ_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Log.i(TAG, "定位权限已授予，开始定位");
            } else {
                Log.w(TAG, "定位权限被拒绝");
            }
        }
    }

    private void registerEventReceiver() {
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(eventReceiver, filter);
        }
    }

    private void onEventReceived(WsMessage message) {
        Log.i(TAG, "广播接收: type=" + message.getType() + ", deviceId=" + message.getDeviceId()
                + ", myDeviceId=" + prefs.getDeviceId() + ", isSelf="
                + (message.getDeviceId() != null && message.getDeviceId().equals(prefs.getDeviceId())));
        switch (message.getType()) {
            case "location":
                handleLocationMessage(message);
                break;
            case "fence_sync":
                // 他端创建/删除围栏，缓存已由 SyncManager 更新，重绘圆环
                loadFenceCircles();
                break;
            case "pair_confirm":
                break;
            default:
                break;
        }
    }

    private void handleLocationMessage(WsMessage message) {
        if (aMap == null) {
            Log.w(TAG, "地图未初始化，跳过位置处理");
            return;
        }

        String deviceId = message.getDeviceId();
        String myDeviceId = prefs.getDeviceId();
        boolean isSelf = deviceId.equals(myDeviceId);
        Log.i(TAG, "收到位置消息: deviceId=" + deviceId + ", isSelf=" + isSelf
                + ", selfMarker=" + (selfMarker != null)
                + ", peerMarkers.size=" + peerMarkers.size());

        Map<String, Object> payload = message.getPayload();
        if (payload == null) {
            Log.w(TAG, "位置消息payload为null");
            return;
        }

        Object latObj = payload.get("lat");
        Object lngObj = payload.get("lng");
        if (!(latObj instanceof Number) || !(lngObj instanceof Number)) {
            Log.w(TAG, "位置坐标类型错误: lat=" + latObj + ", lng=" + lngObj);
            return;
        }

        double lat = ((Number) latObj).doubleValue();
        double lng = ((Number) lngObj).doubleValue();

        // 过滤无效位置（lat=0, lng=0 表示定位还未完成）
        if (lat == 0 && lng == 0) {
            Log.d(TAG, "收到无效位置(0,0)，跳过: " + deviceId);
            return;
        }

        // 时间过滤：10秒内不重复处理（仅用于拦截突发刷屏，不推进计时）
        long now = System.currentTimeMillis();
        long lastTime = isSelf ? lastSelfWsTime : peerLastLocationTime.getOrDefault(deviceId, 0L);
        boolean isFirstMessage = lastTime == 0;
        if (!isFirstMessage && now - lastTime < MIN_LOCATION_INTERVAL_MS) {
            Log.d(TAG, "位置更新过于频繁，跳过: " + deviceId);
            return;
        }

        LatLng pos = new LatLng(lat, lng);
        Log.i(TAG, "处理位置: isSelf=" + isSelf + ", lat=" + lat + ", lng=" + lng);

        if (isSelf) {
            if (selfMarker == null) {
                selfMarker = aMap.addMarker(new MarkerOptions()
                        .position(pos)
                        .title(getString(R.string.map_marker_me))
                        .snippet(getString(R.string.map_marker_my_location))
                        .icon(selfAvatarIcon)
                        .anchor(0.5f, 1.0f));
                lastSelfWsTime = now;
                // 刚进入地图且对方位置未到时，先聚焦自己位置
                if (peerMarkers.isEmpty() && !userDraggingMap) {
                    Log.i(TAG, "进入地图自动聚焦自己位置");
                    aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f));
                }
            } else {
                // 距离去重：位置变化小于5米则忽略（不推进计时，避免误伤后续有效更新）
                float[] dist = new float[1];
                android.location.Location.distanceBetween(
                        selfMarker.getPosition().latitude, selfMarker.getPosition().longitude,
                        pos.latitude, pos.longitude, dist);
                if (dist[0] < MIN_MOVE_DISTANCE_M) {
                    Log.d(TAG, "self位置变化太小(" + (int)dist[0] + "m)，忽略: " + deviceId);
                    return;
                }
                selfMarker.setPosition(pos);
                selfMarker.setSnippet(getString(R.string.map_snippet_accuracy, payload.containsKey("accuracy")
                        ? (int)((Number)payload.get("accuracy")).intValue() : 0));
                lastSelfWsTime = now;
            }
            if (!peerMarkers.isEmpty()) {
                scheduleCameraAdjust();
            }
        } else {
            Marker marker = peerMarkers.get(deviceId);
            if (marker == null) {
                marker = aMap.addMarker(new MarkerOptions()
                        .position(pos)
                        .title(getString(R.string.map_marker_peer))
                        .snippet(getString(R.string.map_marker_peer_id, deviceId))
                        .icon(peerAvatarIcon)
                        .anchor(0.5f, 1.0f));
                peerMarkers.put(deviceId, marker);
                peerLastLocationTime.put(deviceId, now);
                Log.i(TAG, "创建peerMarker, peerMarkers.size=" + peerMarkers.size());
            } else {
                // 距离去重（不推进计时，避免误伤后续有效更新）
                float[] dist = new float[1];
                android.location.Location.distanceBetween(
                        marker.getPosition().latitude, marker.getPosition().longitude,
                        pos.latitude, pos.longitude, dist);
                if (dist[0] < MIN_MOVE_DISTANCE_M) {
                    Log.d(TAG, "peer位置变化太小(" + (int)dist[0] + "m)，忽略: " + deviceId);
                    return;
                }
                marker.setPosition(pos);
                peerLastLocationTime.put(deviceId, now);
            }

            if (selfMarker != null) {
                scheduleCameraAdjust();
            } else {
                // 用户操作中不聚焦（绕过调度直接 moveCamera 的场景也拦截）
                if (userDraggingMap) {
                    Log.d(TAG, "用户操作中，跳过对方位置聚焦");
                } else {
                    aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 12f));
                }
            }
        }
    }

    /** 防抖调度：3秒内只调整一次相机 */
    private void scheduleCameraAdjust() {
        long now = System.currentTimeMillis();
        if (now - lastCameraAdjustTime < CAMERA_DEBOUNCE_MS) {
            Log.d(TAG, "相机调整过于频繁，跳过");
            return;
        }
        lastCameraAdjustTime = now;
        uiHandler.removeCallbacks(cameraAdjustRunnable);
        uiHandler.postDelayed(cameraAdjustRunnable, 500);
    }

    /** 调整相机位置，同时显示自己和对方的标记 */
    private void adjustCameraOnce() {
        // 防抢焦：用户刚操作过地图（8 秒空闲期内），不自动聚焦
        if (userDraggingMap) {
            Log.d(TAG, "用户正在操作地图，跳过自动聚焦");
            return;
        }
        if (peerMarkers.isEmpty()) return;
        if (selfMarker == null && peerMarkers.size() == 1) {
            Marker firstPeer = peerMarkers.values().iterator().next();
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(firstPeer.getPosition(), 12f));
            return;
        }
        if (selfMarker == null) return;

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;

        LatLng selfPos = selfMarker.getPosition();
        minLat = Math.min(minLat, selfPos.latitude);
        maxLat = Math.max(maxLat, selfPos.latitude);
        minLng = Math.min(minLng, selfPos.longitude);
        maxLng = Math.max(maxLng, selfPos.longitude);

        for (Marker marker : peerMarkers.values()) {
            LatLng p = marker.getPosition();
            minLat = Math.min(minLat, p.latitude);
            maxLat = Math.max(maxLat, p.latitude);
            minLng = Math.min(minLng, p.longitude);
            maxLng = Math.max(maxLng, p.longitude);
        }

        // 跨半球（经度跨度 > 180°，如中美）：单视野无法合理显示双方，
        // 强行 fit 会全球缩放到两点几乎不可见，退化为聚焦对方位置
        double lngSpan = maxLng - minLng;
        if (lngSpan > 180) {
            Marker nearestPeer = peerMarkers.values().iterator().next();
            Log.i(TAG, "跨半球距离（经度跨度=" + String.format("%.0f", lngSpan)
                    + "°），聚焦对方位置");
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nearestPeer.getPosition(), 5f));
            return;
        }

        float[] distResult = new float[1];
        android.location.Location.distanceBetween(selfPos.latitude, selfPos.longitude,
                (minLat + maxLat) / 2, (minLng + maxLng) / 2, distResult);
        float distanceM = distResult[0];

        Log.i(TAG, "相机调整: self=(" + selfPos.latitude + "," + selfPos.longitude
                + "), distance≈" + String.format("%.0f", distanceM / 1000) + "km");

        // 无论距离远近，总是把双方范围收进视野（zoom 由高德按 bounds 自动计算）
        com.amap.api.maps.model.LatLngBounds bounds = new com.amap.api.maps.model.LatLngBounds(
                new com.amap.api.maps.model.LatLng(minLat - 0.01, minLng - 0.01),
                new com.amap.api.maps.model.LatLng(maxLat + 0.01, maxLng + 0.01));
        aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 200));
    }

    /** 清理所有marker（Activity销毁时调用） */
    private void cleanupMarkers() {
        if (selfMarker != null) {
            selfMarker.remove();
            selfMarker = null;
        }
        for (Marker marker : peerMarkers.values()) {
            marker.remove();
        }
        peerMarkers.clear();
    }

    // --- 电子围栏（Wave2） ---

    /** 进入设围栏模式：先读缓存校验围栏数量上限（最多 3 个） */
    private void enterFenceMode() {
        if (!prefs.isPaired()) {
            Toast.makeText(this, R.string.fence_need_pair, Toast.LENGTH_SHORT).show();
            return;
        }
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            java.util.List<FenceCacheEntity> fences = db.cacheDao().getFences();
            runOnUiThread(() -> {
                if (!fenceUiAlive) return;
                if (fences.size() >= MAX_FENCES) {
                    Toast.makeText(this, R.string.fence_limit_reached, Toast.LENGTH_SHORT).show();
                    return;
                }
                fenceMode = true;
                fenceCenter = null;
                fenceNameInput.setText("");
                fenceRadiusSeek.setProgress(50);
                fenceCenterHint.setText(R.string.fence_hint_pick_center);
                updateFenceRadiusLabel();
                fenceSetupPanel.setVisibility(View.VISIBLE);
            });
        });
    }

    /** 退出设围栏模式并清理预览 */
    private void exitFenceMode() {
        fenceMode = false;
        fenceCenter = null;
        fenceSetupPanel.setVisibility(View.GONE);
        if (fencePreviewCircle != null) {
            fencePreviewCircle.remove();
            fencePreviewCircle = null;
        }
    }

    /** 点击位置命中围栏检测：落在任一围栏半径内返回其 serverId，否则 null（AMap 无圆环点击接口，用命中检测替代） */
    private Long findFenceAt(LatLng latLng) {
        if (latLng == null) return null;
        for (Circle c : fenceCircles.values()) {
            if (c.contains(latLng)) {
                return fenceCircleIds.get(c);
            }
        }
        return null;
    }

    /** 点击围栏范围：从缓存取信息，弹窗提供删除 */
    private void showFenceActions(long serverId) {
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            final FenceCacheEntity f = db.cacheDao().getFenceById(serverId);
            runOnUiThread(() -> {
                if (!fenceUiAlive) return;
                String name = f != null && f.name != null && !f.name.isEmpty()
                        ? f.name : getString(R.string.fence_unknown);
                String radiusTxt;
                double radius = f != null ? f.radius : 0;
                if (radius >= 1000) {
                    radiusTxt = getString(R.string.fence_radius_label_km, radius / 1000.0);
                } else {
                    radiusTxt = getString(R.string.fence_radius_label_m, (int) radius);
                }
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(name)
                        .setMessage(getString(R.string.fence_delete_message, radiusTxt))
                        .setPositiveButton(R.string.fence_delete_confirm, (d, w) -> deleteFence(serverId))
                        .setNegativeButton(R.string.fence_cancel, null)
                        .show();
            });
        });
    }

    /** 删除围栏：服务端真源删除，广播 fence_sync(delete) 同步双端缓存并重绘 */
    private void deleteFence(long serverId) {
        AuthManager.i(this).delete(this, "/api/fences/" + serverId, new AuthManager.Callback() {
            @Override
            public void onSuccess(JsonObject data) {
                // 围栏私有：无广播同步，删除成功后本地清理 Room 缓存并重绘
                AppDatabase db = AppDatabase.getInstance(MapActivity.this);
                AppDatabase.dbExecutor.execute(() -> {
                    db.cacheDao().deleteFence(serverId);
                    runOnUiThread(() -> {
                        if (!fenceUiAlive) return;
                        Toast.makeText(MapActivity.this, R.string.fence_delete_success, Toast.LENGTH_SHORT).show();
                        loadFenceCircles();
                    });
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    if (!fenceUiAlive) return;
                    Toast.makeText(MapActivity.this,
                            getString(R.string.fence_delete_failed, msg != null ? msg : ""),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** 创建成功后聚焦新围栏范围（视觉确认，仍停留在地图页） */
    private void focusOnFence(double centerLat, double centerLng, double radius) {
        double delta = radius * 2.4 / 111_320.0;
        com.amap.api.maps.model.LatLngBounds.Builder b = new com.amap.api.maps.model.LatLngBounds.Builder();
        b.include(new com.amap.api.maps.model.LatLng(centerLat + delta, centerLng + delta));
        b.include(new com.amap.api.maps.model.LatLng(centerLat - delta, centerLng - delta));
        aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 90), 600, null);
    }

    /** 设围栏模式下点击地图选择围栏中心 */
    private void selectFenceCenter(LatLng latLng) {
        fenceCenter = latLng;
        fenceCenterHint.setText(getString(R.string.fence_center_selected, latLng.latitude, latLng.longitude));
        updateFencePreview();
    }

    /** 半径滑块 0..100 -> 100m..50km（对数刻度，小半径可精细调节） */
    private double radiusFromProgress(int progress) {
        double r = MIN_FENCE_RADIUS_M
                * Math.pow(MAX_FENCE_RADIUS_M / (double) MIN_FENCE_RADIUS_M, progress / 100.0);
        return Math.max(MIN_FENCE_RADIUS_M, Math.round(r));
    }

    private void updateFenceRadiusLabel() {
        double r = radiusFromProgress(fenceRadiusSeek.getProgress());
        if (r < 1000) {
            fenceRadiusLabel.setText(getString(R.string.fence_radius_label_m, (int) r));
        } else {
            fenceRadiusLabel.setText(getString(R.string.fence_radius_label_km, r / 1000.0));
        }
    }

    /** 用预览圆在图上反馈当前的中心与半径 */
    private void updateFencePreview() {
        if (fenceCenter == null || aMap == null) return;
        double radius = radiusFromProgress(fenceRadiusSeek.getProgress());
        int color = fenceColor();
        if (fencePreviewCircle == null) {
            fencePreviewCircle = aMap.addCircle(new CircleOptions()
                    .center(fenceCenter)
                    .radius(radius)
                    .strokeWidth(2f)
                    .strokeColor(color)
                    .fillColor(fenceFillColor(color)));
        } else {
            fencePreviewCircle.setCenter(fenceCenter);
            fencePreviewCircle.setRadius(radius);
            fencePreviewCircle.setStrokeColor(color);
            fencePreviewCircle.setFillColor(fenceFillColor(color));
        }
    }

    /** 确认创建围栏：再次校验数量上限后 POST /api/fences */
    private void confirmFenceCreate() {
        if (fenceCenter == null) {
            Toast.makeText(this, R.string.fence_pick_center_first, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = fenceNameInput.getText() != null ? fenceNameInput.getText().toString().trim() : "";
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.fence_name_required, Toast.LENGTH_SHORT).show();
            return;
        }
        final double radius = radiusFromProgress(fenceRadiusSeek.getProgress());
        final double centerLat = fenceCenter.latitude;
        final double centerLng = fenceCenter.longitude;

        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            java.util.List<FenceCacheEntity> fences = db.cacheDao().getFences();
            if (fences.size() >= MAX_FENCES) {
                runOnUiThread(() -> {
                    if (fenceUiAlive) {
                        Toast.makeText(this, R.string.fence_limit_reached, Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("name", name);
            body.addProperty("lat", centerLat);
            body.addProperty("lng", centerLng);
            body.addProperty("radius", radius);
            runOnUiThread(() -> fenceConfirmBtn.setEnabled(false));
            AuthManager.i(this).postJson(this, "/api/fences", body.toString(), new AuthManager.Callback() {
                @Override
                public void onSuccess(JsonObject data) {
                    runOnUiThread(() -> {
                        if (!fenceUiAlive) return;
                        fenceConfirmBtn.setEnabled(true);
                        // 围栏私有：无广播同步，把服务端返回的新围栏直接写入本地 Room 缓存再重绘
                        final long serverId = data != null && data.has("id") ? data.get("id").getAsLong() : 0L;
                        if (serverId > 0) {
                            AppDatabase db = AppDatabase.getInstance(MapActivity.this);
                            AppDatabase.dbExecutor.execute(() -> {
                                FenceCacheEntity local = new FenceCacheEntity();
                                local.serverId = serverId;
                                local.name = data.has("name") && !data.get("name").isJsonNull()
                                        ? data.get("name").getAsString() : name;
                                local.lat = centerLat;
                                local.lng = centerLng;
                                local.radius = radius;
                                local.enabled = true;
                                db.cacheDao().upsertFence(local);
                            });
                        }
                        exitFenceMode();
                        Toast.makeText(MapActivity.this, R.string.fence_created, Toast.LENGTH_SHORT).show();
                        loadFenceCircles();
                        // 停留在地图页并聚焦新围栏（可视化确认“已设置”），不返回聊天页
                        focusOnFence(centerLat, centerLng, radius);
                    });
                }

                @Override
                public void onError(int code, String msg) {
                    runOnUiThread(() -> {
                        if (!fenceUiAlive) return;
                        fenceConfirmBtn.setEnabled(true);
                        Toast.makeText(MapActivity.this,
                                getString(R.string.fence_create_failed, msg != null ? msg : ""),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });
    }

    /** 从 Room 缓存读围栏配置并异步上屏 */
    private void loadFenceCircles() {
        if (aMap == null) return;
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            java.util.List<FenceCacheEntity> fences = db.cacheDao().getFences();
            runOnUiThread(() -> renderFenceCircles(fences));
        });
    }

    /** 围栏圆环渲染：按 serverId 增量更新，颜色随性别（女粉/男蓝）半透明填充 */
    private void renderFenceCircles(java.util.List<FenceCacheEntity> fences) {
        if (aMap == null || !fenceUiAlive) return;
        int color = fenceColor();
        int fill = fenceFillColor(color);
        java.util.Set<Long> keep = new java.util.HashSet<>();
        if (fences != null) {
            for (FenceCacheEntity f : fences) {
                if (!f.enabled) continue;
                keep.add(f.serverId);
                LatLng center = new LatLng(f.lat, f.lng);
                Circle circle = fenceCircles.get(f.serverId);
                if (circle == null) {
                    circle = aMap.addCircle(new CircleOptions()
                            .center(center)
                            .radius(f.radius)
                            .strokeWidth(2f)
                            .strokeColor(color)
                            .fillColor(fill));
                    fenceCircles.put(f.serverId, circle);
                    fenceCircleIds.put(circle, f.serverId);
                } else {
                    circle.setCenter(center);
                    circle.setRadius(f.radius);
                }
            }
        }
        java.util.Iterator<java.util.Map.Entry<Long, Circle>> it = fenceCircles.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Long, Circle> e = it.next();
            if (!keep.contains(e.getKey())) {
                fenceCircleIds.remove(e.getValue());
                e.getValue().remove();
                it.remove();
            }
        }
    }

    private void cleanupFenceCircles() {
        for (Circle circle : fenceCircles.values()) {
            circle.remove();
        }
        fenceCircles.clear();
        if (fencePreviewCircle != null) {
            fencePreviewCircle.remove();
            fencePreviewCircle = null;
        }
    }

    /** 围栏色随性别：女粉 / 男蓝（与大头针约定一致） */
    private int fenceColor() {
        return prefs.isFemale() ? 0xFFFF6B6B : 0xFF5B8FF9;
    }

    /** 半透明填充色（约 15% 不透明度） */
    private int fenceFillColor(int opaque) {
        return (0x26 << 24) | (opaque & 0xFFFFFF);
    }

    /** 点头像切换工具菜单显示/隐藏（两个头像均可触发） */
    private void toggleToolsMenu() {
        if (viewToolsMenu.getVisibility() == View.VISIBLE) {
            hideToolsMenu();
        } else {
            viewToolsMenu.setAlpha(0f);
            viewToolsMenu.setVisibility(View.VISIBLE);
            viewToolsMenu.animate().alpha(1f).setDuration(150L).start();
        }
    }

    private void hideToolsMenu() {
        if (viewToolsMenu.getVisibility() != View.VISIBLE) return;
        viewToolsMenu.animate().alpha(0f).setDuration(120L)
                .withEndAction(() -> viewToolsMenu.setVisibility(View.GONE)).start();
    }

    /** 「去找他」：聚焦对方位置 */
    private void goToPeer() {
        if (peerMarkers.isEmpty()) {
            Toast.makeText(this, R.string.toast_peer_no_location, Toast.LENGTH_SHORT).show();
            return;
        }
        focusWithIdleGuard();
        Marker m = peerMarkers.values().iterator().next();
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(m.getPosition(), 16f));
        m.showInfoWindow();
    }

    /** 用户主动聚焦后临时开启防抢焦（8 秒内位置更新不拉走视角） */
    private void focusWithIdleGuard() {
        userDraggingMap = true;
        uiHandler.removeCallbacks(dragIdleResetRunnable);
        uiHandler.postDelayed(dragIdleResetRunnable, MAP_IDLE_FOCUS_MS);
    }

    /** 将头像 drawable（矢量）渲染为圆形 Bitmap（圆形裁剪） */
    private android.graphics.Bitmap makeRoundAvatarBitmap(int resId, int sizePx) {
        android.graphics.Bitmap src = android.graphics.Bitmap.createBitmap(
                sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(src);
        android.graphics.drawable.Drawable d =
                androidx.vectordrawable.graphics.drawable.VectorDrawableCompat.create(
                        getResources(), resId, null);
        d.setBounds(0, 0, sizePx, sizePx);
        d.draw(canvas);

        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas oc = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        oc.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, p);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        oc.drawBitmap(src, 0, 0, p);
        return out;
    }

    /** 大头针 marker 图标：圆形头像在上，底部圆润水滴形尾巴指向坐标（与头像留间距） */
    private com.amap.api.maps.model.BitmapDescriptor createPinMarkerIcon(int avatarRes, int pinColor, int headDp) {
        float density = getResources().getDisplayMetrics().density;
        int head = (int) (headDp * density);      // 头像直径
        int tail = (int) (18 * density);          // 水滴尾巴高（含间距）
        int gap = (int) (4 * density);            // 头像与尾巴间距
        int h = head + tail;
        float cx = head / 2f;
        float half = head * 0.22f;                // 尾巴顶半宽（略窄于头像）
        float topY = head + gap;                  // 尾巴从头像下方开始（留间距）
        float tipY = h - 3;                       // 尖端（锚点）

        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                head, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        // 圆润水滴尾巴：顶部微弧 + 两侧曲线平滑收窄到尖
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(cx - half, topY);
        path.quadTo(cx, topY - 3, cx + half, topY);                            // 顶部圆润弧
        path.cubicTo(cx + half, topY + (tipY - topY) * 0.30f,
                cx + half * 0.5f, tipY - 5,
                cx, tipY);                                                     // 右下→尖端
        path.cubicTo(cx - half * 0.5f, tipY - 5,
                cx - half, topY + (tipY - topY) * 0.30f,
                cx - half, topY);                                              // 尖端→左下
        path.close();
        p.setColor(pinColor);
        c.drawPath(path, p);

        // 圆形头像画在上方
        android.graphics.Bitmap av = makeRoundAvatarBitmap(avatarRes, head);
        c.drawBitmap(av, 0, 0, null);
        return com.amap.api.maps.model.BitmapDescriptorFactory.fromBitmap(out);
    }
}
