package com.eyemonitor.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AnniversaryCacheEntity;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.ChatEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.AppUsageTracker;
import com.eyemonitor.service.DeviceStatusTracker;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.util.AccessibilityDiagnostic;
import com.eyemonitor.util.AnniversaryUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主界面（首页）。
 * <p>
 * 未配对：显示配对面板（输入配对码 / 创建配对码）
 * 已配对：显示 QQ 风格聊天界面，对方 App 切换以居中系统提示展示
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    // 配对面板
    private EditText etPairCode;
    private Button btnJoinPair;
    private Button btnCreatePair;

    // 聊天面板
    private View viewPairPanel;
    private View viewChatPanel;
    private TextView tvChatTitle;
    private TextView tvPeerStatus;
    private View btnChatMap;
    private ImageButton btnChatMore;
    private EditText etChatInput;
    private ImageButton btnSend;
    private RecyclerView rvChat;
    private ChatAdapter chatAdapter;
    private View bottomBar;
    private View morePanel;

    // 纪念日倒计时卡片
    private View viewAnniversaryCard;
    private TextView tvAnniversaryCardCountdown;

    private PrefsManager prefs;
    private boolean serviceRunning;

    // SOS 紧急求助：长按 3 秒触发 + 60s 冷却倒计时
    private ImageButton btnSos;
    private TextView tvSosCountdown;
    private static final long SOS_COOLDOWN_MS = 60_000L;
    private static final long SOS_PRESS_HOLD_MS = 3_000L;
    private final Handler sosHandler = new Handler(Looper.getMainLooper());
    private boolean sosPressed;
    private boolean sosFired;
    private boolean sosDialogShowing;

    /** 长按 3 秒到期且手指未抬起时触发 SOS */
    private final Runnable sosPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (sosPressed) {
                sosFired = true;
                triggerSos();
            }
        }
    };

    /** 冷却倒计时：每秒刷新按钮旁剩余秒数，结束后恢复可触发 */
    private final Runnable sosCooldownTicker = new Runnable() {
        @Override
        public void run() {
            long remaining = remainingSosCooldown();
            if (remaining > 0) {
                tvSosCountdown.setText(String.format(Locale.getDefault(), "%d",
                        (remaining + 999) / 1000));
                sosHandler.postDelayed(this, 1000L);
            } else {
                finishSosCooldown();
            }
        }
    };

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(MonitorService.EXTRA_EVENT_JSON);
            if (json != null) {
                WsMessage msg = WsMessage.fromJson(json);
                if (msg != null) {
                    onEventReceived(msg);
                }
            }
        }
    };

    /** 被顶替下线：清登录态回登录页 */
    private final BroadcastReceiver kickedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            runOnUiThread(() -> {
                prefs.clearAuth();
                stopService(new Intent(MainActivity.this, MonitorService.class));
                Toast.makeText(MainActivity.this, R.string.kicked_toast, Toast.LENGTH_LONG).show();
                Intent go = new Intent(MainActivity.this, LoginActivity.class);
                go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(go);
                finish();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new PrefsManager(this);

        // 强制登录：无 token 先去登录页（登录成功再回主界面）
        if (!prefs.isLoggedIn()) {
            Log.d(TAG, "未登录，进入登录页");
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // 配对面板
        viewPairPanel = findViewById(R.id.view_pair_panel);
        etPairCode = findViewById(R.id.et_pair_code);
        btnJoinPair = findViewById(R.id.btn_join_pair);
        btnCreatePair = findViewById(R.id.btn_create_pair);

        // 聊天面板
        viewChatPanel = findViewById(R.id.view_chat_panel);
        tvChatTitle = findViewById(R.id.tv_chat_title);
        tvPeerStatus = findViewById(R.id.tv_peer_status);
        btnChatMap = findViewById(R.id.btn_chat_map);
        btnChatMore = findViewById(R.id.btn_chat_more);
        etChatInput = findViewById(R.id.et_chat_input);
        btnSend = findViewById(R.id.btn_send);
        rvChat = findViewById(R.id.rv_chat);

        // 注册事件广播（兼容 API 24+）
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(eventReceiver, filter);
        }

        // 被顶替下线（单设备登录）
        IntentFilter kickedFilter = new IntentFilter(MonitorService.ACTION_KICKED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(kickedReceiver, kickedFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(kickedReceiver, kickedFilter);
        }

        btnJoinPair.setOnClickListener(v -> joinPair());
        btnCreatePair.setOnClickListener(v -> startActivity(new Intent(this, PairActivity.class)));
        btnChatMap.setOnClickListener(v -> startActivity(new Intent(this, MapActivity.class)));
        btnChatMore.setOnClickListener(v -> toggleMorePanel());
        btnSend.setOnClickListener(v -> sendChatMessage());
        // 顶栏状态行点击进对方设备状态详情页
        tvPeerStatus.setOnClickListener(v -> startActivity(new Intent(this, DeviceStatusActivity.class)));
        setupSosButton();

        // 更多面板：格子绑定
        bottomBar = findViewById(R.id.bottom_bar);
        morePanel = findViewById(R.id.more_panel);
        morePanel.findViewById(R.id.grid_image).setOnClickListener(v -> {
            hideMorePanel();
            Toast.makeText(this, R.string.toast_image_coming_soon, Toast.LENGTH_SHORT).show();
        });
        morePanel.findViewById(R.id.grid_map).setOnClickListener(v -> {
            hideMorePanel();
            startActivity(new Intent(this, MapActivity.class));
        });
        morePanel.findViewById(R.id.grid_nickname).setOnClickListener(v -> {
            hideMorePanel();
            showNicknameDialog();
        });
        morePanel.findViewById(R.id.grid_gender).setOnClickListener(v -> {
            hideMorePanel();
            showGenderDialog();
        });
        morePanel.findViewById(R.id.grid_permissions).setOnClickListener(v -> {
            hideMorePanel();
            openPermissionSettings();
        });
        morePanel.findViewById(R.id.grid_diagnose).setOnClickListener(v -> {
            hideMorePanel();
            runAccessibilityTest();
        });
        morePanel.findViewById(R.id.grid_unpair).setOnClickListener(v -> {
            hideMorePanel();
            showUnpairDialog();
        });
        morePanel.findViewById(R.id.grid_clear).setOnClickListener(v -> {
            hideMorePanel();
            clearChatHistory();
        });
        morePanel.findViewById(R.id.grid_profile).setOnClickListener(v -> {
            hideMorePanel();
            startActivity(new Intent(this, ProfileActivity.class));
        });
        morePanel.findViewById(R.id.grid_track).setOnClickListener(v -> {
            hideMorePanel();
            startActivity(new Intent(this, TrackReplayActivity.class));
        });

        // 发送按钮状态色：无输入灰 / 有输入粉
        updateSendButtonState();
        etChatInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateSendButtonState();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        // 输入法与更多菜单互斥：点击输入框时收起更多面板
        etChatInput.setOnClickListener(v -> hideMorePanel());
        etChatInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) hideMorePanel();
        });

        // 聊天列表
        chatAdapter = new ChatAdapter();
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        rvChat.setAdapter(chatAdapter);

        // 首页纪念日倒计时卡片（点击进入纪念日页）
        viewAnniversaryCard = findViewById(R.id.view_anniversary_card);
        tvAnniversaryCardCountdown = findViewById(R.id.tv_anniversary_card_countdown);
        viewAnniversaryCard.setOnClickListener(v -> startActivity(new Intent(this, AnniversaryActivity.class)));

        switchView(prefs.isPaired());
        checkMonitorPermission();
        requestMissingRuntimePermissions();
    }

    /** 启动时自动请求缺失的运行时权限（通知/定位），已授予的不再弹 */
    private void requestMissingRuntimePermissions() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
        if (!need.isEmpty()) {
            Log.d(TAG, "自动请求权限: " + need);
            requestPermissions(need.toArray(new String[0]), 100);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        switchView(prefs.isPaired());
        // 配对成功后自动启动监控服务（实时检测服务运行状态）
        if (prefs.isPaired() && !isServiceRunning()) {
            startMonitoringService();
        }
        // 已配对则刷新标题与状态
        if (prefs.isPaired()) {
            updateChatHeader();
            refreshPeerStatus();
            loadChatHistory();
            syncSosCooldownUi();
        }
        refreshAnniversaryCard();
    }

    @Override
    protected void onDestroy() {
        sosHandler.removeCallbacks(sosPressRunnable);
        sosHandler.removeCallbacks(sosCooldownTicker);
        unregisterReceiver(eventReceiver);
        try {
            unregisterReceiver(kickedReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    // --- 视图切换 ---

    private void switchView(boolean paired) {
        if (paired) {
            viewPairPanel.setVisibility(View.GONE);
            viewChatPanel.setVisibility(View.VISIBLE);
            updateChatHeader();
            loadChatHistory();
        } else {
            viewPairPanel.setVisibility(View.VISIBLE);
            viewChatPanel.setVisibility(View.GONE);
        }
    }

    /** 刷新聊天头栏：对方昵称 */
    private void updateChatHeader() {
        String peer = prefs.getPeerNickname();
        tvChatTitle.setText(peer != null && !peer.isEmpty()
                ? peer : getString(R.string.chat_title_default));
    }

    /** 刷新聊天头栏状态行：电量% · 充电 · 网络 · 蓝牙 · 在线（离线置灰） */
    private void refreshPeerStatus() {
        int battery = prefs.getPeerBattery();
        String batteryText = battery >= 0
                ? getString(R.string.percent_format, battery) : getString(R.string.status_unknown);
        String chargingText = getString(prefs.getPeerCharging()
                ? R.string.status_charging : R.string.status_not_charging);

        String network = prefs.getPeerNetwork();
        String networkText;
        if (DeviceStatusTracker.NETWORK_WIFI.equals(network)) {
            networkText = getString(R.string.status_network_wifi);
        } else if (DeviceStatusTracker.NETWORK_MOBILE.equals(network)) {
            networkText = getString(R.string.status_network_mobile);
        } else if (DeviceStatusTracker.NETWORK_NONE.equals(network)) {
            networkText = getString(R.string.status_network_none);
        } else {
            networkText = getString(R.string.status_unknown);
        }

        String bluetoothText = getString(prefs.getPeerBluetooth()
                ? R.string.status_bluetooth_on : R.string.status_bluetooth_off);
        boolean online = prefs.getPeerOnline();
        String onlineText = getString(online ? R.string.status_online : R.string.status_offline);

        tvPeerStatus.setText(getString(R.string.peer_status_line,
                batteryText, chargingText, networkText, bluetoothText, onlineText));
        tvPeerStatus.setTextColor(online
                ? getColor(R.color.text_on_primary)
                : getColor(R.color.text_on_primary_muted));
    }

    // --- 监控权限引导 ---

    /** App 启动时检查监控权限：使用情况访问 或 无障碍，都没有则主动引导开启（仅提示一次） */
    private void checkMonitorPermission() {
        if (prefs.isPermissionPrompted()) return;

        boolean hasUsageStats = AppUsageTracker.hasUsageStatsPermission(this);
        boolean hasAccessibility = AccessibilityDiagnostic.isAccessibilityEnabled(this);
        if (hasUsageStats || hasAccessibility) return;

        prefs.setPermissionPrompted(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_monitor_permission_title)
                .setMessage(R.string.dialog_permission_hint_message)
                .setPositiveButton(R.string.btn_open_accessibility, (d, w) ->
                        AccessibilityDiagnostic.openAccessibilitySettings(this))
                .setNegativeButton(R.string.btn_open_usage_stats, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.dialog_monitor_permission_fallback,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    // --- 配对面板逻辑（委托 PairActivity 执行配对） ---

    private void joinPair() {
        String code = etPairCode.getText().toString().trim();
        if (code.isEmpty()) {
            Toast.makeText(this, R.string.pair_input_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (code.length() != 6) {
            Toast.makeText(this, R.string.pair_input_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, PairActivity.class);
        intent.putExtra(PairActivity.EXTRA_PAIR_CODE, code);
        startActivity(intent);
    }

    // --- 聊天逻辑 ---

    private void sendChatMessage() {
        String text = etChatInput.getText().toString().trim();
        if (text.isEmpty()) return;

        String from = prefs.getNickname();
        long now = System.currentTimeMillis();
        chatAdapter.addItem(new ChatItem(TYPE_SELF, text, from, TIME_FORMAT.format(new Date(now)), now));

        // 本地入库（Room 禁止主线程操作，走 dbExecutor）
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> db.chatDao()
                .insert(new ChatEntity("chat", text, from, true, now)));

        // 借道 MonitorService 的 WebSocket 发送
        MonitorService.sendChat(this, text, from);

        etChatInput.setText("");
        hideMorePanel();
        scrollToBottom();
    }

    /** 从本地数据库加载聊天历史 */
    private void loadChatHistory() {
        AppDatabase.dbExecutor.execute(() -> {
            List<ChatEntity> all = AppDatabase.getInstance(MainActivity.this).chatDao().getAll();
            runOnUiThread(() -> {
                chatAdapter.clear();
                for (ChatEntity e : all) {
                    int type = "system".equals(e.kind) ? TYPE_SYSTEM
                            : e.isSelf ? TYPE_SELF : TYPE_PEER;
                    chatAdapter.addItem(new ChatItem(type, e.text, e.fromName,
                            TIME_FORMAT.format(new Date(e.timestamp)), e.timestamp));
                }
                if (chatAdapter.getItemCount() == 0) {
                    chatAdapter.addItem(new ChatItem(TYPE_SYSTEM,
                            getString(R.string.chat_empty), null, "", 0));
                }
                scrollToBottom();
            });
        });
    }

    private void scrollToBottom() {
        rvChat.post(() -> {
            if (chatAdapter.getItemCount() > 0) {
                rvChat.scrollToPosition(chatAdapter.getItemCount() - 1);
            }
        });
    }

    // --- 消息接收 ---

    private void onEventReceived(WsMessage message) {
        switch (message.getType()) {
            case "pair_confirm":
                // peerOnline：服务器在对方上下线时推送；本地 WS 断开/恢复时由 MonitorService 合成广播
                Object peerOnlineObj = message.getPayload() != null
                        ? message.getPayload().get("peerOnline") : null;
                if (peerOnlineObj instanceof Boolean) {
                    prefs.setPeerOnline((Boolean) peerOnlineObj);
                }
                switchView(prefs.isPaired());
                refreshPeerStatus();
                break;
            case "device_status":
                refreshPeerStatus();
                break;
            case "chat":
                handleChatMessage(message);
                break;
            case "app_switch":
                String appName = message.getPayload() != null
                        ? (String) message.getPayload().get("appName") : null;
                appendSystemItem(appName);
                break;
<<<<<<< HEAD
            case "anniversary_sync":
                refreshAnniversaryCard();
                break;
            case "system_tip":
                handleSystemTip(message);
                break;
            case "sos":
                // 聊天内快捷回执：弹窗「我没事」（通知按钮也会发回执）
                showSosIncomingDialog(message);
                break;
            case "sos_ack":
                // 对方已确认安全：居中系统提示
                appendSystemText(getString(R.string.sos_ack_chat));
                break;
            case "error":
                handleError(message);
                break;
        }
    }

    /** 服务器错误处理：配对失效时清空本地配对并引导重新配对 */
    private void handleError(WsMessage message) {
        if (!prefs.isPaired()) return;
        Object msg = message.getPayload() != null ? message.getPayload().get("message") : null;
        String err = msg instanceof String ? (String) msg : "";
        Log.w(TAG, "配对失效: " + err);

        runOnUiThread(() -> {
            Toast.makeText(this,
                    err.isEmpty() ? getString(R.string.pair_disconnected) : err,
                    Toast.LENGTH_LONG).show();
            prefs.clearPairCode();
            stopService(new Intent(this, MonitorService.class));
            AppDatabase.dbExecutor.execute(() ->
                    AppDatabase.getInstance(this).chatDao().clear());
            chatAdapter.clear();
            switchView(false);
        });
    }

    private void handleChatMessage(WsMessage message) {
        Map<String, Object> payload = message.getPayload();
        String text = payload != null && payload.get("text") instanceof String
                ? (String) payload.get("text") : null;
        String from = payload != null && payload.get("from") instanceof String
                ? (String) payload.get("from") : null;
        if (text == null || text.isEmpty()) return;

        // 学习对方昵称并刷新头栏
        if (from != null && !from.isEmpty()) {
            prefs.setPeerNickname(from);
            runOnUiThread(() -> tvChatTitle.setText(from));
        }

        long now = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        chatAdapter.addItem(new ChatItem(TYPE_PEER, text, from,
                TIME_FORMAT.format(new Date(now)), now));
        // 持久化已在 MonitorService（服务层）完成，这里只渲染
        scrollToBottom();
    }

    private void appendSystemItem(String appName) {
        String name = appName != null ? appName : getString(R.string.toast_unknown_app);
        appendSystemText(getString(R.string.chat_peer_opened, name));
    }

    /** 插一条居中系统提示到聊天列表 */
    private void appendSystemText(String text) {
        long now = System.currentTimeMillis();
        chatAdapter.addItem(new ChatItem(TYPE_SYSTEM, text, null,
                TIME_FORMAT.format(new Date(now)), now));
        scrollToBottom();
    }

    /** 刷新首页纪念日倒计时卡片：读 Room 缓存找最近一个，无数据隐藏卡片 */
    private void refreshAnniversaryCard() {
        AppDatabase.dbExecutor.execute(() -> {
            List<AnniversaryCacheEntity> list = AppDatabase.getInstance(MainActivity.this)
                    .cacheDao().getAnniversaries();
            runOnUiThread(() -> {
                AnniversaryCacheEntity nearest = AnniversaryUtils.findNearest(list);
                if (nearest == null || nearest.name == null) {
                    viewAnniversaryCard.setVisibility(View.GONE);
                    return;
                }
                long days = AnniversaryUtils.daysUntilNext(nearest, AnniversaryUtils.today());
                tvAnniversaryCardCountdown.setText(days == 0
                        ? getString(R.string.anniversary_countdown_today, nearest.name)
                        : getString(R.string.anniversary_countdown_days, nearest.name, days));
                viewAnniversaryCard.setVisibility(View.VISIBLE);
            });
        });
    }

    /** 纪念日到期系统提示（服务层已入库 Room，这里只渲染） */
    private void handleSystemTip(WsMessage message) {
        Object text = message.getPayload() != null ? message.getPayload().get("text") : null;
        if (!(text instanceof String) || ((String) text).isEmpty()) return;
        long now = message.getTimestamp() > 0 ? message.getTimestamp() : System.currentTimeMillis();
        chatAdapter.addItem(new ChatItem(TYPE_SYSTEM, (String) text, null,
                TIME_FORMAT.format(new Date(now)), now));
        scrollToBottom();
    }

    /** 系统提示时间：今天显示 HH:mm，更早显示 yyyy-M-d HH:mm（如 2026-9-28 21:38） */
    private String formatSystemTime(long ts) {
        if (ts <= 0) return "";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        Calendar now = Calendar.getInstance();
        boolean today = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        SimpleDateFormat fmt = today
                ? new SimpleDateFormat("HH:mm", Locale.getDefault())
                : new SimpleDateFormat("yyyy-M-d HH:mm", Locale.getDefault());
        return fmt.format(new Date(ts));
    }

    /** 系统提示富文本：时间（今天=HH:mm / 更早=日期+时间）+ 应用名珊瑚色加粗 */
    private CharSequence styleSystemText(ChatItem item) {
        String header = formatSystemTime(item.ts);
        String prefix = getString(R.string.chat_peer_opened_prefix);
        String text = item.text;
        String full = header.isEmpty() ? text : header + " " + text;
        SpannableStringBuilder sb = new SpannableStringBuilder(full);
        if (text != null && text.startsWith(prefix)) {
            int appStart = (header.isEmpty() ? 0 : header.length() + 1) + prefix.length();
            if (appStart < full.length()) {
                sb.setSpan(new ForegroundColorSpan(getColor(R.color.primary)),
                        appStart, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD),
                        appStart, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    /** 发送按钮状态色：无输入灰色，有输入粉色 */
    private void updateSendButtonState() {
        boolean hasText = etChatInput.getText().length() > 0;
        int color = hasText ? getColor(R.color.primary) : getColor(R.color.text_secondary);
        btnSend.setImageTintList(ColorStateList.valueOf(color));
    }

    // --- 更多面板（QQ 风格网格，弹出时顶起输入栏，与输入法互斥） ---

    /** 切换更多面板：输入栏+面板整个底部块一起滑入/滑出，显示时收起输入法并滚到最新消息 */
    private void toggleMorePanel() {
        if (morePanel.getVisibility() == View.VISIBLE) {
            hideMorePanel();
        } else {
            hideKeyboard();
            morePanel.setVisibility(View.VISIBLE);
            bottomBar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom));
            // 最新消息滚到面板上方，不被面板遮挡
            scrollToBottom();
        }
    }

    private void hideMorePanel() {
        if (morePanel.getVisibility() == View.GONE) return;
        Animation out = AnimationUtils.loadAnimation(this, R.anim.slide_out_bottom);
        out.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation a) {}

            @Override
            public void onAnimationEnd(Animation a) {
                morePanel.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation a) {}
        });
        bottomBar.startAnimation(out);
    }

    /** 清空本地聊天记录 */
    private void clearChatHistory() {
        AppDatabase.dbExecutor.execute(() ->
                AppDatabase.getInstance(this).chatDao().clear());
        chatAdapter.clear();
        Toast.makeText(this, R.string.toast_chat_cleared, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etChatInput.getWindowToken(), 0);
        }
    }

    // --- SOS 紧急求助 ---

    /** 绑定 SOS 按钮：长按 3 秒触发（按住期间有效，松手取消） */
    private void setupSosButton() {
        btnSos = findViewById(R.id.btn_sos);
        tvSosCountdown = findViewById(R.id.tv_sos_countdown);
        if (btnSos == null) return;
        btnSos.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sosPressed = true;
                    sosFired = false;
                    v.setPressed(true);
                    sosHandler.removeCallbacks(sosPressRunnable);
                    sosHandler.postDelayed(sosPressRunnable, SOS_PRESS_HOLD_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    sosPressed = false;
                    sosHandler.removeCallbacks(sosPressRunnable);
                    v.setPressed(false);
                    if (sosFired) {
                        sosFired = false;
                        v.performClick();
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    /** 长按 3 秒到期：未配对提示 / 冷却中提示 / 确认弹窗 */
    private void triggerSos() {
        if (!prefs.isPaired()) {
            Toast.makeText(this, R.string.sos_need_pair, Toast.LENGTH_SHORT).show();
            return;
        }
        long remaining = remainingSosCooldown();
        if (remaining > 0) {
            Toast.makeText(this,
                    getString(R.string.sos_cooldown, (remaining + 999) / 1000),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sos_confirm_title)
                .setMessage(R.string.sos_confirm_message)
                .setPositiveButton(R.string.sos_confirm_send, (d, w) -> sendSosNow())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 确认后发送：记录冷却时间（跨重启持久化）→ 借道服务发送 → 进入冷却倒计时 */
    private void sendSosNow() {
        prefs.setSosLastTrigger(System.currentTimeMillis());
        MonitorService.sendSos(this, getString(R.string.sos_help_me));
        Toast.makeText(this, R.string.sos_sent, Toast.LENGTH_SHORT).show();
        startSosCooldown();
    }

    /** 剩余冷却毫秒（0 = 不在冷却） */
    private long remainingSosCooldown() {
        long last = prefs.getSosLastTrigger();
        if (last <= 0) return 0L;
        long remaining = last + SOS_COOLDOWN_MS - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    /** 进入冷却：按钮禁用变灰 + 显示剩余秒数，每秒刷新 */
    private void startSosCooldown() {
        btnSos.setEnabled(false);
        btnSos.setAlpha(0.45f);
        btnSos.setImageTintList(ColorStateList.valueOf(getColor(R.color.text_secondary)));
        tvSosCountdown.setVisibility(View.VISIBLE);
        sosHandler.removeCallbacks(sosCooldownTicker);
        sosHandler.post(sosCooldownTicker);
    }

    /** 冷却结束：恢复按钮可触发并隐藏倒计时 */
    private void finishSosCooldown() {
        sosHandler.removeCallbacks(sosCooldownTicker);
        if (btnSos != null) {
            btnSos.setEnabled(true);
            btnSos.setAlpha(1f);
            btnSos.setImageTintList(ColorStateList.valueOf(getColor(R.color.status_error)));
        }
        tvSosCountdown.setVisibility(View.GONE);
    }

    /** 同步冷却 UI（启动/回到前台时基于持久化状态恢复） */
    private void syncSosCooldownUi() {
        if (btnSos == null) return;
        if (remainingSosCooldown() > 0) {
            startSosCooldown();
        } else {
            finishSosCooldown();
        }
    }

    /** 收到对方 SOS：前台时弹聊天内快捷回执弹窗（后台靠高优先级通知） */
    private void showSosIncomingDialog(WsMessage message) {
        if (isFinishing() || isDestroyed() || !hasWindowFocus() || sosDialogShowing) return;
        java.util.Map<String, Object> payload = message.getPayload();
        Object t = payload != null ? payload.get("text") : null;
        String text = t instanceof String ? (String) t : getString(R.string.sos_help_me);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.sos_notification_title)
                .setMessage(getString(R.string.sos_peer_alert, text))
                .setPositiveButton(R.string.sos_ack_action,
                        (d, w) -> MonitorService.sendSosAck(this))
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(d -> sosDialogShowing = false)
                .create();
        sosDialogShowing = true;
        dialog.show();
    }

    private void openPermissionSettings() {
        if (prefs.isPaired() && isServiceRunning()) {
            startActivity(new Intent(this, MonitorActivity.class));
        } else {
            requestNotificationPermission();
        }
    }

    private void showNicknameDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.dialog_nickname_hint);
        input.setSingleLine(true);
        String current = prefs.getNickname();
        if (current != null) input.setText(current);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_nickname_title)
                .setView(input)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String nick = input.getText().toString().trim();
                    if (!nick.isEmpty()) {
                        prefs.setNickname(nick);
                        Toast.makeText(this, nick, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 设置性别（女=粉 / 男=蓝，地图与头像取色依据） */
    private void showGenderDialog() {
        String[] options = {getString(R.string.gender_female), getString(R.string.gender_male)};
        int checked = prefs.isFemale() ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_gender_title)
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    prefs.setGender(which == 0 ? "female" : "male");
                    d.dismiss();
                    Toast.makeText(this, options[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showUnpairDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_unpair_title)
                .setMessage(R.string.dialog_unpair_message)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    prefs.clearPairCode();
                    stopService(new Intent(this, MonitorService.class));
                    AppDatabase.dbExecutor.execute(() ->
                            AppDatabase.getInstance(this).chatDao().clear());
                    chatAdapter.clear();
                    switchView(false);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // --- 服务与权限（沿用原逻辑） ---

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

    private void startMonitoringService() {
        // 不检查监控权限：配对/聊天/地图不依赖它；监控权限缺失只影响 App 切换检测
        // （AppUsageTracker/无障碍均有权限容错），且启动服务才能完成配对恢复与失效检测。
        Intent intent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        serviceRunning = true;
    }

    private boolean checkPermissions() {
        boolean hasUsageStats = AppUsageTracker.hasUsageStatsPermission(this);
        boolean hasAccessibility = AccessibilityDiagnostic.isAccessibilityEnabled(this);

        Log.d(TAG, "权限检查 - USAGE_STATS: " + hasUsageStats + ", Accessibility: " + hasAccessibility);

        if (!hasUsageStats && !hasAccessibility) {
            showPermissionDialog();
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "缺少通知权限，请求中...");
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
                return false;
            }
        }
        return true;
    }

    private void showPermissionDialog() {
        String diagInfo = AccessibilityDiagnostic.getDiagnosticInfo(this);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_permission_title)
                .setMessage(getString(R.string.dialog_permission_message, diagInfo))
                .setPositiveButton(R.string.go_settings, (dialog, which) -> {
                    AccessibilityDiagnostic.openAccessibilitySettings(MainActivity.this);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(
                                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
            } else {
                Toast.makeText(this, R.string.toast_notification_granted, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 测试无障碍服务是否工作 */
    private void runAccessibilityTest() {
        boolean isEnabled = AccessibilityDiagnostic.isAccessibilityEnabled(this);
        boolean instanceAvailable = AccessibilityDiagnostic.isServiceInstanceAvailable();
        String diagInfo = AccessibilityDiagnostic.getDiagnosticInfo(this);
        String suggestion = AccessibilityDiagnostic.getFixSuggestion(this);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_diag_title)
                .setMessage(getString(R.string.dialog_diag_message, diagInfo, suggestion))
                .setPositiveButton(R.string.go_settings, (dialog, which) ->
                        AccessibilityDiagnostic.openAccessibilitySettings(MainActivity.this))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // --- 数据类与适配器 ---

    private static final int TYPE_SELF = 0;
    private static final int TYPE_PEER = 1;
    private static final int TYPE_SYSTEM = 2;

    public static class ChatItem {
        public final int type;
        public final String text;
        public final String from;
        public final String time;
        public final long ts;

        public ChatItem(int type, String text, String from, String time, long ts) {
            this.type = type;
            this.text = text;
            this.from = from;
            this.time = time;
            this.ts = ts;
        }
    }

    private class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {
        private final List<ChatItem> items = new ArrayList<>();

        void addItem(ChatItem item) {
            items.add(item);
            notifyItemInserted(items.size() - 1);
        }

        void clear() {
            items.clear();
            notifyDataSetChanged();
        }

        int getCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View view;
            switch (viewType) {
                case TYPE_SELF:
                    view = inflater.inflate(R.layout.item_chat_self, parent, false);
                    break;
                case TYPE_PEER:
                    view = inflater.inflate(R.layout.item_chat_peer, parent, false);
                    break;
                default:
                    view = inflater.inflate(R.layout.item_chat_system, parent, false);
                    break;
            }
            return new ViewHolder(view, viewType);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            ChatItem item = items.get(position);
            holder.bind(item);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final int viewType;
            TextView tvText;
            TextView tvTime;
            TextView tvFrom;

            ViewHolder(View view, int viewType) {
                super(view);
                this.viewType = viewType;
                switch (viewType) {
                    case TYPE_SELF:
                        tvText = view.findViewById(R.id.tv_chat_text);
                        tvTime = view.findViewById(R.id.tv_chat_time);
                        break;
                    case TYPE_PEER:
                        tvText = view.findViewById(R.id.tv_chat_text);
                        tvTime = view.findViewById(R.id.tv_chat_time);
                        tvFrom = view.findViewById(R.id.tv_chat_from);
                        break;
                    default:
                        tvText = view.findViewById(R.id.tv_system_text);
                        break;
                }
            }

            void bind(ChatItem item) {
                switch (viewType) {
                    case TYPE_SELF:
                        tvText.setText(item.text);
                        tvTime.setText(item.time);
                        break;
                    case TYPE_PEER:
                        tvText.setText(item.text);
                        tvTime.setText(item.time);
                        String from = item.from;
                        tvFrom.setText(from != null && !from.isEmpty()
                                ? from : getString(R.string.chat_title_default));
                        break;
                    default:
                        tvText.setText(MainActivity.this.styleSystemText(item));
                        break;
                }
            }
        }
    }
}
