package com.eyemonitor.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.ChatEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.AppUsageTracker;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.util.AccessibilityDiagnostic;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
    private TextView tvStatus;
    private Button btnChatMap;
    private Button btnChatMore;
    private EditText etChatInput;
    private Button btnSend;
    private RecyclerView rvChat;
    private ChatAdapter chatAdapter;

    private PrefsManager prefs;
    private boolean serviceRunning;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new PrefsManager(this);

        // 配对面板
        viewPairPanel = findViewById(R.id.view_pair_panel);
        etPairCode = findViewById(R.id.et_pair_code);
        btnJoinPair = findViewById(R.id.btn_join_pair);
        btnCreatePair = findViewById(R.id.btn_create_pair);

        // 聊天面板
        viewChatPanel = findViewById(R.id.view_chat_panel);
        tvChatTitle = findViewById(R.id.tv_chat_title);
        tvStatus = findViewById(R.id.tv_status);
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

        btnJoinPair.setOnClickListener(v -> joinPair());
        btnCreatePair.setOnClickListener(v -> startActivity(new Intent(this, PairActivity.class)));
        btnChatMap.setOnClickListener(v -> startActivity(new Intent(this, MapActivity.class)));
        btnChatMore.setOnClickListener(v -> showMoreMenu());
        btnSend.setOnClickListener(v -> sendChatMessage());

        // 聊天列表
        chatAdapter = new ChatAdapter();
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        rvChat.setAdapter(chatAdapter);

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
        // 配对成功后自动启动监控服务
        if (prefs.isPaired() && !serviceRunning) {
            startMonitoringService();
        }
        // 已配对则刷新标题与状态
        if (prefs.isPaired()) {
            updateChatHeader();
            loadChatHistory();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(eventReceiver);
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

    /** 刷新聊天头栏：对方昵称 + 服务状态 */
    private void updateChatHeader() {
        String peer = prefs.getPeerNickname();
        tvChatTitle.setText(peer != null && !peer.isEmpty()
                ? peer : getString(R.string.chat_title_default));

        serviceRunning = isServiceRunning();
        tvStatus.setText(serviceRunning ? R.string.status_running : R.string.status_not_running);
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
        chatAdapter.addItem(new ChatItem(TYPE_SELF, text, from, TIME_FORMAT.format(new Date(now))));

        // 本地入库（Room 禁止主线程操作，走 dbExecutor）
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> db.chatDao()
                .insert(new ChatEntity("chat", text, from, true, now)));

        // 借道 MonitorService 的 WebSocket 发送
        MonitorService.sendChat(this, text, from);

        etChatInput.setText("");
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
                            TIME_FORMAT.format(new Date(e.timestamp))));
                }
                if (chatAdapter.getItemCount() == 0) {
                    chatAdapter.addItem(new ChatItem(TYPE_SYSTEM,
                            getString(R.string.chat_empty), null, ""));
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
                switchView(prefs.isPaired());
                break;
            case "chat":
                handleChatMessage(message);
                break;
            case "app_switch":
                String appName = message.getPayload() != null
                        ? (String) message.getPayload().get("appName") : null;
                appendSystemItem(getString(R.string.chat_peer_opened,
                        appName != null ? appName : getString(R.string.toast_unknown_app)));
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
                TIME_FORMAT.format(new Date(now))));
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> db.chatDao()
                .insert(new ChatEntity("chat", text, from, false, now)));
        scrollToBottom();
    }

    private void appendSystemItem(String text) {
        long now = System.currentTimeMillis();
        chatAdapter.addItem(new ChatItem(TYPE_SYSTEM, text, null,
                TIME_FORMAT.format(new Date(now))));
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> db.chatDao()
                .insert(new ChatEntity("system", text, null, false, now)));
        scrollToBottom();
    }

    // --- 头栏「更多」菜单 ---

    private void showMoreMenu() {
        PopupMenu menu = new PopupMenu(this, btnChatMore);
        menu.getMenu().add(0, 1, 0, R.string.menu_map);
        menu.getMenu().add(0, 2, 0, R.string.menu_permissions);
        menu.getMenu().add(0, 3, 0, R.string.menu_diagnose);
        menu.getMenu().add(0, 4, 0, R.string.menu_nickname);
        menu.getMenu().add(0, 5, 0, R.string.menu_unpair);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    startActivity(new Intent(this, MapActivity.class));
                    break;
                case 2:
                    openPermissionSettings();
                    break;
                case 3:
                    runAccessibilityTest();
                    break;
                case 4:
                    showNicknameDialog();
                    break;
                case 5:
                    showUnpairDialog();
                    break;
            }
            return true;
        });
        menu.show();
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

        public ChatItem(int type, String text, String from, String time) {
            this.type = type;
            this.text = text;
            this.from = from;
            this.time = time;
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
                        tvText.setText(item.text);
                        break;
                }
            }
        }
    }
}
