package com.eyemonitor.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.ChatEntity;
import com.eyemonitor.db.LocationCacheEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.ui.MainActivity;
import com.eyemonitor.websocket.WSClient;

/**
 * 前台监控服务。
 * <p>
 * 核心协调者，负责：
 * 1. 启动前台服务（常驻通知栏）
 * 2. 初始化 AppAccessibilityService 检测 App 切换
 * 3. 初始化 WSClient 连接服务器
 * 4. 初始化 LocationTracker 上报位置
 * 5. App 切换时通过 WSClient 发送消息
 * 6. 收到消息时发系统通知
 */
public class MonitorService extends Service {

    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "eye_monitor_channel";
    private static final int NOTIFICATION_ID_FOREGROUND = 1;
    private static final int NOTIFICATION_ID_EVENT = 2;

    public static final String ACTION_EVENT = "com.eyemonitor.EVENT";
    public static final String EXTRA_EVENT_JSON = "event_json";
    // 给 MapActivity 主动请求对方位置用的 action
    public static final String ACTION_REQUEST_PEER_LOCATION = "com.eyemonitor.REQUEST_PEER_LOCATION";
    // 给聊天界面发送聊天消息用的 action
    public static final String ACTION_SEND_CHAT = "com.eyemonitor.SEND_CHAT";
    public static final String EXTRA_CHAT_TEXT = "chat_text";
    public static final String EXTRA_CHAT_FROM = "chat_from";
    // 地图打开时主动触发一次本机位置上报（让 selfMarker 尽快创建并聚焦）
    public static final String ACTION_REQUEST_SELF_LOCATION = "com.eyemonitor.REQUEST_SELF_LOCATION";
    // 被顶替下线（单设备登录）：通知 UI 清登录态回登录页
    public static final String ACTION_KICKED = "com.eyemonitor.KICKED";
    // 资料变更后广播 user_profile 给对方
    public static final String ACTION_BROADCAST_PROFILE = "com.eyemonitor.BROADCAST_PROFILE";
    public static final String EXTRA_PROFILE_NICKNAME = "profile_nickname";
    public static final String EXTRA_PROFILE_AVATAR = "profile_avatar";
    public static final String EXTRA_PROFILE_GENDER = "profile_gender";
    public static final String EXTRA_PROFILE_BIRTHDAY = "profile_birthday";
    public static final String EXTRA_PROFILE_BIO = "profile_bio";

    // 静态引用：PairActivity 配对成功后将 WSClient 交给 MonitorService
    private static WSClient sharedWSClient;

    private PrefsManager prefs;
    private WSClient wsClient;
    private LocationTracker locationTracker;
    private AppUsageTracker appUsageTracker;

    // App 切换发送去重（无障碍与轮询双通道可能重复触发）
    private String lastAppSwitchPkg;
    private long lastAppSwitchTime;
    private static final long APP_SWITCH_DEDUP_MS = 3000;
    private NotificationManager notificationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean accessibilityListenerSet = false;
    private volatile boolean wsInitialized = false;
    private volatile boolean locationStarted = false;
    private boolean wsFromPairActivity = false;

    private final Runnable locationReportRunnable = new Runnable() {
        @Override
        public void run() {
            reportLocation();
            handler.postDelayed(this, 30_000L); // 每30秒上报一次
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        prefs = new PrefsManager(this);
        Log.i(TAG, "本机deviceId: " + prefs.getDeviceId() + ", pairCode: " + prefs.getPairCode());
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        accessibilityListenerSet = false;
    }

    public static void sendRequestPeerLocation(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_REQUEST_PEER_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** 发送聊天消息（借道 MonitorService 的 WebSocket 连接） */
    public static void sendChat(Context context, String text, String from) {
        if (context == null || text == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_SEND_CHAT);
        intent.putExtra(EXTRA_CHAT_TEXT, text);
        intent.putExtra(EXTRA_CHAT_FROM, from);
        context.startService(intent);
    }

    /** 地图打开时请求立即上报一次本机位置 */
    public static void sendRequestSelfLocation(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_REQUEST_SELF_LOCATION);
        context.startService(intent);
    }

    /** 资料变更后广播 user_profile 给对方（借道 MonitorService 的 WebSocket） */
    public static void sendProfileUpdate(Context context, String nickname, String avatar,
                                         String gender, String birthday, String bio) {
        if (context == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_BROADCAST_PROFILE);
        if (nickname != null) intent.putExtra(EXTRA_PROFILE_NICKNAME, nickname);
        if (avatar != null) intent.putExtra(EXTRA_PROFILE_AVATAR, avatar);
        if (gender != null) intent.putExtra(EXTRA_PROFILE_GENDER, gender);
        if (birthday != null) intent.putExtra(EXTRA_PROFILE_BIRTHDAY, birthday);
        if (bio != null) intent.putExtra(EXTRA_PROFILE_BIO, bio);
        context.startService(intent);
    }

    /** PairActivity 配对成功后调用，将 WSClient 转移给 MonitorService */
    public static void setSharedWSClient(WSClient client) {
        sharedWSClient = client;
        Log.i(TAG, "WSClient 已共享给 MonitorService, client=" + (client != null ? "not null" : "null"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand, action=" + (intent != null ? intent.getAction() : "null"));

        if (intent != null && ACTION_REQUEST_PEER_LOCATION.equals(intent.getAction())) {
            Log.i(TAG, "收到请求对方位置请求");
            requestPeerLocation();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SEND_CHAT.equals(intent.getAction())) {
            String text = intent.getStringExtra(EXTRA_CHAT_TEXT);
            String from = intent.getStringExtra(EXTRA_CHAT_FROM);
            if (text != null && wsClient != null && prefs.getPairCode() != null) {
                WsMessage chat = WsMessage.createChat(prefs.getDeviceId(), prefs.getPairCode(), text, from);
                Log.i(TAG, "发送聊天消息: " + text);
                wsClient.send(chat);
            } else {
                Log.w(TAG, "聊天发送失败: text=" + text + ", wsClient=" + wsClient + ", paired=" + (prefs.getPairCode() != null));
            }
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_REQUEST_SELF_LOCATION.equals(intent.getAction())) {
            Log.i(TAG, "地图请求本机位置上报");
            sendLocation();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_BROADCAST_PROFILE.equals(intent.getAction())) {
            Log.i(TAG, "收到资料变更广播");
            String nickname = intent.getStringExtra(EXTRA_PROFILE_NICKNAME);
            String avatar = intent.getStringExtra(EXTRA_PROFILE_AVATAR);
            String gender = intent.getStringExtra(EXTRA_PROFILE_GENDER);
            String birthday = intent.getStringExtra(EXTRA_PROFILE_BIRTHDAY);
            String bio = intent.getStringExtra(EXTRA_PROFILE_BIO);
            if (wsClient != null && prefs.getPairCode() != null) {
                WsMessage profileMsg = WsMessage.createUserProfile(prefs.getDeviceId(),
                        prefs.getPairCode(), nickname, avatar, gender, birthday, bio);
                boolean sent = wsClient.send(profileMsg);
                Log.i(TAG, "user_profile 广播发送: " + sent);
            } else {
                Log.w(TAG, "user_profile 广播跳过: wsClient=" + wsClient
                        + ", paired=" + (prefs.getPairCode() != null));
            }
            return START_NOT_STICKY;
        }

        // 复用 PairActivity 传入的 WSClient（配对已成功建立）
        wsFromPairActivity = false;
        if (sharedWSClient != null) {
            wsClient = sharedWSClient;
            sharedWSClient = null; // 转移所有权
            wsFromPairActivity = true;
            Log.i(TAG, "复用 PairActivity 的 WSClient，pairCode=" + prefs.getPairCode());
            wsInitialized = true;
        }

        startForeground(NOTIFICATION_ID_FOREGROUND, createForegroundNotification());

        if (!wsInitialized) {
            initWebSocket();
        } else {
            // 复用模式：wsClient 已存在，initWebSocket 会设置回调并立即触发 onConnected
            accessibilityListenerSet = false;
            initWebSocket();
        }
        initAccessibilityTracker();
        initUsageStatsTracker();
        initLocationTracker();

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        handler.removeCallbacks(locationReportRunnable);
        if (locationTracker != null) locationTracker.stop();
        wsInitialized = false;
        accessibilityListenerSet = false;
        stopAccessibilityTracker();
        if (appUsageTracker != null) {
            appUsageTracker.stop();
            appUsageTracker = null;
        }
        stopWebSocket();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved");
        super.onTaskRemoved(rootIntent);
    }

    private void requestPeerLocation() {
        if (wsClient == null || !wsClient.isConnected()) {
            Log.w(TAG, "WebSocket未连接，无法请求对方位置");
            return;
        }
        if (prefs.getPairCode() == null || prefs.getPairCode().isEmpty()) {
            Log.w(TAG, "未配对，无法请求对方位置");
            return;
        }
        WsMessage req = WsMessage.createRequestPeerLocation(prefs.getDeviceId());
        Log.i(TAG, "主动请求对方位置: " + req.toJson() + ", connected=" + wsClient.isConnected());
        boolean sent = wsClient.send(req);
        Log.i(TAG, "request_peer_location 发送结果: " + sent);
    }

    // --- 初始化 ---

    private void initWebSocket() {
        // 如果 WSClient 已由 PairActivity 传入（配对已建立），复用连接
        if (wsClient != null) {
            Log.i(TAG, "复用已有 WSClient，设置 MonitorService 回调");
            accessibilityListenerSet = false;
            wsClient.setCallback(new WSClient.WsCallback() {
                @Override
                public void onConnected() {
                    Log.i(TAG, "WebSocket 已连接（复用）");
                    String pairCode = prefs.getPairCode();
                    if (pairCode != null && !pairCode.isEmpty()) {
                        Log.d(TAG, "发送 pair_recover: deviceId=" + prefs.getDeviceId() + ", pairCode=" + pairCode);
                        WsMessage recoverReq = WsMessage.createPairRecover(prefs.getDeviceId(), pairCode);
                        wsClient.send(recoverReq);
                    }
                    if (!accessibilityListenerSet && AppAccessibilityService.getInstance() != null) {
                        setupAccessibilityListener();
                    }
                    SyncManager.syncAll(MonitorService.this);
                }

                @Override
                public void onMessage(WsMessage message) {
                    Log.i(TAG, "WS回调收到: type=" + message.getType() + ", deviceId=" + message.getDeviceId()
                            + ", myDeviceId=" + prefs.getDeviceId());
                    handleMessage(message);
                }

                @Override
                public void onDisconnected() {
                    Log.w(TAG, "WebSocket 已断开");
                }

                @Override
                public void onError(String msg) {
                    Log.e(TAG, "WebSocket 错误: " + msg);
                }

                @Override
                public void onKicked(WsMessage message) {
                    handleKicked();
                }

                @Override
                public void onAuthExpired() {
                    handleAuthExpired();
                }
            });
            // 复用连接也确保带上最新 token（无感刷新后热更新）
            wsClient.setAuthToken(prefs.getAccessToken());
            // 如果连接已建立，直接触发回调
            if (wsClient.isConnected()) {
                Log.i(TAG, "WS 已连接，立即触发 onConnected 回调");
                wsClient.getCallback().onConnected();
            }
            return;
        }

        // 无已有连接，创建新连接
        String serverUrl = prefs.getServerUrl();
        Log.d(TAG, "初始化 WebSocket: " + serverUrl + ", deviceId=" + prefs.getDeviceId() + ", pairCode=" + prefs.getPairCode());
        wsClient = new WSClient(serverUrl, new WSClient.WsCallback() {
            @Override
            public void onConnected() {
                Log.i(TAG, "WebSocket 已连接");
                String pairCode = prefs.getPairCode();
                if (pairCode != null && !pairCode.isEmpty()) {
                    Log.d(TAG, "发送 pair_recover: deviceId=" + prefs.getDeviceId() + ", pairCode=" + pairCode);
                    WsMessage recoverReq = WsMessage.createPairRecover(prefs.getDeviceId(), pairCode);
                    wsClient.send(recoverReq);
                } else {
                    Log.i(TAG, "未配对，无需发送恢复请求");
                }
                if (!accessibilityListenerSet && AppAccessibilityService.getInstance() != null) {
                    setupAccessibilityListener();
                }
                SyncManager.syncAll(MonitorService.this);
            }

            @Override
            public void onMessage(WsMessage message) {
                Log.i(TAG, "WS回调收到: type=" + message.getType() + ", deviceId=" + message.getDeviceId()
                        + ", myDeviceId=" + prefs.getDeviceId());
                handleMessage(message);
            }

            @Override
            public void onDisconnected() {
                Log.w(TAG, "WebSocket 已断开");
            }

            @Override
            public void onError(String msg) {
                Log.e(TAG, "WebSocket 错误: " + msg);
            }

            @Override
            public void onKicked(WsMessage message) {
                handleKicked();
            }

            @Override
            public void onAuthExpired() {
                handleAuthExpired();
            }
        });

        wsClient.setAuthToken(prefs.getAccessToken());
        wsClient.connect();
    }

    /** 初始化 UsageStats 轮询追踪器（主方案，不需要无障碍权限） */
    private void initUsageStatsTracker() {
        try {
            appUsageTracker = new AppUsageTracker(this, (packageName, appName) -> onAppSwitched(packageName, appName));
            appUsageTracker.start();
            if (appUsageTracker.hasPermissionIssue()) {
                Log.w(TAG, "UsageStats 追踪器缺少 PACKAGE_USAGE_STATS 权限（用户可在系统设置→使用情况访问中开启）");
            } else {
                Log.i(TAG, "UsageStats 轮询追踪器已启动");
            }
        } catch (Exception e) {
            Log.e(TAG, "初始化 UsageStats 追踪器失败", e);
        }
    }

    /**
     * App 切换统一入口（无障碍 + 轮询双通道）。
     * 3 秒内同一 App 只发送一次，避免重复触发。
     */
    private void onAppSwitched(String packageName, String appName) {
        long now = System.currentTimeMillis();
        if (packageName.equals(lastAppSwitchPkg) && now - lastAppSwitchTime < APP_SWITCH_DEDUP_MS) {
            Log.d(TAG, "App切换去重（3s内同一App）: " + packageName);
            return;
        }
        lastAppSwitchPkg = packageName;
        lastAppSwitchTime = now;

        Log.d(TAG, "App切换回调: " + appName + " (" + packageName + ") wsConnected="
                + (wsClient != null && wsClient.isConnected()) + " pairCode=" + prefs.getPairCode());
        if (wsClient != null && wsClient.isConnected() && prefs.getPairCode() != null) {
            WsMessage msg = WsMessage.createAppSwitch(
                    prefs.getDeviceId(), prefs.getPairCode(),
                    packageName, appName, "OPENED");
            Log.d(TAG, "发送 app_switch: " + msg.toJson());
            wsClient.send(msg);
        } else {
            Log.w(TAG, "无法发送 app_switch: wsClient=" + wsClient
                    + " isConnected=" + (wsClient != null && wsClient.isConnected())
                    + " pairCode=" + prefs.getPairCode());
        }
    }

    private void initAccessibilityTracker() {
        Log.i(TAG, "初始化无障碍追踪器, instance=" + (AppAccessibilityService.getInstance() != null ? "已存在" : "null"));
        Log.i(TAG, "初始化无障碍追踪器, onServiceReady=" + (AppAccessibilityService.getInstance() != null ? "检查中" : "N/A"));

        AppAccessibilityService.setOnServiceReady(() -> {
            Log.i(TAG, "无障碍服务就绪回调触发，准备设置 listener");
            setupAccessibilityListener();
        });
        Log.i(TAG, "已注册 onServiceReady 回调");

        if (AppAccessibilityService.getInstance() != null && !accessibilityListenerSet) {
            Log.d(TAG, "无障碍服务已就绪，立即设置 listener");
            setupAccessibilityListener();
        } else if (AppAccessibilityService.getInstance() == null) {
            Log.e(TAG, "严重错误：无障碍服务实例为 null，服务可能未启用！");
            Log.e(TAG, "请检查：设置 -> 辅助功能 -> 找到'眼互'并开启");
        } else {
            Log.d(TAG, "listener 已设置，跳过重复初始化");
        }
    }

    private void setupAccessibilityListener() {
        if (accessibilityListenerSet) {
            Log.d(TAG, "无障碍 listener 已设置，跳过重复设置");
            return;
        }
        accessibilityListenerSet = true;
        Log.i(TAG, "无障碍 listener 已设置，WebSocket connected=" + (wsClient != null && wsClient.isConnected()));
        AppAccessibilityService.getInstance().setListener(this::onAppSwitched);
    }

    private void stopAccessibilityTracker() {
        accessibilityListenerSet = false;
        if (AppAccessibilityService.getInstance() != null) {
            AppAccessibilityService.getInstance().setListener(null);
        }
    }

    private void initLocationTracker() {
        if (locationStarted) return;
        locationStarted = true;

        locationTracker = new LocationTracker(this);
        locationTracker.start();

        handler.postDelayed(locationReportRunnable, 5_000L);
        Log.i(TAG, "位置追踪已启动");
    }

    /** 定期上报位置 */
    private void reportLocation() {
        if (locationTracker == null || !locationTracker.hasLocation()) {
            Log.d(TAG, "位置数据不可用，跳过上报");
            return;
        }
        if (wsClient == null || !wsClient.isConnected()) {
            Log.w(TAG, "WebSocket 未连接，跳过位置上报");
            return;
        }
        sendLocation();
    }

    /** 发送当前最新位置（不检查hasLocation，用于即时请求响应） */
    private void sendLocation() {
        if (wsClient == null || !wsClient.isConnected()) {
            Log.w(TAG, "sendLocation: WebSocket 未连接，跳过位置发送");
            return;
        }
        if (locationTracker == null) {
            Log.w(TAG, "sendLocation: locationTracker为null，跳过");
            return;
        }
        double lat = locationTracker.getLastLat();
        double lng = locationTracker.getLastLng();
        float accuracy = locationTracker.getLastAccuracy();
        Log.i(TAG, "sendLocation: lat=" + lat + ", lng=" + lng + ", accuracy=" + accuracy + ", hasLocation=" + locationTracker.hasLocation());
        WsMessage locMsg = locationTracker.createLocationMessage(prefs.getDeviceId(), prefs.getPairCode());
        Log.d(TAG, "发送位置: deviceId=" + prefs.getDeviceId() + ", pairCode=" + prefs.getPairCode());
        boolean sent = wsClient.send(locMsg);
        Log.d(TAG, "sendLocation 发送结果: " + sent);
        // 同时广播给本地UI（用于地图显示自己的位置）
        broadcastEvent(locMsg);
    }

    // --- 消息处理 ---

    private void handleMessage(WsMessage message) {
        Log.i(TAG, "=== 收到WS消息: type=" + message.getType() + ", deviceId=" + message.getDeviceId()
                + ", pairCode=" + message.getPairCode() + ", myDeviceId=" + prefs.getDeviceId());
        switch (message.getType()) {
            case "pair_confirm":
                handlePairConfirm(message);
                break;
            case "pair_recover":
                handlePairRecover(message);
                break;
            case "app_switch":
                handleAppSwitch(message);
                break;
            case "location":
                handleLocation(message);
                break;
            case "chat":
                // 聊天消息：服务层持久化（App 未打开时也不丢记录），再广播给 UI
                saveChatMessage(message);
                broadcastEvent(message);
                break;
            case "request_peer_location":
                handleRequestPeerLocation(message);
                break;
            case "user_profile":
                handleUserProfile(message);
                break;
            case "anniversary_sync":
            case "fence_sync":
            case "media":
            case "media_deleted":
                // 服务器真源 -> 更新本地缓存（Wave-2 功能读缓存）
                SyncManager.handleWsMessage(this, message);
                broadcastEvent(message);
                break;
            case "error":
                Log.w(TAG, "服务器错误: " + message.getPayload());
                // 广播给 UI（如配对失效需要清空本地配对并提示重新配对）
                broadcastEvent(message);
                break;
            default:
                Log.d(TAG, "未处理消息类型: " + message.getType());
        }
    }

    /** 被顶替下线：断开 WS、广播 KICKED、停服务（UI 收到后清登录态回登录页） */
    private void handleKicked() {
        Log.w(TAG, "=== 被顶替下线（KICKED）===");
        if (wsClient != null) wsClient.disconnect();
        wsInitialized = false;
        sendBroadcast(new Intent(ACTION_KICKED));
        stopForeground(true);
        stopSelf();
    }

    /** WS 握手鉴权失败：无感刷新 token 后重连；refresh 失败则按下线处理 */
    private void handleAuthExpired() {
        Log.w(TAG, "WS 握手鉴权失败，尝试无感刷新");
        AuthManager.i(this).refresh(this, new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                if (wsClient != null) {
                    wsClient.setAuthToken(prefs.getAccessToken());
                    wsClient.connect();
                }
            }

            @Override
            public void onError(int code, String msg) {
                Log.w(TAG, "无感刷新失败，按下线处理: " + msg);
                handleKicked();
            }
        });
    }

    /** 对方资料变更：更新本地缓存并广播（聊天页头像/昵称/详情刷新） */
    private void handleUserProfile(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        if (payload != null) {
            Object nickname = payload.get("nickname");
            if (nickname instanceof String) prefs.setPeerNickname((String) nickname);
            Object avatar = payload.get("avatar");
            if (avatar instanceof String) prefs.setPeerAvatar((String) avatar);
            Object gender = payload.get("gender");
            if (gender instanceof String) prefs.setPeerGender((String) gender);
            Object birthday = payload.get("birthday");
            if (birthday instanceof String) prefs.setPeerBirthday((String) birthday);
            Object bio = payload.get("bio");
            if (bio instanceof String) prefs.setPeerBio((String) bio);
        }
        broadcastEvent(message);
    }

    private void handleRequestPeerLocation(WsMessage message) {
        Log.i(TAG, "=== 收到request_peer_location ===, deviceId=" + message.getDeviceId()
                + ", hasLocation=" + (locationTracker != null ? locationTracker.hasLocation() : "null")
                + ", lastLat=" + (locationTracker != null ? locationTracker.getLastLat() : 0));
        sendLocation();
    }

    private void handleLocation(WsMessage message) {
        boolean isSelf = message.getDeviceId() != null && message.getDeviceId().equals(prefs.getDeviceId());
        Log.i(TAG, "处理位置消息: deviceId=" + message.getDeviceId() + ", isSelf=" + isSelf);
        // 对端位置：记录对端 deviceId（轨迹回放 device 参数）+ 写入本地轨迹缓存（离线回放数据源）
        if (!isSelf && message.getDeviceId() != null) {
            prefs.setPeerDeviceId(message.getDeviceId());
            cachePeerLocation(message);
        }
        broadcastEvent(message);
        Log.i(TAG, "位置消息已广播");
    }

    /** 对端位置点写入本地轨迹缓存（Room 禁止主线程，走 dbExecutor） */
    private void cachePeerLocation(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        if (payload == null) return;
        Object latObj = payload.get("lat");
        Object lngObj = payload.get("lng");
        if (!(latObj instanceof Number) || !(lngObj instanceof Number)) return;
        double lat = ((Number) latObj).doubleValue();
        double lng = ((Number) lngObj).doubleValue();
        if (lat == 0 && lng == 0) return; // 无效定位点(0,0)不缓存
        LocationCacheEntity e = new LocationCacheEntity();
        e.lat = lat;
        e.lng = lng;
        e.accuracy = payload.get("accuracy") instanceof Number
                ? ((Number) payload.get("accuracy")).floatValue() : 0f;
        e.ts = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> db.cacheDao().insertLocation(e));
    }

    private void handlePairConfirm(WsMessage message) {
        String code = message.getPairCode();
        if (code != null && !code.isEmpty()) {
            prefs.setPairCode(code);
            Log.i(TAG, "配对成功: code=" + code);
        }
        broadcastEvent(message);
    }

    private void handlePairRecover(WsMessage message) {
        handlePairConfirm(message);
        Log.i(TAG, "配对恢复成功");
    }

    private void handleAppSwitch(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        String appName = payload != null && payload.get("appName") instanceof String
                ? (String) payload.get("appName") : "未知应用";

        // 服务层持久化：对方打开记录写入 Room（App 未打开时也不丢，打开后按时间显示）
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> db.chatDao().insert(new ChatEntity(
                "system", getString(R.string.chat_peer_opened, appName), null, false,
                message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis())));

        // 不弹系统通知（需求：只在聊天界面以居中系统提示展示）
        broadcastEvent(message);
    }

    /** 持久化收到的聊天消息（服务层，不依赖 Activity 生命周期） */
    private void saveChatMessage(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        Object t = payload != null ? payload.get("text") : null;
        Object f = payload != null ? payload.get("from") : null;
        String text = t instanceof String ? (String) t : null;
        String from = f instanceof String ? (String) f : null;
        if (text == null || text.isEmpty()) return;
        long ts = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> db.chatDao().insert(
                new ChatEntity("chat", text, from, false, ts)));
    }

    // --- 通知 ---

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "眼互监控",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("眼互监控服务运行状态");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);

            NotificationChannel eventChannel = new NotificationChannel(
                    CHANNEL_ID + "_event",
                    "眼互事件",
                    NotificationManager.IMPORTANCE_HIGH
            );
            eventChannel.setDescription("对方 App 切换通知");
            notificationManager.createNotificationChannel(eventChannel);
        }
    }

    private Notification createForegroundNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("眼互监控中")
                .setContentText("正在监控对方设备状态")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void showAppSwitchNotification(String appName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "缺少 POST_NOTIFICATIONS 权限，无法发送通知");
                return;
            }
        }

        String text = "对方打开了 " + appName;
        Log.i(TAG, "显示通知: " + text);

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, (int) System.currentTimeMillis(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID + "_event")
                .setContentTitle("眼互提醒")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .build();

        notificationManager.notify(NOTIFICATION_ID_EVENT, notification);
        Log.i(TAG, "通知已发送");
    }

    // --- 广播事件给 UI ---

    private void broadcastEvent(WsMessage message) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.putExtra(EXTRA_EVENT_JSON, message.toJson());
        sendBroadcast(intent);
    }

    // --- 清理 ---

    private void stopWebSocket() {
        if (wsClient != null) {
            wsClient.disconnect();
            wsClient = null;
        }
    }
}
