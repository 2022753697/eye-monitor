package com.eyemonitor.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AnniversaryCacheEntity;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.ChatEntity;
import com.eyemonitor.db.FenceCacheEntity;
import com.eyemonitor.db.LocationCacheEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.util.MapNav;
import com.eyemonitor.ui.MainActivity;
import com.eyemonitor.util.AnniversaryUtils;
import com.eyemonitor.websocket.WSClient;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final String SOS_CHANNEL_ID = CHANNEL_ID + "_sos";
    private static final int NOTIFICATION_ID_FOREGROUND = 1;
    private static final int NOTIFICATION_ID_EVENT = 2;
    private static final int NOTIFICATION_ID_SOS = 3;
    private static final int NOTIFICATION_ID_FENCE = 4;

    public static final String ACTION_EVENT = "com.eyemonitor.EVENT";
    public static final String EXTRA_EVENT_JSON = "event_json";
    // 给 MapActivity 主动请求对方位置用的 action
    public static final String ACTION_REQUEST_PEER_LOCATION = "com.eyemonitor.REQUEST_PEER_LOCATION";
    // 给聊天界面发送聊天消息用的 action
    public static final String ACTION_SEND_CHAT = "com.eyemonitor.SEND_CHAT";
    public static final String EXTRA_CHAT_TEXT = "chat_text";
    public static final String EXTRA_CHAT_FROM = "chat_from";
    public static final String EXTRA_CHAT_REF_ID = "chat_ref_id";
    public static final String EXTRA_CHAT_REF_TEXT = "chat_ref_text";
    public static final String EXTRA_CHAT_TS = "chat_ts";
    public static final String ACTION_SEND_TYPING = "com.eyemonitor.SEND_TYPING";
    public static final String ACTION_SEND_CHAT_READ = "com.eyemonitor.SEND_CHAT_READ";
    public static final String EXTRA_UP_TO_TS = "chat_up_to_ts";
    public static final String ACTION_SEND_CHAT_RECALL = "com.eyemonitor.SEND_CHAT_RECALL";
    public static final String EXTRA_MSG_TS = "chat_msg_ts";
    // 地图打开时主动触发一次本机位置上报（让 selfMarker 尽快创建并聚焦）
    public static final String ACTION_REQUEST_SELF_LOCATION = "com.eyemonitor.REQUEST_SELF_LOCATION";
    // 被顶替下线（单设备登录）：通知 UI 清登录态回登录页
    public static final String ACTION_KICKED = "com.eyemonitor.KICKED";
    // 资料变更后广播 user_profile 给对方
    public static final String ACTION_BROADCAST_PROFILE = "com.eyemonitor.BROADCAST_PROFILE";
    // SOS 紧急求助：发送 / 回执（「我没事」）
    public static final String ACTION_SEND_SOS = "com.eyemonitor.SEND_SOS";
    public static final String EXTRA_SOS_TEXT = "sos_text";
    public static final String ACTION_SEND_SOS_ACK = "com.eyemonitor.SEND_SOS_ACK";
    public static final String EXTRA_SOS_NAV = "sos_nav";
    public static final String EXTRA_PROFILE_NICKNAME = "profile_nickname";
    public static final String EXTRA_PROFILE_AVATAR = "profile_avatar";
    public static final String EXTRA_PROFILE_GENDER = "profile_gender";
    public static final String EXTRA_PROFILE_BIRTHDAY = "profile_birthday";
    public static final String EXTRA_PROFILE_BIO = "profile_bio";
    // 媒体元数据广播（借道 MonitorService 的 WebSocket 发送 createMediaMeta）
    public static final String ACTION_SEND_MEDIA_META = "com.eyemonitor.SEND_MEDIA_META";
    public static final String EXTRA_MEDIA_FILE_ID = "media_file_id";
    public static final String EXTRA_MEDIA_FILE_NAME = "media_file_name";
    public static final String EXTRA_MEDIA_MIME = "media_mime";
    public static final String EXTRA_MEDIA_SIZE = "media_size";
    public static final String EXTRA_MEDIA_DURATION = "media_duration";
    public static final String EXTRA_MEDIA_FROM = "media_from";

    // 静态引用：PairActivity 配对成功后将 WSClient 交给 MonitorService
    private static WSClient sharedWSClient;

    private PrefsManager prefs;
    private WSClient wsClient;

    /** 进程内单例引用（KeepAliveWorker 存活判定用） */
    private static volatile MonitorService instance;

    /** 监控服务是否存活（KeepAliveWorker 周期自检用） */
    public static boolean isRunning() {
        return instance != null;
    }

    /** WebSocket 是否已连接（仅诊断日志；断线重连由 WSClient 指数退避自行处理） */
    public static boolean isWsConnected() {
        MonitorService s = instance;
        return s != null && s.wsClient != null && s.wsClient.isConnected();
    }
    private LocationTracker locationTracker;
    private DeviceStatusTracker deviceStatusTracker;
    private AppUsageTracker appUsageTracker;

    // 围栏进出判定（有状态：进出状态 + 60s 防抖均由 Tracker 维护）
    private FenceEvaluator.Tracker fenceTracker;

    // App 切换发送去重（无障碍与轮询双通道可能重复触发）
    private String lastAppSwitchPkg;
    private long lastAppSwitchTime;
    private static final long APP_SWITCH_DEDUP_MS = 3000;
    /** 纪念日到期提醒：每天检查一次 */
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private NotificationManager notificationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** SOS 未确认重发提醒（每分钟一次，直到对方点「确认」） */
    private String lastSosText;
    private String lastSosLocation;
    private double lastSosLat = Double.NaN;
    private double lastSosLng = Double.NaN;
    private final Runnable sosReminderRunnable = new Runnable() {
        @Override
        public void run() {
            if (lastSosText == null) return;
            Log.i(TAG, "SOS 未确认，1 分钟后重发提醒");
            showSosNotification(lastSosText, lastSosLocation);
            handler.postDelayed(this, 60_000L);
        }
    };
    private volatile boolean accessibilityListenerSet = false;
    private volatile boolean wsInitialized = false;
    private volatile boolean locationStarted = false;
    private boolean wsFromPairActivity = false;

    /** 省电 P1：当前屏态（默认亮屏，onCreate 里用 isInteractive 校正） */
    private boolean screenOn = true;

    /** 省电 P2：息屏期间推迟的历史/媒体同步，亮屏后补一次 */
    private boolean pendingSyncOnScreenOn = false;

    /** 省电 P1：屏态变化接收器（息屏 → 轮询停/定位90s/上报90s；亮屏 → 立即恢复） */
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            handleScreenChange(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
        }
    };

    /** 位置上报限流状态（R1/R2 判定逻辑在 {@link LocationReportRule}，这里只存状态） */
    private long lastLocationReportTs;
    private double lastReportLat;
    private double lastReportLng;

    private final Runnable locationReportRunnable = new Runnable() {
        @Override
        public void run() {
            reportLocation();
            handler.postDelayed(this, screenOn ? 30_000L : 90_000L); // 息屏 90s 上报（P1）
        }
    };

    /** 纪念日到期提醒：每次启动立即检查，之后每 24 小时复查 */
    private final Runnable anniversaryCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkAnniversaryReminders();
            handler.postDelayed(this, DAY_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        instance = this;
        // P1 轻量保活：幂等注册 15 分钟周期自检（重复启动只保留一个周期任务）
        KeepAliveScheduler.schedule(this);
        prefs = new PrefsManager(this);

        // 省电 P1：屏态监听（息屏停轮询/定位降频/心跳降频）
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        screenOn = pm != null && pm.isInteractive();
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenStateReceiver, screenFilter);
        }
        Log.i(TAG, "屏态监听已注册, 初始screenOn=" + screenOn);
        Log.i(TAG, "本机deviceId: " + prefs.getDeviceId() + ", pairCode: " + prefs.getPairCode());
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        accessibilityListenerSet = false;
        // 围栏翻转回调：跑在 dbExecutor 线程，发通知线程安全
        fenceTracker = new FenceEvaluator.Tracker((fence, nowInside, lat, lng) -> {
            String text = getString(nowInside
                    ? R.string.fence_notification_enter
                    : R.string.fence_notification_exit, fence.name);
            Log.i(TAG, "围栏翻转: fence=" + fence.name + ", inside=" + nowInside);
            showFenceNotification(text);
            // 聊天页也要系统提示（持久化，重启/回看历史仍在）
            appendFenceSystemTip(fence.name, nowInside);
        });
    }

    /** 围栏进出：除系统通知外，聊天页插一条居中系统提示并落库（同纪念日提醒模式）
     *  文本以 \u001F 分隔 [时间, 昵称, 动作, 围栏名]——渲染端按段配色，
     *  昵称/围栏名任意字符（空格/「」等）都不会破坏分段 */
    private void appendFenceSystemTip(String fenceName, boolean nowInside) {
        try {
            long now = System.currentTimeMillis();
            String dt = new java.text.SimpleDateFormat("yyyy年M月d日 HH:mm",
                    java.util.Locale.getDefault()).format(new java.util.Date(now));
            String who = prefs.getPeerNickname();
            if (who == null || who.isEmpty()) who = getString(R.string.chat_title_default);
            String action = getString(nowInside
                    ? R.string.fence_chat_action_enter : R.string.fence_chat_action_exit);
            String text = "\u001F" + dt + "\u001F" + who + "\u001F" + action + "\u001F" + fenceName;
            AppDatabase db = AppDatabase.getInstance(this);
            db.chatDao().insert(new ChatEntity("system", text, null, false, now));
            Map<String, Object> payload = new HashMap<>();
            payload.put("text", text);
            WsMessage tip = new WsMessage("system_tip", prefs.getDeviceId(),
                    prefs.getPairCode(), payload, now);
            broadcastEvent(tip);
            Log.i(TAG, "围栏系统提示已落库: " + dt + " " + who + " " + action + "「" + fenceName + "」");
        } catch (Exception ex) {
            Log.w(TAG, "围栏系统提示失败", ex);
        }
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
        sendChat(context, text, from, 0, null, System.currentTimeMillis());
    }

    /** 发送聊天（引用扩展 + 显式时间戳：本地 ts 与服务端存储 ts 同源，撤回/引用按 ts 精确匹配） */
    public static void sendChat(Context context, String text, String from,
                                long refMsgId, String refText, long ts) {
        if (context == null || text == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_SEND_CHAT);
        intent.putExtra(EXTRA_CHAT_TEXT, text);
        intent.putExtra(EXTRA_CHAT_FROM, from);
        intent.putExtra(EXTRA_CHAT_TS, ts);
        if (refMsgId > 0) intent.putExtra(EXTRA_CHAT_REF_ID, refMsgId);
        if (refText != null && !refText.isEmpty()) intent.putExtra(EXTRA_CHAT_REF_TEXT, refText);
        context.startService(intent);
    }

    /** 发送「正在输入…」（ephemeral，防抖由调用方做） */
    public static void sendTyping(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_SEND_TYPING);
        context.startService(intent);
    }

    /** 发送已读回执：upToTs = 已读到的对方消息时间戳（含更早） */
    public static void sendChatRead(Context context, long upToTs) {
        if (context == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_SEND_CHAT_READ);
        intent.putExtra(EXTRA_UP_TO_TS, upToTs);
        context.startService(intent);
    }

    /** 发送撤回指令：msgTs = 被撤回消息时间戳（2 分钟窗口内） */
    public static void sendChatRecall(Context context, long msgTs) {
        if (context == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_SEND_CHAT_RECALL);
        intent.putExtra(EXTRA_MSG_TS, msgTs);
        context.startService(intent);
    }

    /** 发送 SOS 求助（借道 MonitorService 的 WebSocket，附最近定位，无定位则不带） */
    public static void sendSos(Context context, String text) {
        if (context == null || text == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_SEND_SOS);
        intent.putExtra(EXTRA_SOS_TEXT, text);
        context.startService(intent);
    }

    /** 确认 SOS（弹窗路径）：停止每分钟重发+移除通知；导航由 UI 侧用消息内的位置发起 */
    public static void sendSosAck(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_SEND_SOS_ACK);
        intent.putExtra(EXTRA_SOS_NAV, false);
        context.startService(intent);
    }

    /** 地图打开时请求立即上报一次本机位置 */
    public static void sendRequestSelfLocation(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_REQUEST_SELF_LOCATION);
        context.startService(intent);
    }

    /** 上传成功后广播媒体元数据（借道 MonitorService 的 WebSocket，服务器按 pairCode 转发给对方） */
    public static void sendMediaMeta(Context context, String fileId, String fileName, String mime,
                                     long size, long duration, String from) {
        if (context == null || fileId == null) return;
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_SEND_MEDIA_META);
        intent.putExtra(EXTRA_MEDIA_FILE_ID, fileId);
        if (fileName != null) intent.putExtra(EXTRA_MEDIA_FILE_NAME, fileName);
        if (mime != null) intent.putExtra(EXTRA_MEDIA_MIME, mime);
        intent.putExtra(EXTRA_MEDIA_SIZE, size);
        intent.putExtra(EXTRA_MEDIA_DURATION, duration);
        if (from != null) intent.putExtra(EXTRA_MEDIA_FROM, from);
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
            long refId = intent.getLongExtra(EXTRA_CHAT_REF_ID, 0);
            String refText = intent.getStringExtra(EXTRA_CHAT_REF_TEXT);
            long ts = intent.getLongExtra(EXTRA_CHAT_TS, 0);
            boolean ok = false;
            if (text != null && wsClient != null && prefs.getPairCode() != null) {
                WsMessage chat = WsMessage.createChat(prefs.getDeviceId(), prefs.getPairCode(),
                        text, from, refId, refText);
                // 关键：用 UI 层同一时间戳（本地/服务端 ts 同源，撤回与引用才能精确匹配）
                if (ts > 0) chat.setTimestamp(ts);
                Log.i(TAG, "发送聊天消息: " + text);
                ok = wsClient.send(chat);
                if (ok && ts > 0) {
                    // 等服务器 chat_ack 确认送达；超时（断网但 socket 未死等）标未送达
                    final long ackTs = ts;
                    Runnable timeout = () -> {
                        chatAckTimers.remove(ackTs);
                        broadcastChatSendResult(ackTs, false);
                    };
                    chatAckTimers.put(ackTs, timeout);
                    handler.postDelayed(timeout, CHAT_ACK_TIMEOUT_MS);
                }
            } else {
                Log.w(TAG, "聊天发送失败: text=" + text + ", wsClient=" + wsClient
                        + ", paired=" + (prefs.getPairCode() != null));
            }
            if (!ok) {
                // send() 返回 false / 未连接 / 未配对：直接未送达 + 持久化待重发
                broadcastChatSendResult(ts, false);
                markChatSendPending(ts);
            }
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SEND_TYPING.equals(intent.getAction())) {
            if (wsClient != null && prefs.getPairCode() != null) {
                wsClient.send(WsMessage.createTyping(prefs.getDeviceId(), prefs.getPairCode()));
            }
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SEND_CHAT_READ.equals(intent.getAction())) {
            if (wsClient != null && prefs.getPairCode() != null) {
                long upToTs = intent.getLongExtra(EXTRA_UP_TO_TS, System.currentTimeMillis());
                wsClient.send(WsMessage.createChatRead(prefs.getDeviceId(), prefs.getPairCode(), upToTs));
            }
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SEND_CHAT_RECALL.equals(intent.getAction())) {
            if (wsClient != null && prefs.getPairCode() != null) {
                long msgTs = intent.getLongExtra(EXTRA_MSG_TS, 0);
                wsClient.send(WsMessage.createChatRecall(prefs.getDeviceId(), prefs.getPairCode(), msgTs));
            }
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_REQUEST_SELF_LOCATION.equals(intent.getAction())) {
            Log.i(TAG, "地图请求本机位置上报");
            sendLocation();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SEND_SOS.equals(intent.getAction())) {
            String text = intent.getStringExtra(EXTRA_SOS_TEXT);
            Log.i(TAG, "收到 SOS 发送请求");
            sendSosMessage(text);
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SEND_SOS_ACK.equals(intent.getAction())) {
            Log.i(TAG, "收到 SOS 回执发送请求（我没事）");
            handleSosConfirmed(intent);
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SEND_MEDIA_META.equals(intent.getAction())) {
            String fileId = intent.getStringExtra(EXTRA_MEDIA_FILE_ID);
            if (fileId != null && wsClient != null && prefs.getPairCode() != null) {
                long size = intent.getLongExtra(EXTRA_MEDIA_SIZE, 0);
                long duration = intent.getLongExtra(EXTRA_MEDIA_DURATION, 0);
                WsMessage media = WsMessage.createMediaMeta(prefs.getDeviceId(), prefs.getPairCode(),
                        fileId, intent.getStringExtra(EXTRA_MEDIA_FILE_NAME),
                        intent.getStringExtra(EXTRA_MEDIA_MIME), size,
                        duration > 0 ? (double) duration : null,
                        intent.getStringExtra(EXTRA_MEDIA_FROM));
                Log.i(TAG, "发送媒体元数据: fileId=" + fileId);
                wsClient.send(media);
            } else {
                Log.w(TAG, "媒体元数据发送失败: fileId=" + fileId + ", wsClient=" + wsClient
                        + ", paired=" + (prefs.getPairCode() != null));
            }
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
        refreshTrackerMode();
        syncRemarkIfNeeded();
        initLocationTracker();
        initDeviceStatusTracker();

        // 纪念日到期提醒（启动即查 + 每天复查）
        handler.post(anniversaryCheckRunnable);

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        if (instance == this) {
            instance = null;
        }
        handler.removeCallbacks(locationReportRunnable);
        handler.removeCallbacks(anniversaryCheckRunnable);
        handler.removeCallbacks(sosReminderRunnable);
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (Exception ignored) {}
        if (locationTracker != null) locationTracker.stop();
        if (deviceStatusTracker != null) {
            deviceStatusTracker.stop();
            deviceStatusTracker = null;
        }
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
                    if (screenOn) {
                        SyncManager.syncAll(MonitorService.this);
                    } else {
                        pendingSyncOnScreenOn = true; // 息屏不主动拉（P2），亮屏后补
                    }
                    sendDeviceStatus();
                    broadcastPeerOnline();
                    autoResendPending();
                    syncRemarkIfNeeded();
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
                    broadcastPeerOffline();
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
                if (screenOn) {
                    SyncManager.syncAll(MonitorService.this);
                } else {
                    pendingSyncOnScreenOn = true; // 息屏不主动拉（P2），亮屏后补
                }
                sendDeviceStatus();
                broadcastPeerOnline();
                autoResendPending();
                syncRemarkIfNeeded();
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
                broadcastPeerOffline();
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
            refreshTrackerMode(); // 无障碍连上 → 轮询切 60s 兜底档
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
        locationTracker.setScreenOn(screenOn); // 服务启动时若已息屏，直接应用降频档

        handler.postDelayed(locationReportRunnable, 5_000L);
        Log.i(TAG, "位置追踪已启动");
    }

    /** 省电 P1：屏态切换分发（轮询档/定位档/上报节奏 + 亮屏顺刷无障碍档位） */
    private void handleScreenChange(boolean on) {
        if (screenOn == on) return;
        screenOn = on;
        Log.i(TAG, "屏幕状态变化: " + (on ? "亮屏" : "息屏"));
        if (appUsageTracker != null) {
            appUsageTracker.setScreenOn(on);
        }
        if (locationTracker != null) {
            locationTracker.setScreenOn(on);
        }
        if (wsClient != null) {
            wsClient.setScreenOn(on); // 心跳 30s ↔ 120s（P2）
        }
        refreshTrackerMode(); // 亮屏时顺带刷新无障碍 → 轮询档位
        if (on && pendingSyncOnScreenOn) {
            pendingSyncOnScreenOn = false;
            Log.i(TAG, "亮屏，补执行息屏期间推迟的同步");
            SyncManager.syncAll(MonitorService.this);
        }
        if (on) {
            sendDeviceStatus(); // 亮屏追发一次状态快照（省电 P3，去掉固定周期后的保新语义）
        }
        // 上报节奏随屏态：亮屏 5s 内首次即报（追发快照语义）；息屏直接 90s
        handler.removeCallbacks(locationReportRunnable);
        handler.postDelayed(locationReportRunnable, on ? 5_000L : 90_000L);
    }

    /** 备注同步（P1）：启动/重连时 —— 本地无备注且已登录 → 拉取一次（换机/重装恢复）；
     *  有重传标志 → 重传当前本地值（最终一致）。未登录静默本地模式。 */
    private void syncRemarkIfNeeded() {
        try {
            if (prefs.getAccessToken() == null || prefs.getAccessToken().isEmpty()) {
                return; // 未登录：本地模式，不请求
            }
            String local = prefs.getPeerRemark();
            if (local == null || local.isEmpty()) {
                // 本地无备注：尝试从服务器恢复（仅已登录）；失败静默
                AuthManager.i(this).fetchRemark(this, new AuthManager.Callback() {
                    @Override
                    public void onSuccess(com.google.gson.JsonObject data) {
                        String remark = null;
                        if (data != null && data.has("remark") && !data.get("remark").isJsonNull()) {
                            remark = data.get("remark").getAsString();
                        }
                        if (remark != null && !remark.isEmpty()) {
                            prefs.setPeerRemark(remark);
                            Log.i(TAG, "备注已从服务器恢复: " + remark);
                        }
                    }

                    @Override
                    public void onError(int code, String msg) {
                        Log.d(TAG, "备注拉取跳过(code=" + code + "): " + msg);
                    }
                });
            } else if (prefs.isRemarkPendingSync()) {
                // 有重传标志：重传当前本地值（成功由 syncRemark 回调清标志）
                Log.i(TAG, "重传本地备注到服务器");
                AuthManager.syncRemark(this);
            }
        } catch (Exception e) {
            Log.w(TAG, "备注同步异常", e);
        }
    }

    /** 无障碍开关状态 → 轮询档位（已启用→60s 兜底；未启用→5s）。读取失败按未启用。 */
    private void refreshTrackerMode() {
        boolean has = false;
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            has = enabled != null && enabled.contains("com.eyemonitor");
        } catch (Exception e) {
            Log.w(TAG, "读取无障碍设置失败，按未启用处理", e);
        }
        if (appUsageTracker != null) {
            appUsageTracker.setAccessibilityEnabled(has);
        }
        Log.d(TAG, "无障碍状态: " + (has ? "已启用(轮询60s兜底)" : "未启用(轮询5s)"));
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
        long now = System.currentTimeMillis();
        double lat = locationTracker.getLastLat();
        double lng = locationTracker.getLastLng();
        LocationReportRule.Decision decision = LocationReportRule.shouldReport(
                lastLocationReportTs, lastReportLat, lastReportLng, lat, lng, now);
        if (decision == LocationReportRule.Decision.SKIP_STATIC) {
            Log.d(TAG, "位置静止未变(<" + LocationReportRule.SEND_DISTANCE_M + "m)，跳过上报");
            return;
        }
        if (decision == LocationReportRule.Decision.SKIP_RATE_LIMITED) {
            Log.d(TAG, "位置间隔<60s 且位移<200m，跳过上报");
            return;
        }
        sendLocation();
        lastLocationReportTs = now;
        lastReportLat = lat;
        lastReportLng = lng;
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

    // --- 设备状态 ---

    private void initDeviceStatusTracker() {
        if (deviceStatusTracker != null) return;
        deviceStatusTracker = new DeviceStatusTracker(this, status -> {
            Log.d(TAG, "设备状态变化: battery=" + status.battery + ", charging=" + status.charging
                    + ", network=" + status.network + ", bluetooth=" + status.bluetooth);
            if (!screenOn) {
                // 省电 P3：息屏不主动发状态（亮屏快照会携带最新值）
                Log.d(TAG, "息屏变化不主动发送，亮屏快照兜底");
                return;
            }
            sendDeviceStatus();
        });
        Log.i(TAG, "设备状态追踪已启动（变化驱动）");
    }

    /** 定期上报设备状态（复用 30s 位置上报周期） */
    private void reportDeviceStatus() {
        if (deviceStatusTracker == null) return;
        if (wsClient == null || !wsClient.isConnected()) {
            Log.d(TAG, "WebSocket 未连接，跳过设备状态上报");
            return;
        }
        sendDeviceStatus();
    }

    /** 发送当前设备状态（五件套），状态变化/连接恢复/定时周期时调用 */
    private void sendDeviceStatus() {
        if (deviceStatusTracker == null) return;
        if (wsClient == null || !wsClient.isConnected()) {
            Log.w(TAG, "sendDeviceStatus: WebSocket 未连接，跳过");
            return;
        }
        String pairCode = prefs.getPairCode();
        if (pairCode == null || pairCode.isEmpty()) {
            Log.d(TAG, "sendDeviceStatus: 未配对，跳过");
            return;
        }
        DeviceStatusTracker.DeviceStatus s = deviceStatusTracker.getStatus();
        if (s.battery < 0) {
            Log.d(TAG, "sendDeviceStatus: 电池状态未知，跳过");
            return;
        }
        // online 恒为 true：正在发送即代表本机在线；对方在线状态由连接/pair_confirm 驱动
        WsMessage msg = WsMessage.createDeviceStatus(prefs.getDeviceId(), pairCode,
                s.battery, s.charging, s.network, true, s.bluetooth);
        boolean sent = wsClient.send(msg);
        Log.d(TAG, "sendDeviceStatus 发送结果: " + sent);
    }

    /** 收到对方设备状态：本地缓存最新五件套并广播给 UI（顶栏/详情页实时刷新） */
    private void handleDeviceStatus(WsMessage message) {
        Log.i(TAG, "收到设备状态: deviceId=" + message.getDeviceId()
                + ", isSelf=" + (message.getDeviceId() != null && message.getDeviceId().equals(prefs.getDeviceId())));
        java.util.Map<String, Object> payload = message.getPayload();
        if (payload != null) {
            Object battery = payload.get("battery");
            if (battery instanceof Number) prefs.setPeerBattery(((Number) battery).intValue());
            Object charging = payload.get("charging");
            if (charging instanceof Boolean) prefs.setPeerCharging((Boolean) charging);
            Object network = payload.get("network");
            if (network instanceof String) prefs.setPeerNetwork((String) network);
            Object bluetooth = payload.get("bluetooth");
            if (bluetooth instanceof Boolean) prefs.setPeerBluetooth((Boolean) bluetooth);
        }
        broadcastEvent(message);
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
            case "device_status":
                handleDeviceStatus(message);
                break;
            case "chat":
                // 聊天消息：服务层持久化（App 未打开时也不丢记录），再广播给 UI
                saveChatMessage(message);
                broadcastEvent(message);
                break;
            case "typing":
                // 输入中（ephemeral）：直接广播给 UI（3s 超时由 UI 处理）
                broadcastEvent(message);
                break;
            case "chat_read":
                // 对方已读回执：广播给 UI（标记自己消息的已读态）
                broadcastEvent(message);
                break;
            case "chat_recall":
                // 对方撤回：广播给 UI（本地按时间戳标记已撤回）
                broadcastEvent(message);
                break;
            case "chat_ack":
                // 服务器确认收到：取消超时任务、持久化已送达并广播 UI 清除标记
                Object ackTs = message.getPayload() != null ? message.getPayload().get("msgTs") : null;
                if (ackTs instanceof Number) {
                    long ts = ((Number) ackTs).longValue();
                    Runnable pending = chatAckTimers.remove(ts);
                    if (pending != null) {
                        handler.removeCallbacks(pending);
                    }
                    AppDatabase db = AppDatabase.getInstance(this);
                    final long ackTsFinal = ts;
                    AppDatabase.dbExecutor.execute(() -> db.chatDao().markSendSent(ackTsFinal));
                }
                broadcastEvent(message);
                break;
            case "system_tip":
                // 服务端业务提示（如撤回失败）：广播给 UI 渲染为居中系统提示
                broadcastEvent(message);
                break;
            case "request_peer_location":
                handleRequestPeerLocation(message);
                break;
            case "user_profile":
                handleUserProfile(message);
                break;
            case "sos":
                // 对方紧急求助：高优先级通知（含求救语+位置）+ 广播 UI 提供快捷回执
                handleSos(message);
                break;
            case "sos_ack":
                // 对方回执「我没事」：落库 system 提示 + 广播 UI
                handleSosAck(message);
                break;
            case "anniversary_sync":
            case "fence_sync":
            case "folder_sync":
                // 服务器真源 -> 更新本地缓存（Wave-2 功能读缓存）
                SyncManager.handleWsMessage(this, message);
                broadcastEvent(message);
                break;
            case "media":
                // 服务器真源 -> 更新媒体缓存 + 落一条媒体聊天气泡（按 fileId 去重）
                SyncManager.handleWsMessage(this, message);
                saveMediaChat(message);
                broadcastEvent(message);
                break;
            case "media_deleted":
                // 双向同步删除：清缓存行、本地文件与聊天气泡
                SyncManager.handleWsMessage(this, message);
                removeMediaLocal(message);
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

    /** 发送 SOS 求助：附最近定位（无定位则不带 lat/lng） */
    private void sendSosMessage(String text) {
        if (wsClient == null || !wsClient.isConnected()) {
            Log.w(TAG, "SOS 发送失败: WebSocket 未连接");
            return;
        }
        if (prefs.getPairCode() == null || prefs.getPairCode().isEmpty()) {
            Log.w(TAG, "SOS 发送失败: 未配对");
            return;
        }
        Double lat = null;
        Double lng = null;
        if (locationTracker != null && locationTracker.hasLocation()) {
            lat = locationTracker.getLastLat();
            lng = locationTracker.getLastLng();
        }
        WsMessage sos = WsMessage.createSos(prefs.getDeviceId(), prefs.getPairCode(), text, lat, lng);
        boolean sent = wsClient.send(sos);
        Log.i(TAG, "SOS 消息发送结果: " + sent + ", lat=" + lat + ", lng=" + lng);
        if (sent) {
            // 自己侧聊天页系统提示（无位置文案）：你已发送 SOS 求助
            appendSosTip(true, System.currentTimeMillis());
        }
    }

    /** 双方 SOS 系统提示（\u001F 分段：时间/昵称/动作，渲染端配色：时间灰/昵称粉/动作红）
     *  senderSide=true：自己侧「你已发送 SOS 求助」；false：对方侧「昵称 发送了 SOS 求助」。
     *  注意：sendSosMessage 跑在主线程，Room 写入必须走 dbExecutor（否则被吞）。 */
    private void appendSosTip(boolean senderSide, long now) {
        try {
            final String dt = new java.text.SimpleDateFormat("yyyy年M月d日 HH:mm",
                    java.util.Locale.getDefault()).format(new java.util.Date(now));
            String peerNick = prefs.getPeerNickname();
            if (peerNick == null || peerNick.isEmpty()) {
                peerNick = getString(R.string.chat_title_default);
            }
            final String who = senderSide ? getString(R.string.sos_chat_sender_you) : peerNick;
            final String action = getString(senderSide
                    ? R.string.sos_chat_sender_action : R.string.sos_chat_receiver_action);
            final String text = "\u001F" + dt + "\u001F" + who + "\u001F" + action;
            AppDatabase db = AppDatabase.getInstance(this);
            AppDatabase.dbExecutor.execute(() -> {
                try {
                    db.chatDao().insert(new ChatEntity("system", text, null, false, now));
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("text", text);
                    WsMessage tip = new WsMessage("system_tip", prefs.getDeviceId(),
                            prefs.getPairCode(), payload, now);
                    broadcastEvent(tip);
                    Log.i(TAG, "SOS 系统提示已落库: " + dt + " " + who + " " + action);
                } catch (Exception ex) {
                    Log.w(TAG, "SOS 系统提示失败", ex);
                }
            });
        } catch (Exception ex) {
            Log.w(TAG, "SOS 系统提示组装失败", ex);
        }
    }

    /** 对方点「确认」：停止每分钟重发提醒、移除 SOS 通知；
     *  通知按钮路径（EXTRA_SOS_NAV=true）顺带跳转高德导航到发送方位置（弹窗路径由 UI 导航） */
    private void handleSosConfirmed(Intent intent) {
        Log.i(TAG, "SOS 已确认，停止重发提醒");
        handler.removeCallbacks(sosReminderRunnable);
        lastSosText = null;
        lastSosLocation = null;
        notificationManager.cancel(NOTIFICATION_ID_SOS);
        boolean navigate = intent != null && intent.getBooleanExtra(EXTRA_SOS_NAV, false);
        if (navigate && !Double.isNaN(lastSosLat) && !Double.isNaN(lastSosLng)) {
            MapNav.navigate(this, lastSosLat, lastSosLng, prefs.getPeerNickname());
        }
        lastSosLat = Double.NaN;
        lastSosLng = Double.NaN;
    }

    /** 收到对方 SOS：高优先级通知（含求救语+位置）+ 广播 UI */
    private void handleSos(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        Object t = payload != null ? payload.get("text") : null;
        String text = t instanceof String ? (String) t : getString(R.string.sos_help_me);

        Object latObj = payload != null ? payload.get("lat") : null;
        Object lngObj = payload != null ? payload.get("lng") : null;
        String locationText = getString(R.string.sos_no_location);
        if (latObj instanceof Number && lngObj instanceof Number) {
            locationText = String.format(Locale.getDefault(), "%.6f, %.6f",
                    ((Number) latObj).doubleValue(), ((Number) lngObj).doubleValue());
        }

        showSosNotification(text, locationText);
        // 未确认则每分钟重发通知，直到对方点「确认」
        lastSosText = text;
        lastSosLocation = locationText;
        lastSosLat = latObj instanceof Number ? ((Number) latObj).doubleValue() : Double.NaN;
        lastSosLng = lngObj instanceof Number ? ((Number) lngObj).doubleValue() : Double.NaN;
        handler.removeCallbacks(sosReminderRunnable);
        handler.postDelayed(sosReminderRunnable, 60_000L);
        broadcastEvent(message);
        // 对方侧聊天页系统提示（含位置文案）：昵称 发送了 SOS 求助（含位置）
        appendSosTip(false, message.getTimestamp() > 0
                ? message.getTimestamp() : System.currentTimeMillis());
    }

    /** 收到对方回执「我没事」：落库 system 提示 + 广播 UI */
    private void handleSosAck(WsMessage message) {
        long ts = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> db.chatDao().insert(
                new ChatEntity("system", getString(R.string.sos_ack_chat), null, false, ts)));
        broadcastEvent(message);
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
        // 围栏判定只针对对端位置（本机位置走本地定位不上 WS 判定）
        if (message.getDeviceId() != null && !message.getDeviceId().equals(prefs.getDeviceId())) {
            evaluateFences(message);
        }
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

    /**
     * 围栏判定入口：读 Room 围栏缓存 -> 丢给纯逻辑 FenceEvaluator.Tracker，
     * 翻转事件经回调发系统通知。dbExecutor 单线程串行保证 Tracker 线程安全。
     */
    private void evaluateFences(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        if (payload == null) return;
        Object latObj = payload.get("lat");
        Object lngObj = payload.get("lng");
        if (!(latObj instanceof Number) || !(lngObj instanceof Number)) return;
        double lat = ((Number) latObj).doubleValue();
        double lng = ((Number) lngObj).doubleValue();
        if (lat == 0 && lng == 0) return; // 无效占位点
        float accuracy = payload.get("accuracy") instanceof Number
                ? ((Number) payload.get("accuracy")).floatValue() : Float.MAX_VALUE;

        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            java.util.List<FenceCacheEntity> fences = db.cacheDao().getFences();
            java.util.List<FenceEvaluator.Fence> snapshots = new java.util.ArrayList<>();
            for (FenceCacheEntity f : fences) {
                if (!f.enabled) continue;
                snapshots.add(new FenceEvaluator.Fence(f.serverId, f.name, f.lat, f.lng, f.radius));
            }
            fenceTracker.setFences(snapshots);
            int crosses = fenceTracker.evaluate(lat, lng, accuracy);
            if (crosses > 0) {
                Log.i(TAG, "围栏翻转触发通知: " + crosses + " 条, lat=" + lat + ", lng=" + lng + ", accuracy=" + accuracy);
            }
        });
    }

    private void handlePairConfirm(WsMessage message) {
        String code = message.getPairCode();
        if (code != null && !code.isEmpty()) {
            prefs.setPairCode(code);
            Log.i(TAG, "配对成功: code=" + code);
        }
        java.util.Map<String, Object> payload = message.getPayload();
        Object peerOnline = payload != null ? payload.get("peerOnline") : null;
        if (peerOnline instanceof Boolean) {
            prefs.setPeerOnline((Boolean) peerOnline);
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

    /** 收到的媒体消息落一条聊天气泡（kind=media, text=fileId；上传后服务器与发送端可能双份广播，按 fileId 去重） */
    private void saveMediaChat(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        Object fileId = payload != null ? payload.get("fileId") : null;
        if (!(fileId instanceof String) || ((String) fileId).isEmpty()) return;
        String fid = (String) fileId;
        Object f = payload != null ? payload.get("from") : null;
        String from = f instanceof String ? (String) f : null;
        long ts = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            if (db.chatDao().countMediaChat(fid) > 0) return;
            db.chatDao().insert(new ChatEntity("media", fid, from, false, ts));
        });
    }

    /** media_deleted：清理本地媒体聊天气泡与缓存文件（Room 缓存行由 SyncManager 清理） */
    private void removeMediaLocal(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        Object fileId = payload != null ? payload.get("fileId") : null;
        if (!(fileId instanceof String) || ((String) fileId).isEmpty()) return;
        final String fid = (String) fileId;
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            db.chatDao().deleteMediaChat(fid);
            java.io.File f = new java.io.File(new java.io.File(getFilesDir(), "media"), fid);
            if (f.exists()) f.delete();
        });
    }

    /** 持久化收到的聊天消息（服务层，不依赖 Activity 生命周期） */
    private void saveChatMessage(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        Object t = payload != null ? payload.get("text") : null;
        Object f = payload != null ? payload.get("from") : null;
        Object refId = payload != null ? payload.get("refMsgId") : null;
        Object refText = payload != null ? payload.get("refText") : null;
        String text = t instanceof String ? (String) t : null;
        String from = f instanceof String ? (String) f : null;
        if (text == null || text.isEmpty()) return;
        long ts = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        long refMsgId = refId instanceof Number ? ((Number) refId).longValue() : 0L;
        String refTextS = refText instanceof String && !((String) refText).isEmpty()
                ? (String) refText : null;
        ChatEntity e = new ChatEntity("chat", text, from, false, ts, refMsgId, refTextS);
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> db.chatDao().insert(e));
    }

    /**
     * 纪念日到期提醒：遍历 Room 缓存，月日=今天（repeat 按月-日、一次性按完整日期）
     * 且今天未提示过的纪念日，插入一条 system 聊天并广播（复用聊天页居中系统提示渲染）。
     * 去重态存 PrefsManager（日期 + 已提示 serverId 列表），当天只提示一次。
     */
    private void checkAnniversaryReminders() {
        String pairCode = prefs.getPairCode();
        if (pairCode == null || pairCode.isEmpty() || !prefs.isLoggedIn()) return;
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            try {
                List<AnniversaryCacheEntity> all = db.cacheDao().getAnniversaries();
                if (all == null || all.isEmpty()) return;
                Calendar today = Calendar.getInstance();
                String todayStr = AnniversaryUtils.todayString();
                Set<String> reminded = new HashSet<>();
                if (todayStr.equals(prefs.getAnniversaryReminderDate())) {
                    String ids = prefs.getAnniversaryReminderIds();
                    if (ids != null && !ids.isEmpty()) {
                        for (String id : ids.split(",")) {
                            if (!id.trim().isEmpty()) reminded.add(id.trim());
                        }
                    }
                }
                boolean changed = !todayStr.equals(prefs.getAnniversaryReminderDate());
                long now = System.currentTimeMillis();
                for (AnniversaryCacheEntity e : all) {
                    if (!AnniversaryUtils.isAnniversaryToday(e, today)) continue;
                    String idKey = String.valueOf(e.serverId);
                    if (reminded.contains(idKey)) continue;
                    reminded.add(idKey);
                    changed = true;
                    String name = e.name != null && !e.name.isEmpty()
                            ? e.name : getString(R.string.toast_unknown_app);
                    String text = getString(R.string.anniversary_today_chat, name);
                    db.chatDao().insert(new ChatEntity("system", text, null, false, now));
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("text", text);
                    WsMessage tip = new WsMessage("system_tip", prefs.getDeviceId(), pairCode,
                            payload, now);
                    broadcastEvent(tip);
                    Log.i(TAG, "纪念日到期提醒已发送: " + name);
                }
                if (changed) {
                    StringBuilder sb = new StringBuilder();
                    for (String id : reminded) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(id);
                    }
                    prefs.setAnniversaryReminderDate(todayStr);
                    prefs.setAnniversaryReminderIds(sb.toString());
                }
            } catch (Exception ex) {
                Log.w(TAG, "纪念日提醒检查失败", ex);
            }
        });
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

            NotificationChannel sosChannel = new NotificationChannel(
                    SOS_CHANNEL_ID,
                    getString(R.string.sos_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
            );
            sosChannel.setDescription(getString(R.string.sos_channel_desc));
            sosChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                    Notification.AUDIO_ATTRIBUTES_DEFAULT);
            sosChannel.enableVibration(true);
            sosChannel.setVibrationPattern(new long[]{0, 500, 300, 500});
            notificationManager.createNotificationChannel(sosChannel);
        }
    }

    /** SOS 高优先级通知：标题+求救语+位置+强震动，「确认」按钮发回执并停止重发 */
    private void showSosNotification(String sosText, String locationText) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "缺少 POST_NOTIFICATIONS 权限，无法发送 SOS 通知");
                return;
            }
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, (int) System.currentTimeMillis(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 通知上的「确认」按钮：停止重发 + 跳转高德导航到发送方位置
        Intent ack = new Intent(this, MonitorService.class);
        ack.setAction(ACTION_SEND_SOS_ACK);
        ack.putExtra(EXTRA_SOS_NAV, true);
        PendingIntent ackPi = PendingIntent.getService(
                this, (int) System.currentTimeMillis(), ack,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SOS_CHANNEL_ID)
                .setContentTitle(getString(R.string.sos_notification_title))
                .setContentText(getString(R.string.sos_notification_text, sosText, locationText))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(openPi)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(Notification.DEFAULT_SOUND)
                .setVibrate(new long[]{0, 1000, 500, 1000, 500, 1000}) // 强震动：1s 长脉冲 ×3
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .addAction(R.drawable.ic_sos, getString(R.string.sos_ack_action), ackPi)
                .build();

        notificationManager.notify(NOTIFICATION_ID_SOS, notification);
        Log.i(TAG, "SOS 通知已发送; 求救语=" + sosText + ", 位置=" + locationText);
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

    /** 围栏进出系统通知（复用 event 渠道，独立通知 id 避免与普通事件互顶） */
    private void showFenceNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "缺少 POST_NOTIFICATIONS 权限，无法发送围栏通知");
                return;
            }
        }

        Log.i(TAG, "显示围栏通知: " + text);

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, (int) System.currentTimeMillis(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID + "_event")
                .setContentTitle(getString(R.string.fence_notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build();

        notificationManager.notify(NOTIFICATION_ID_FENCE, notification);
        Log.i(TAG, "围栏通知已发送");
    }

    // --- 广播事件给 UI ---

    private void broadcastEvent(WsMessage message) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.putExtra(EXTRA_EVENT_JSON, message.toJson());
        sendBroadcast(intent);
    }

    /** 聊天送达等待（ack 超时 8s 视为未送达）：msgTs -> 超时任务 */
    private static final long CHAT_ACK_TIMEOUT_MS = 8_000L;
    private final java.util.Map<Long, Runnable> chatAckTimers = new java.util.HashMap<>();

    /** WS 连接（重连）后自动补发未送达消息：原 ts/引用原样重发，成功由 ack 落 sent */
    private void autoResendPending() {
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            try {
                List<ChatEntity> pending = db.chatDao().getPendingSelf();
                if (pending.isEmpty()) return;
                for (ChatEntity e : pending) {
                    if (wsClient == null || !wsClient.isConnected()) break;
                    String from = e.fromName != null && !e.fromName.isEmpty()
                            ? e.fromName : prefs.getNickname();
                    WsMessage chat = WsMessage.createChat(prefs.getDeviceId(),
                            prefs.getPairCode(), e.text, from, e.refMsgId, e.refText);
                    if (e.timestamp > 0) chat.setTimestamp(e.timestamp);
                    boolean ok = wsClient.send(chat);
                    Log.i(TAG, "自动补发未送达消息: ts=" + e.timestamp + ", ok=" + ok);
                }
            } catch (Exception ex) {
                Log.w(TAG, "自动补发失败", ex);
            }
        });
    }

    /** 未送达持久标记（重连自动补发依赖） */
    private void markChatSendPending(long msgTs) {
        if (msgTs <= 0) return;
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> db.chatDao().markSendPending(msgTs));
    }

    /** 聊天发送结果回执：ok=false → UI 把该消息标「未送达」（可点击重发） */
    private void broadcastChatSendResult(long msgTs, boolean ok) {
        java.util.Map<String, Object> payload = new HashMap<>();
        payload.put("msgTs", msgTs);
        payload.put("ok", ok);
        WsMessage m = new WsMessage("chat_send_result", prefs.getDeviceId(),
                prefs.getPairCode(), payload, System.currentTimeMillis());
        broadcastEvent(m);
    }

    /** 本地 WS 连接恢复：向 UI 广播对方在线（服务器随后会推送 pair_confirm 修正） */
    private void broadcastPeerOnline() {
        // 本机仍处于“等待对方加入”阶段时，对端并未上线：合成 false，
        // 避免 MainActivity 误把 awaiting 清掉、提前切到聊天页（配对码还没看就不见了）
        boolean online = !prefs.isPairAwaitingPeer();
        broadcastPeerState(online);
    }

    /** 本地 WS 断开：广播对方离线（UI 顶栏置灰，连接恢复前无法获知对方状态） */
    private void broadcastPeerOffline() {
        broadcastPeerState(false);
    }

    private void broadcastPeerState(boolean online) {
        String pairCode = prefs.getPairCode();
        if (pairCode == null || pairCode.isEmpty()) return;
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pairCode", pairCode);
        payload.put("peerOnline", online);
        WsMessage msg = new WsMessage("pair_confirm", prefs.getDeviceId(), pairCode, payload,
                System.currentTimeMillis());
        broadcastEvent(msg);
    }

    // --- 清理 ---

    private void stopWebSocket() {
        if (wsClient != null) {
            wsClient.disconnect();
            wsClient = null;
        }
    }
}
