package com.eyemonitor.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.ChatEntity;
import com.eyemonitor.db.MediaCacheEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.AppUsageTracker;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.util.AccessibilityDiagnostic;
import com.eyemonitor.util.MediaUtils;

import com.bumptech.glide.Glide;

import java.io.File;
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

    // 媒体选择器请求码
    private static final int REQ_PICK_MEDIA = 2002;

    // 配对面板
    private EditText etPairCode;
    private Button btnJoinPair;
    private Button btnCreatePair;

    // 聊天面板
    private View viewPairPanel;
    private View viewChatPanel;
    private TextView tvChatTitle;
    private View btnChatMap;
    private View btnChatGallery;
    private ImageButton btnChatMore;
    private EditText etChatInput;
    private ImageButton btnSend;
    private ImageButton btnAddMedia;
    private RecyclerView rvChat;
    private ChatAdapter chatAdapter;
    private View bottomBar;
    private View morePanel;

    /** fileId -> 媒体缓存元数据（聊天气泡渲染/下载状态用，随 loadChatHistory 刷新） */
    private final java.util.Map<String, MediaCacheEntity> mediaByFileId = new java.util.HashMap<>();

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
        btnChatMap = findViewById(R.id.btn_chat_map);
        btnChatGallery = findViewById(R.id.btn_chat_gallery);
        btnChatMore = findViewById(R.id.btn_chat_more);
        etChatInput = findViewById(R.id.et_chat_input);
        btnSend = findViewById(R.id.btn_send);
        btnAddMedia = findViewById(R.id.btn_add_media);
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
        btnChatGallery.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));
        btnChatMore.setOnClickListener(v -> toggleMorePanel());
        btnSend.setOnClickListener(v -> sendChatMessage());
        btnAddMedia.setOnClickListener(v -> pickMedia());

        // 更多面板：格子绑定
        bottomBar = findViewById(R.id.bottom_bar);
        morePanel = findViewById(R.id.more_panel);
        morePanel.findViewById(R.id.grid_image).setOnClickListener(v -> pickMedia());
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
        // 配对成功后自动启动监控服务（实时检测服务运行状态）
        if (prefs.isPaired() && !isServiceRunning()) {
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

    // --- 媒体发送（照片/视频，HTTP 上传 + WS 元数据） ---

    /** 打开系统照片选择器（API 24+ 通用 ACTION_OPEN_DOCUMENT，免存储权限） */
    private void pickMedia() {
        hideMorePanel();
        hideKeyboard();
        if (!prefs.isPaired()) {
            Toast.makeText(this, R.string.media_not_paired, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        try {
            startActivityForResult(intent, REQ_PICK_MEDIA);
        } catch (Exception e) {
            Log.w(TAG, "打开媒体选择器失败", e);
            Toast.makeText(this, R.string.media_pick_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_MEDIA && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) handleMediaPicked(uri);
        }
    }

    /** 校验大小/视频时长后上传（拷贝与时长读取走后台线程，避免大文件阻塞 UI） */
    private void handleMediaPicked(Uri uri) {
        long size = MediaUtils.querySize(this, uri);
        if (size > MediaUtils.MAX_MEDIA_BYTES) {
            Toast.makeText(this, R.string.media_file_too_large,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String name = MediaUtils.queryDisplayName(this, uri);
        String mime = getContentResolver().getType(uri);
        final boolean video = MediaUtils.isVideo(mime);
        final String finalName = name;
        final String finalMime = mime;
        AppDatabase.dbExecutor.execute(() -> {
            // 先拷到缓存文件（上传与后续归档都用它），再后台校验视频时长
            String safe = finalName.length() > 60 ? finalName.substring(finalName.length() - 60) : finalName;
            File tmp = new File(new File(getCacheDir(), "media_send"),
                    System.currentTimeMillis() + "_" + safe.replaceAll("[^a-zA-Z0-9._-]", "_"));
            File dir = tmp.getParentFile();
            if (dir != null) dir.mkdirs();
            if (!MediaUtils.copyUriToFile(MainActivity.this, uri, tmp)) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.media_pick_failed,
                        Toast.LENGTH_SHORT).show());
                return;
            }
            long duration = video ? MediaUtils.queryDurationMs(MainActivity.this, uri) : 0;
            final File file = tmp;
            final long finalDuration = duration;
            runOnUiThread(() -> {
                if (video && (finalDuration < 0 || finalDuration > MediaUtils.MAX_VIDEO_MS)) {
                    file.delete();
                    Toast.makeText(MainActivity.this, R.string.media_video_too_long,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                uploadMedia(file, finalName, finalMime, finalDuration, uri);
            });
        });
    }

    /** 上传到服务器并广播元数据；成功后本地归档 + 聊天气泡立即显示 */
    private void uploadMedia(File file, String name, String mime, long duration, Uri uri) {
        Toast.makeText(this, R.string.media_uploading, Toast.LENGTH_SHORT).show();
        AuthManager.i(this).uploadMedia(this, file, prefs.getPairCode(), new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                final String fileId = data.has("fileId") ? data.get("fileId").getAsString() : null;
                if (fileId == null || fileId.isEmpty()) {
                    file.delete();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.auth_error_response,
                            Toast.LENGTH_SHORT).show());
                    return;
                }
                final long now = System.currentTimeMillis();
                final MediaCacheEntity e = new MediaCacheEntity();
                e.fileId = fileId;
                e.serverFileName = data.has("fileName") && !data.get("fileName").isJsonNull()
                        ? data.get("fileName").getAsString() : name;
                e.mime = data.has("mime") && !data.get("mime").isJsonNull()
                        ? data.get("mime").getAsString() : mime;
                e.size = data.has("size") ? data.get("size").getAsLong() : file.length();
                e.duration = duration;
                e.ts = now;
                AppDatabase db = AppDatabase.getInstance(MainActivity.this);
                AppDatabase.dbExecutor.execute(() -> {
                    // 本地归档 getFilesDir()/media/{fileId}（聊天气泡与图库都从本地文件渲染，后台拷贝）
                    File dst = MediaUtils.localMediaFile(MainActivity.this, fileId);
                    boolean archived = dst.exists() && dst.length() > 0
                            || MediaUtils.copyUriToFile(MainActivity.this, uri, dst);
                    if (!archived) archived = file.renameTo(dst);
                    if (archived) e.localPath = dst.getAbsolutePath();
                    file.delete();
                    db.cacheDao().upsertMedia(e);
                    db.chatDao().insert(new ChatEntity("media", fileId,
                            prefs.getNickname(), true, now));
                    runOnUiThread(() -> {
                        // 借道服务发送 WS 元数据（服务器同时在上传响应里转发，接收端已按 fileId 去重）
                        String from = prefs.getNickname() != null ? prefs.getNickname() : "";
                        MonitorService.sendMediaMeta(MainActivity.this, fileId,
                                e.serverFileName, e.mime, e.size, duration, from);
                        mediaByFileId.put(fileId, e);
                        chatAdapter.addItem(new ChatItem(TYPE_MEDIA_SELF, fileId, from,
                                TIME_FORMAT.format(new Date(now)), now));
                        scrollToBottom();
                        Toast.makeText(MainActivity.this, R.string.media_send_success,
                                Toast.LENGTH_SHORT).show();
                    });
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    file.delete();
                    Toast.makeText(MainActivity.this,
                            getString(R.string.media_upload_failed,
                                    msg != null ? msg : code + ""),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 生成媒体缩略图：视频走 ThumbnailUtils 后台生成，图片走 Glide */
    private void loadThumb(ImageView iv, String path, String mime) {
        if (MediaUtils.isVideo(mime)) {
            final String local = path;
            AppDatabase.dbExecutor.execute(() -> {
                final Bitmap bmp = ThumbnailUtils.createVideoThumbnail(local,
                        MediaStore.Video.Thumbnails.MINI_KIND);
                runOnUiThread(() -> {
                    if (bmp != null) iv.setImageBitmap(bmp);
                    else iv.setImageResource(R.drawable.ic_image);
                });
            });
        } else {
            Glide.with(iv)
                    .load(new File(path))
                    .centerCrop()
                    .placeholder(R.drawable.ic_image)
                    .error(R.drawable.ic_image)
                    .into(iv);
        }
    }

    /** 从本地数据库加载聊天历史（媒体气泡元数据从 media_cache 映射补齐） */
    private void loadChatHistory() {
        AppDatabase.dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(MainActivity.this);
            List<MediaCacheEntity> media = db.cacheDao().getMedia();
            List<ChatEntity> all = db.chatDao().getAll();
            runOnUiThread(() -> {
                mediaByFileId.clear();
                for (MediaCacheEntity m : media) mediaByFileId.put(m.fileId, m);
                chatAdapter.clear();
                for (ChatEntity e : all) {
                    int type;
                    if ("media".equals(e.kind)) {
                        type = e.isSelf ? TYPE_MEDIA_SELF : TYPE_MEDIA_PEER;
                    } else if ("system".equals(e.kind)) {
                        type = TYPE_SYSTEM;
                    } else {
                        type = e.isSelf ? TYPE_SELF : TYPE_PEER;
                    }
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
                switchView(prefs.isPaired());
                break;
            case "chat":
                handleChatMessage(message);
                break;
            case "app_switch":
                String appName = message.getPayload() != null
                        ? (String) message.getPayload().get("appName") : null;
                appendSystemItem(appName);
                break;
            case "media":
            case "media_deleted":
                // 服务层已落库/清理，这里整页重载以渲染媒体气泡或移除被删项
                loadChatHistory();
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
        String text = getString(R.string.chat_peer_opened, name);
        long now = System.currentTimeMillis();
        // 持久化已在 MonitorService（服务层）完成，这里只渲染
        chatAdapter.addItem(new ChatItem(TYPE_SYSTEM, text, null,
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
    private static final int TYPE_MEDIA_SELF = 3;
    private static final int TYPE_MEDIA_PEER = 4;

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
                case TYPE_MEDIA_SELF:
                case TYPE_MEDIA_PEER:
                    view = inflater.inflate(R.layout.item_chat_media, parent, false);
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
            holder.bind(item, position);
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
            // 媒体气泡视图
            LinearLayout llMediaBubble;
            FrameLayout flMediaContainer;
            ImageView ivMediaThumb;
            LinearLayout llMediaPlaceholder;
            TextView tvMediaHint;
            FrameLayout flVideoBadge;

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
                    case TYPE_MEDIA_SELF:
                    case TYPE_MEDIA_PEER:
                        llMediaBubble = view.findViewById(R.id.ll_media_bubble);
                        flMediaContainer = view.findViewById(R.id.fl_media_container);
                        ivMediaThumb = view.findViewById(R.id.iv_media_thumb);
                        llMediaPlaceholder = view.findViewById(R.id.ll_media_placeholder);
                        tvMediaHint = view.findViewById(R.id.tv_media_hint);
                        flVideoBadge = view.findViewById(R.id.fl_video_badge);
                        tvTime = view.findViewById(R.id.tv_chat_time);
                        tvFrom = view.findViewById(R.id.tv_chat_from);
                        break;
                    default:
                        tvText = view.findViewById(R.id.tv_system_text);
                        break;
                }
            }

            void bind(ChatItem item) {
                bind(item, getBindingAdapterPosition());
            }

            void bind(ChatItem item, int position) {
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
                    case TYPE_MEDIA_SELF:
                    case TYPE_MEDIA_PEER:
                        bindMedia(this, item, position);
                        break;
                    default:
                        tvText.setText(MainActivity.this.styleSystemText(item));
                        break;
                }
            }
        }
    }

    // --- 媒体气泡渲染 ---

    /** 媒体气泡：已下载显示缩略图（视频带播放角标），未下载显示点击下载占位 */
    private void bindMedia(ChatAdapter.ViewHolder h, ChatItem item, int position) {
        final boolean self = item.type == TYPE_MEDIA_SELF;
        final int density = (int) getResources().getDisplayMetrics().density;
        final int outerPad = 60 * density;
        final int nearPad = 12 * density;
        int startPad = self ? nearPad : outerPad;
        int endPad = self ? outerPad : nearPad;
        h.llMediaBubble.setGravity(self ? Gravity.END : Gravity.START);
        h.llMediaBubble.setPadding(startPad, 0, endPad, 0);
        h.flMediaContainer.setBackgroundResource(
                self ? R.drawable.bg_bubble_self : R.drawable.bg_bubble_peer);
        h.tvTime.setText(item.time);
        if (self) {
            h.tvFrom.setVisibility(View.GONE);
        } else {
            h.tvFrom.setVisibility(View.VISIBLE);
            String from = item.from;
            h.tvFrom.setText(from != null && !from.isEmpty()
                    ? from : getString(R.string.chat_title_default));
        }

        final String fileId = item.text;
        final MediaCacheEntity meta = mediaByFileId.get(fileId);
        final String mime = meta != null ? meta.mime : null;
        final long duration = meta != null ? meta.duration : 0;
        final boolean video = MediaUtils.isVideo(mime);
        h.flVideoBadge.setVisibility(video ? View.VISIBLE : View.GONE);

        final String localPath = meta != null ? meta.localPath : null;
        final boolean downloaded = localPath != null && new File(localPath).exists();
        if (downloaded) {
            h.ivMediaThumb.setVisibility(View.VISIBLE);
            h.llMediaPlaceholder.setVisibility(View.GONE);
            loadThumb(h.ivMediaThumb, localPath, mime);
        } else {
            h.ivMediaThumb.setVisibility(View.GONE);
            h.llMediaPlaceholder.setVisibility(View.VISIBLE);
            h.tvMediaHint.setText(R.string.media_download_hint);
        }

        h.flMediaContainer.setOnClickListener(v -> {
            if (downloaded) {
                MediaUtils.launchViewer(MainActivity.this, fileId, mime, duration, localPath);
            } else {
                h.tvMediaHint.setText(R.string.media_downloading);
                MediaUtils.openMedia(MainActivity.this, fileId, mime, duration, null,
                        new MediaUtils.MediaCb() {
                            @Override
                            public void onReady(String path) {
                                runOnUiThread(() -> {
                                    MediaCacheEntity m = mediaByFileId.get(fileId);
                                    if (m != null) m.localPath = path;
                                    chatAdapter.notifyItemChanged(position);
                                });
                            }

                            @Override
                            public void onError(int code, String msg) {
                                runOnUiThread(() -> {
                                    h.tvMediaHint.setText(R.string.media_download_hint);
                                    Toast.makeText(MainActivity.this, R.string.media_download_failed,
                                            Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
            }
        });
    }
}
