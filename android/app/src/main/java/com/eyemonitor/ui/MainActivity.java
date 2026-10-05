package com.eyemonitor.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.ThumbnailUtils;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AffectionCacheEntity;
import com.eyemonitor.db.AnniversaryCacheEntity;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.ChatEntity;
import com.eyemonitor.db.TaskEntity;
import com.eyemonitor.db.MediaCacheEntity;
import com.eyemonitor.db.AffectionStateHolder;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.ui.chat.ChatAdapter;
import com.eyemonitor.ui.chat.ChatItem;
import com.eyemonitor.ui.theme.ChatTheme;
import com.eyemonitor.ui.theme.ThemeManager;

import static com.eyemonitor.ui.chat.ChatItem.TYPE_MEDIA_PEER;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_MEDIA_SELF;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_PEER;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_SELF;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_SYSTEM;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_TASK_PEER;
import static com.eyemonitor.ui.chat.ChatItem.TYPE_TASK_SELF;
import com.eyemonitor.util.AffectionUtils;
import com.eyemonitor.service.AppUsageTracker;
import com.eyemonitor.service.DeviceStatusTracker;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.util.AccessibilityDiagnostic;
import com.eyemonitor.util.MapNav;
import com.eyemonitor.util.AnniversaryUtils;
import com.eyemonitor.util.MediaUtils;
import com.eyemonitor.util.PermissionHelper;
import com.eyemonitor.util.Transitions;
import com.eyemonitor.util.UiDialogs;

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
public class MainActivity extends AppCompatActivity implements ChatAdapter.Host {

    private static final String TAG = "MainActivity";
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    // 媒体选择器请求码
    private static final int REQ_PICK_MEDIA = 2002;
    private static final int REQ_VOICE_PERMISSION = 2005;
    /** 外观切换后 recreate，重开更多面板第 2 页（intent 标记，onResume 消费） */
    private static final String EXTRA_REOPEN_MORE_PAGE2 = "reopen_more_page2";

    // 配对面板
    private EditText etPairCode;
    private Button btnJoinPair;
    private Button btnCreatePair;
    private TextView tvPairResult;

    // 内联配对状态（原 PairActivity 逻辑迁移到本页，不再跳独立页面）
    private com.eyemonitor.websocket.WSClient pairWsClient;
    private String pendingPairCode;
    private boolean pendingPairJoin;
    private boolean pairingComplete;
    /** 已创建配对码、等待对方加入：期间停留在配对面板显示码，不切聊天页 */
    private boolean pairAwaitingPeer;
    private final android.os.Handler pairHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // 聊天面板
    private View viewPairPanel;
    private View viewChatPanel;
    private TextView tvChatTitle;
    // 好感度/等级：头栏徽章行 + 主题切换入口（点击进 ProfileActivity 等级详情）
    private View chatHeader;
    private ImageView ivHeaderDecor;
    private View affectionBadgeBar;
    private TextView tvAffectionTitle;
    private ImageView ivChatEmptyState;
    private View btnChatMap;
    private View btnChatGallery;
    private ImageButton btnChatMore;
    private EditText etChatInput;
    private ImageButton btnSend;
    private android.widget.ImageView ivIconMap;
    private android.widget.ImageView ivIconGallery;
    private ImageButton btnMic;
    private ImageButton btnEmoji;
    private TextView tvVoiceBar;
    private View llRecordPanel;
    private TextView tvRecordHint;
    private TextView btnRecordCancel;
    private RecyclerView rvChat;
    private ChatAdapter chatAdapter;
    private View bottomBar;
    private View emojiPanel;
    private GridView emojiGrid;

    // ⑧ 切片重构：控制器（头栏状态 / 纪念日爱心 / 更多面板）
    private com.eyemonitor.ui.chat.PeerStatusHeaderController peerStatusHeader;
    private com.eyemonitor.ui.chat.AnniversaryHeartController anniversaryHeart;
    private com.eyemonitor.ui.chat.ChatMorePanelController morePanelController;
    /** fileId -> 媒体缓存元数据（聊天气泡渲染/下载状态用，随 loadChatHistory 刷新） */
    private final java.util.Map<String, MediaCacheEntity> mediaByFileId = new java.util.HashMap<>();
    /** taskId -> 任务实体（聊天气泡状态徽标数据源，随 loadChatHistory 刷新） */
    private final java.util.Map<String, TaskEntity> taskByTaskId = new java.util.HashMap<>();
    /** WiFi 自动下载去重（同一 fileId 只自动触发一次） */
    private final java.util.Set<String> mediaAutoDownloading = new java.util.HashSet<>();

    // --- P2 聊天增强：引用 / 输入中 / 已读 / 撤回 / 语音 ---
    private long pendingRefMsgId;
    private String pendingRefText;
    private long lastTypingSentTs;
    private long peerUpToTs;
    private LinearLayout tvQuoteStrip;
    private TextView tvQuoteText;
    private TextView tvTypingHint;
    private final Handler chatUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideTypingRunnable = () -> {
        if (tvTypingHint != null) {
            tvTypingHint.animate().alpha(0f).translationY(2 * getResources().getDisplayMetrics().density)
                    .setDuration(120).withEndAction(() -> {
                        if (tvTypingHint != null) tvTypingHint.setVisibility(View.GONE);
                    }).start();
        }
    };
    private MediaPlayer voicePlayer;
    private MediaRecorder voiceRecorder;
    private File voiceFile;
    private long voiceStartTs;
    private Runnable voiceTimeoutRunnable;
    private boolean voiceMode;
    private boolean voiceCancelling;
    /** 语音波形高亮：当前播放语音的波形视图（进度推进用） */
    private WaveformView playingVoiceWave;
    private final Runnable voiceProgressRunnable = new Runnable() {
        @Override
        public void run() {
            MediaPlayer p = voicePlayer;
            if (p == null || playingVoiceWave == null) {
                resetVoiceWave(null);
                return;
            }
            int dur = p.getDuration();
            int pos = p.getCurrentPosition();
            if (dur > 0) {
                playingVoiceWave.setPlayedFraction((float) pos / dur);
            }
            chatUiHandler.postDelayed(this, 100);
        }
    };

    /** 波形高亮管理：切歌/停止时复位上一个视图 */
    private void resetVoiceWave(WaveformView target) {
        if (playingVoiceWave != null && playingVoiceWave != target) {
            playingVoiceWave.reset();
        }
        playingVoiceWave = target;
        chatUiHandler.removeCallbacks(voiceProgressRunnable);
    }
    /** 未送达消息时间戳集合（会话级：loadChatHistory 重载时恢复失败标记，防“重连后自己消失”） */
    private final java.util.Set<Long> failedMsgTs = new java.util.HashSet<>();
    private PopupWindow chatMenuPopup;
    /** 聊天页是否真正在前台（地图等独立页面时 MainActivity 停着但面板可见性仍为 VISIBLE，需此标志兜底） */
    private boolean chatForeground;

    private PrefsManager prefs;
    private boolean serviceRunning;

    // SOS 紧急求助：更多菜单 → 长按面板触发 + 60s 冷却倒计时
    private ImageButton btnSos;
    private AlertDialog sosPanelDialog;
    private static final long SOS_COOLDOWN_MS = 60_000L;
    private static final long SOS_PRESS_HOLD_MS = 3_000L;
    private final Handler sosHandler = new Handler(Looper.getMainLooper());
    private boolean sosPressed;
    private boolean sosFired;
    private boolean sosDialogShowing;

    /** 长按 3 秒到期且手指未抬起时发送 SOS（长按本身即确认，松手取消） */
    private final Runnable sosPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (sosPressed) {
                sosFired = true;
                sendSosNow();
                if (sosPanelDialog != null && sosPanelDialog.isShowing()) {
                    sosPanelDialog.dismiss();
                }
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
                com.eyemonitor.util.Toasts.showLong(MainActivity.this, getString(R.string.kicked_toast));
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
            Transitions.push(this);
            finish();
            return;
        }

        // 配对面板
        viewPairPanel = findViewById(R.id.view_pair_panel);
        etPairCode = findViewById(R.id.et_pair_code);
        btnJoinPair = findViewById(R.id.btn_join_pair);
        btnCreatePair = findViewById(R.id.btn_create_pair);
        tvPairResult = findViewById(R.id.tv_pair_result);

        // 聊天面板
        viewChatPanel = findViewById(R.id.view_chat_panel);
        tvChatTitle = findViewById(R.id.tv_chat_title);
        btnChatMap = findViewById(R.id.btn_chat_map);
        btnChatGallery = findViewById(R.id.btn_chat_gallery);
        btnChatMore = findViewById(R.id.btn_chat_more);
        etChatInput = findViewById(R.id.et_chat_input);
        btnSend = findViewById(R.id.btn_send);
        ivIconMap = findViewById(R.id.iv_icon_map);
        ivIconGallery = findViewById(R.id.iv_icon_gallery);
        btnMic = findViewById(R.id.btn_mic);
        btnEmoji = findViewById(R.id.btn_emoji);
        tvVoiceBar = findViewById(R.id.tv_voice_bar);
        llRecordPanel = findViewById(R.id.ll_record_panel);
        tvRecordHint = findViewById(R.id.tv_record_hint);
        btnRecordCancel = findViewById(R.id.btn_record_cancel);
        rvChat = findViewById(R.id.rv_chat);
        // 好感度/等级 + 主题
        chatHeader = findViewById(R.id.chat_header);
        ivHeaderDecor = findViewById(R.id.iv_header_decor);
        affectionBadgeBar = findViewById(R.id.affection_badge_bar);
        tvAffectionTitle = findViewById(R.id.tv_affection_title);
        ivChatEmptyState = findViewById(R.id.iv_chat_empty_state);

        // 顶栏状态行控制器（⑧ 切片：电量/充电/网络/蓝牙/在线 + 点击进详情页）
        peerStatusHeader = new com.eyemonitor.ui.chat.PeerStatusHeaderController(this, viewChatPanel, prefs);
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
        btnCreatePair.setOnClickListener(v -> createPair());
        btnChatMap.setOnClickListener(v -> {
            startActivity(new Intent(this, MapActivity.class));
            Transitions.push(this);
        });
        btnChatGallery.setOnClickListener(v -> {
            startActivity(new Intent(this, GalleryActivity.class));
            Transitions.push(this);
        });
        btnChatMore.setOnClickListener(v -> morePanelController.toggle());
        btnSend.setOnClickListener(v -> sendChatMessage());
        // 好感度：头栏徽章行点击进资料页等级详情
        affectionBadgeBar.setOnClickListener(v -> {
            startActivity(new Intent(this, ProfileActivity.class));
            Transitions.push(this);
        });
        // 标题（对方名称）：双击弹备注编辑（微信式）
        final android.view.GestureDetector titleGesture = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(android.view.MotionEvent e) {
                        showPeerRemarkEditor();
                        return true;
                    }
                });
        tvChatTitle.setOnTouchListener((v, event) -> titleGesture.onTouchEvent(event));
        // E4 点缀：长按标题彩蛋（与点击改名并存，500ms 阈值）
        tvChatTitle.setOnLongClickListener(v -> {
            com.eyemonitor.util.Toasts.showRes(this, R.string.chat_title_easter);
            return true;
        });
        btnMic.setOnClickListener(v -> toggleVoiceMode());
        btnEmoji.setOnClickListener(v -> toggleEmojiPanel());
        // 微信式按住说话：按下录音 → 松开发送 / 滑到取消按钮释放 = 放弃
        tvVoiceBar.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    voiceCancelling = false;
                    startVoiceRecord();
                    showRecordPanel();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateVoiceCancelState(ev.getRawX(), ev.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    hideRecordPanel();
                    finishVoiceRecord(!voiceCancelling);
                    tvVoiceBar.setText(R.string.voice_hold_hint);
                    return true;
            }
            return false;
        });

        tvQuoteStrip = findViewById(R.id.ll_quote_strip);
        tvQuoteText = findViewById(R.id.tv_quote_text);
        findViewById(R.id.btn_quote_cancel).setOnClickListener(v -> clearPendingQuote());
        tvTypingHint = findViewById(R.id.tv_typing_hint);

        // P2：输入即发 typing（防抖 2s），对方在线显示「正在输入…」
        etChatInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (s == null || s.toString().trim().isEmpty()) return;
                long now = System.currentTimeMillis();
                if (now - lastTypingSentTs > 2000) {
                    lastTypingSentTs = now;
                    MonitorService.sendTyping(MainActivity.this);
                }
            }
        });

        // 更多面板控制器（⑧ 切片：分页网格/指示点/显隐动画/清空聊天）
        morePanelController = new com.eyemonitor.ui.chat.ChatMorePanelController(this, viewChatPanel,
                new com.eyemonitor.ui.chat.ChatMorePanelController.Actions() {
                    @Override public void onPickMedia() { pickMedia(); }
                    @Override public void onShowSos() { showSosPanel(); }
                    @Override public void onOpenPermissions() { openPermissionSettings(); }
                    @Override public void onOpenTask() {
                        startActivity(new Intent(MainActivity.this, TaskActivity.class));
                        Transitions.push(MainActivity.this);
                    }
                    @Override public void onOpenMemo() {
                        startActivity(new Intent(MainActivity.this, MemoListActivity.class));
                        Transitions.push(MainActivity.this);
                    }
                    @Override public void onShowUnpairDialog() { showUnpairDialog(); }
                    @Override public void onOpenProfile() {
                        startActivity(new Intent(MainActivity.this, ProfileActivity.class));
                        Transitions.push(MainActivity.this);
                    }
                    @Override public void onOpenTrack() {
                        startActivity(new Intent(MainActivity.this, TrackReplayActivity.class));
                        Transitions.push(MainActivity.this);
                    }
                    @Override public void onClearChat() { clearChatHistory(); }
                    @Override public void onCheckIn() { doCheckIn(); }
                    @Override public void onOpenAnniversary() {
                        startActivity(new Intent(MainActivity.this, AnniversaryActivity.class));
                        Transitions.push(MainActivity.this);
                    }
                    @Override public void onScrollToBottom() { scrollToBottom(); }
                    @Override public void onHideEmojiPanelInstant() { hideEmojiPanelInstant(); }
                    @Override public void onHideKeyboard() { hideKeyboard(); }
                    @Override public void onChangeAppearance() { cycleAppearance(); }
                    @Override public void onOpenSearch() {
                        startActivity(new Intent(MainActivity.this, SearchActivity.class));
                        Transitions.push(MainActivity.this);
                    }
                });
        // emoji 面板（微信式底部上滑）：初始化 8 列网格
        emojiPanel = findViewById(R.id.emoji_panel);
        emojiGrid = findViewById(R.id.grid_emoji);
        initEmojiGrid();
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
        etChatInput.setOnClickListener(v -> {
            morePanelController.hide();
            hideEmojiPanel();
        });
        etChatInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) morePanelController.hide();
        });

        // 聊天列表
        chatAdapter = new ChatAdapter(this);
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        rvChat.setAdapter(chatAdapter);

        // 纪念日爱心控制器（⑧ 切片：轮播/心跳/手势/点击进纪念日页）
        anniversaryHeart = new com.eyemonitor.ui.chat.AnniversaryHeartController(this, viewChatPanel);

        switchView(prefs.isPaired() && !isPairAwaitingPeer());
        // 主题即时生效（头栏/背景/输入区/发送键/空态）
        applyTheme();
        // 权限流程：批次1安全集合 → 后台定位单独批次 → 监控设置引导（批次后弹，避免非法组合被系统终止）
        PermissionHelper.startEntryPermissionFlow(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSosFromNotificationIntent(intent);
        // Phase 5：搜索结果点击定位到消息（SearchActivity → EXTRA_TARGET_TS）
        if (intent != null && intent.hasExtra(SearchActivity.EXTRA_TARGET_TS)) {
            long target = intent.getLongExtra(SearchActivity.EXTRA_TARGET_TS, 0);
            if (target > 0) {
                loadChatHistory();
                chatUiHandler.postDelayed(() -> scrollToRef(target), 300);
            }
            intent.removeExtra(SearchActivity.EXTRA_TARGET_TS);
        }
    }

    /** 点 SOS 通知进入：延迟到窗口聚焦后弹回执弹窗（带 SOS 参数才处理） */
    private void handleSosFromNotificationIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(MonitorService.EXTRA_SOS_TEXT)) return;
        final String text = intent.getStringExtra(MonitorService.EXTRA_SOS_TEXT);
        if (text == null) return;
        // 清掉参数，避免每次 onResume 重复弹
        intent.removeExtra(MonitorService.EXTRA_SOS_TEXT);
        final double lat = intent.getDoubleExtra(MonitorService.EXTRA_SOS_LAT, Double.NaN);
        final double lng = intent.getDoubleExtra(MonitorService.EXTRA_SOS_LNG, Double.NaN);
        sosHandler.postDelayed(() -> showSosIncomingDialog(text, lat, lng), 300L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        chatForeground = true;
        handleSosFromNotificationIntent(getIntent());
        // 外观切换后 recreate：重开更多面板第 2 页（一次性标记，消费后清除）
        if (getIntent().getBooleanExtra(EXTRA_REOPEN_MORE_PAGE2, false)) {
            getIntent().removeExtra(EXTRA_REOPEN_MORE_PAGE2);
            morePanelController.reopenOnSecondPage();
        }
        // 从监控设置页（使用情况/无障碍）返回：复查仍缺则再提醒一次
        PermissionHelper.guideIfReturnedFromSettings(this);
        switchView(prefs.isPaired() && !isPairAwaitingPeer());
        // 配对成功后自动启动监控服务（实时检测服务运行状态）
        if (prefs.isPaired() && !isServiceRunning()) {
            startMonitoringService();
        }
        // 省电 P3：白名单引导（已配对才提示；独立于服务启动时机——保活先起服务也不哑火）
        if (prefs.isPaired()) {
            maybePromptBatteryOptimization();
        }
        // 已配对则刷新标题与状态
        if (prefs.isPaired()) {
            updateChatHeader();
            peerStatusHeader.refresh();
            loadChatHistory();
            // 好感度：进聊天页兜底拉取 + 头栏徽章刷新
            fetchAffection();
            refreshAffectionHeader();
            applyTheme();
        }
        anniversaryHeart.refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开前台（如去地图/切后台）：已读回执不再发送，直到回到聊天页
        chatForeground = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 权限流程后继步骤：批次1结束→后台定位批次；批次2结束→监控设置引导
        PermissionHelper.onEntryFlowStep(this, requestCode);
        if (requestCode == REQ_VOICE_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.voice_permission_granted);
        }
    }

    @Override
    protected void onDestroy() {
        chatUiHandler.removeCallbacks(hideTypingRunnable);
        chatUiHandler.removeCallbacks(voiceTimeoutRunnable);
        if (voicePlayer != null) {
            voicePlayer.release();
            voicePlayer = null;
        }
        sosHandler.removeCallbacks(sosPressRunnable);
        try {
            unregisterReceiver(eventReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(kickedReceiver);
        } catch (Exception ignored) {}
        // 配对未完成时断开配对 WS（配对成功后已移交 MonitorService，勿断开）
        if (!pairingComplete) {
            disconnectPairWs();
        }
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

    /** 备注编辑弹窗共用入口（头栏双击 / 气泡名双击 / 状态页行） */
    private void showPeerRemarkEditor() {
        UiDialogs.showRemarkDialog(this, prefs, () -> {
            updateChatHeader();
            loadChatHistory();
            AuthManager.syncRemark(this); // 本地已写入，异步上传服务器（失败置重传标志）
        });
    }

    /** 刷新聊天头栏：对方昵称 */
    private void updateChatHeader() {
        String peer = prefs.getPeerNickname();
        tvChatTitle.setText(peer != null && !peer.isEmpty()
                ? peer : getString(R.string.chat_title_default));
    }

    // --- 好感度/等级：头栏徽章 + 主题 + 打卡 + 升级仪式 ---

    /** 头栏徽章：读 Room 缓存（dbExecutor，禁主线程），无数据隐藏 */
    private void refreshAffectionHeader() {
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            final AffectionCacheEntity e = db.cacheDao().getAffection();
            runOnUiThread(() -> {
                if (affectionBadgeBar == null || tvAffectionTitle == null) {
                    return;
                }
                if (e == null || e.level <= 0) {
                    affectionBadgeBar.setVisibility(View.GONE);
                    return;
                }
                affectionBadgeBar.setVisibility(View.VISIBLE);
                String title = e.title != null && !e.title.isEmpty() ? e.title : "";
                tvAffectionTitle.setText(title.isEmpty()
                        ? getString(R.string.affection_badge_title_short, e.level)
                        : getString(R.string.affection_badge_title, e.level, title));
            });
        });
    }

    /** GET /affection 兜底拉取（App 打开/进聊天页）：写 Room 缓存 + 内存镜像 + 刷新 UI */
    private void fetchAffection() {
        if (!prefs.isPaired() || !prefs.isLoggedIn()) return;
        AuthManager.i(this).get(this, AffectionUtils.AFFECTION_API, new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                try {
                    if (data == null || !data.has("points")) return;
                    final AffectionCacheEntity e = new AffectionCacheEntity();
                    e.points = data.get("points").getAsInt();
                    e.level = data.has("level") ? data.get("level").getAsInt() : 1;
                    e.progress = data.has("progress") ? data.get("progress").getAsDouble() : 0;
                    e.title = data.has("title") && !data.get("title").isJsonNull()
                            ? data.get("title").getAsString() : "";
                    e.updatedAt = System.currentTimeMillis();
                    AppDatabase db = AppDatabase.getInstance(MainActivity.this);
                    AppDatabase.dbExecutor.execute(() -> {
                        db.cacheDao().upsertAffection(e);
                        AffectionStateHolder.update(e);
                        runOnUiThread(() -> {
                            refreshAffectionHeader();
                            applyTheme();
                        });
                    });
                } catch (Exception ex) {
                    Log.w(TAG, "解析亲密度数据失败", ex);
                }
            }

            @Override
            public void onError(int code, String msg) {
                Log.d(TAG, "亲密度兜底拉取失败: " + code + " " + msg);
            }
        });
    }

    /** dp→px（主题尺寸/悬浮旋钮通用） */
    public int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ===== ChatAdapter.Host 实现 =====
    /** 当前主题（聊天气泡渲染：背景/文字/装饰） */
    @Override
    public ChatTheme theme() {
        return ThemeManager.getCurrent(this);
    }

    @Override
    public PrefsManager getPrefs() {
        return prefs;
    }

    /** 图标按主题应用：PNG 自带配色清 tint；矢量主题重套 tint（切换主题时 tint 会被前一个清掉，必须补回） */
    private void applyTintableIcon(android.widget.ImageView v, int res, int tintColorRes, boolean custom) {
        if (v == null) return;
        v.setImageResource(res);
        v.setImageTintList(custom ? null : getColorStateList(tintColorRes));
    }

    /** 头栏地图/图库图标尺寸：默认主题 20dp 原样；PNG 主题 44dp 满幅（白框去掉后图即按钮） */
    private void applyHeaderIconSize(android.widget.ImageView v, com.eyemonitor.ui.theme.ChatTheme t) {
        if (v == null) return;
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();   // 父容器是 LinearLayout，不能转 FrameLayout
        int s = t.customIcons ? dp(44f) : dp(20f);
        lp.width = s;
        lp.height = s;
        v.setLayoutParams(lp);
    }

    /** 主题运行时切换（ThemeManager 数据驱动，一套布局不复制）：头栏/背景/输入区/发送键/装饰/空态 */
    private void applyTheme() {
        ChatTheme t = ThemeManager.getCurrent(this);
        if (chatHeader != null) chatHeader.setBackgroundResource(t.headerGradientRes);
        if (viewChatPanel != null) viewChatPanel.setBackgroundResource(t.chatBgRes);
        if (etChatInput != null) etChatInput.setBackgroundResource(t.inputBgRes);
        if (tvVoiceBar != null) tvVoiceBar.setBackgroundResource(t.voiceBarBgRes);
        if (btnSend != null) {
            if (t.sendBgRes != 0) btnSend.setBackgroundResource(t.sendBgRes);
            btnSend.setImageResource(t.sendIconRes);
            if (t.customSendIcon) {
                btnSend.setImageTintList(null); // 自带配色图标不做 tint
                // PNG 发送钮视觉缩 2dp：按钮保持 44dp（布局像素一致），padding 1dp 让图标=42dp
                btnSend.setPadding(dp(1f), dp(1f), dp(1f), dp(1f));
            } else {
                updateSendButtonState();
            }
        }
        if (ivHeaderDecor != null) {
            if (t.headerDecorRes != 0) {
                ivHeaderDecor.setVisibility(View.VISIBLE);
                ivHeaderDecor.setImageResource(t.headerDecorRes);
            } else {
                ivHeaderDecor.setVisibility(View.GONE);
            }
        }
        // 地图/图库按钮底框：狗狗主题图标自带底，去掉白框直显；默认主题保留
        if (btnChatMap != null) {
            btnChatMap.setBackground(t.customIcons ? null
                    : getDrawable(R.drawable.bg_btn_white_rect));
        }
        if (btnChatGallery != null) {
            btnChatGallery.setBackground(t.customIcons ? null
                    : getDrawable(R.drawable.bg_btn_white_rect));
        }
        // 头栏/输入区图标集：PNG 主题清 tint 直显；默认主题重套矢量 tint（否则白色矢量隐形）
        applyTintableIcon(ivIconMap, t.iconMapRes, R.color.text_on_primary, t.customIcons);
        if (ivIconMap != null) applyHeaderIconSize(ivIconMap, t);   // 尺寸按主题：默认20dp / 狗狗44dp满幅
        applyTintableIcon(ivIconGallery, t.iconGalleryRes, R.color.text_on_primary, t.customIcons);
        if (ivIconGallery != null) applyHeaderIconSize(ivIconGallery, t);
        if (btnMic != null) {
            if (voiceMode) btnMic.setImageResource(R.drawable.ic_keyboard);
            else applyTintableIcon(btnMic, t.iconMicRes, R.color.text_secondary, t.customIcons);
        }
        applyTintableIcon(btnEmoji, t.iconEmojiRes, R.color.text_secondary, t.customIcons);
        applyTintableIcon(btnChatMore, t.iconMoreRes, R.color.text_secondary, t.customIcons);
        if (chatAdapter != null) {
            chatAdapter.notifyDataSetChanged(); // 气泡背景跟随主题
        }
        updateChatEmptyState();
    }

    /** 空态插画：聊天列表为空且主题有插画时显示（狗狗乐园）；空态占位行（ts=0）也算空 */
    private void updateChatEmptyState() {
        if (ivChatEmptyState == null) return;
        ChatTheme t = ThemeManager.getCurrent(this);
        boolean listEmpty = chatAdapter == null || chatAdapter.getItemCount() == 0;
        if (chatAdapter != null && chatAdapter.getItemCount() == 1
                && chatAdapter.getItems().get(0).type == TYPE_SYSTEM
                && chatAdapter.getItems().get(0).ts == 0) {
            listEmpty = true;
        }
        if (listEmpty && t.emptyStateRes != 0) {
            ivChatEmptyState.setVisibility(View.VISIBLE);
            ivChatEmptyState.setImageResource(t.emptyStateRes);
        } else {
            ivChatEmptyState.setVisibility(View.GONE);
        }
    }

    /** 打卡 = 发送早安/晚安问候消息（服务端首字规则计分）；每日每时段仅可一次 */
    private void doCheckIn() {
        int result = MonitorService.performCheckIn(this, null);
        if (result == MonitorService.CHECK_IN_NOT_IN_WINDOW) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.affection_check_in_outside);
        } else if (result == MonitorService.CHECK_IN_ALREADY) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.affection_check_in_already);
        } else {
            com.eyemonitor.util.Toasts.showRes(this, R.string.affection_check_in_success);
        }
    }

    /** 共同升级事件：全屏粒子动画 + 聊天系统提示（system 行由服务层落库） */
    private void handleLevelUp(WsMessage message) {
        Map<String, Object> p = message.getPayload();
        int level = p != null && p.get("level") instanceof Number
                ? ((Number) p.get("level")).intValue() : 0;
        String title = p != null && p.get("title") instanceof String
                ? (String) p.get("title") : "";
        String text = p != null && p.get("text") instanceof String
                ? (String) p.get("text") : getString(R.string.affection_level_up_chat, level);
        appendSystemText(text);
        showLevelUpOverlay(level, title);
        refreshAffectionHeader();
        applyTheme();
    }

    /** 全屏粒子动画（~3s，可点跳过；挂在聊天页 FrameLayout 顶层） */
    private void showLevelUpOverlay(int level, String title) {
        if (viewChatPanel == null) return;
        ChatTheme theme = ThemeManager.getCurrent(this);
        int[] colors = getResources().getIntArray(theme.levelUpColorsRes);
        boolean pawMode = theme.pawParticles;
        final LevelUpOverlayView overlay =
                new LevelUpOverlayView(this, level, title, colors, pawMode);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        ((ViewGroup) viewChatPanel).addView(overlay, lp);
        // 等布局完成再启动动画（粒子出生位置依赖宽高）
        overlay.post(() -> overlay.startAnimation(() -> {
            runOnUiThread(() -> {
                ViewGroup parent = (ViewGroup) overlay.getParent();
                if (parent != null) {
                    parent.removeView(overlay);
                }
            });
        }));
    }

    // --- 监控权限引导 ---

    /** App 启动时检查监控权限：使用情况访问 或 无障碍，都没有则主动引导开启（仅提示一次） */
    private void checkMonitorPermission() {
        // 引导时机统一由 PermissionHelper 控制（系统权限批次结束后/设置页返回后）
    }

    // --- 配对面板逻辑（内联，不再跳转 PairActivity） ---

    private void joinPair() {
        String code = etPairCode.getText().toString().trim();
        if (code.isEmpty()) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.pair_input_empty);
            return;
        }
        if (code.length() != 6) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.pair_input_invalid);
            return;
        }
        connectPairWs(code, true);
    }

    /** 创建配对：连 WS → 发 pair_request（不带码，服务端生成 6 位码） */
    private void createPair() {
        // 乐观置等待态：防止 monitor 的 pair_recover 广播抢先触发 switchView 切聊天页
        pairAwaitingPeer = true;
        prefs.setPairAwaitingPeer(true);
        connectPairWs(null, false);
    }

    /** 统一配对连接入口；握手 401/403 时无感刷新 token 后重连一次 */
    private void connectPairWs(String pairCode, boolean joining) {
        disconnectPairWs();
        pendingPairCode = pairCode;
        pendingPairJoin = joining;
        pairingComplete = false;
        setPairResultVisible(getString(R.string.pair_connecting));

        String serverUrl = prefs.getServerUrl();
        pairWsClient = new com.eyemonitor.websocket.WSClient(serverUrl, new com.eyemonitor.websocket.WSClient.WsCallback() {
            @Override
            public void onConnected() {
                Log.d(TAG, "WS 已连接（配对）");
                setPairResultVisible(getString(R.string.pair_connecting));
                WsMessage req = WsMessage.createPairRequest(prefs.getDeviceId(), pendingPairCode);
                pairWsClient.send(req);
            }

            @Override
            public void onMessage(com.eyemonitor.model.WsMessage message) {
                runOnUiThread(() -> handlePairMessage(message));
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    if (!pairingComplete) {
                        setPairResultVisible(getString(R.string.pair_disconnected));
                    }
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> setPairResultVisible(getString(R.string.pair_connect_failed, msg)));
            }

            @Override
            public void onAuthExpired() {
                runOnUiThread(() -> refreshPairTokenAndRetry());
            }
        });

        pairWsClient.setAuthToken(prefs.getAccessToken());
        pairWsClient.connect();
    }

    /** 握手 401/403：无感刷新 token 后携带新 token 重连（token 过期/被踢场景） */
    private void refreshPairTokenAndRetry() {
        setPairResultVisible(getString(R.string.pair_connecting));
        AuthManager.i(this).refresh(this, new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                Log.d(TAG, "配对握手 token 已无感刷新，重连");
                if (pairWsClient != null) {
                    pairWsClient.setAuthToken(new PrefsManager(MainActivity.this).getAccessToken());
                    pairWsClient.connect();
                }
            }

            @Override
            public void onError(int code, String msg) {
                setPairResultVisible(getString(R.string.auth_error_expired));
                Log.w(TAG, "配对握手 token 刷新失败（token 族已吊销/过期）: " + code + " " + msg);
                // 单设备登录吊销或 refresh 过期：清空登录态，回登录页（用户重新登录换新 token）
                new PrefsManager(MainActivity.this).clearAuth();
                startActivity(new Intent(MainActivity.this, LoginActivity.class));
                finish();
            }
        });
    }

    private void handlePairMessage(com.eyemonitor.model.WsMessage message) {
        switch (message.getType()) {
            case "pair_confirm":
                pairingComplete = true;
                String code = message.getPayload() != null
                        ? (String) message.getPayload().get("pairCode") : null;
                Object peerOnlineObj = message.getPayload() != null
                        ? message.getPayload().get("peerOnline") : null;
                boolean peerOnline = peerOnlineObj instanceof Boolean ? (Boolean) peerOnlineObj : false;
                if (code != null) {
                    prefs.setPairCode(code);
                    if (peerOnline) {
                        // 配对真正完成（对方已加入）：结束等待态
                        pairAwaitingPeer = false;
                        prefs.setPairAwaitingPeer(false);
                        setPairResultVisible(getString(R.string.pair_success_with_code, code));
                        com.eyemonitor.util.Toasts.showCelebrateRes(this, R.string.pair_success);
                    } else {
                        // 已创建 / 已恢复配对：等待对方加入，停留在配对面板（码本页可见）
                        pairAwaitingPeer = true;
                        prefs.setPairAwaitingPeer(true);
                        // 直接强制停留配对面板（覆盖可能已发生的 switchView(true)），码内联显示
                        viewPairPanel.setVisibility(android.view.View.VISIBLE);
                        viewChatPanel.setVisibility(android.view.View.GONE);
                        setPairResultVisible(getString(R.string.pair_code_share, code));
                        com.eyemonitor.util.Toasts.showLong(this, getString(R.string.pair_created_share));
                    }
                    // 启动监控服务，由它管理 WebSocket 与 App 切换监控

                    startMonitorAfterPair();
                }
                break;

            case "error":
                String errMsg = message.getPayload() != null
                        ? (String) message.getPayload().get("message") : getString(R.string.pair_failed, "");
                setPairResultVisible(getString(R.string.pair_failed, errMsg));
                com.eyemonitor.util.Toasts.showLong(this, errMsg);
                pairAwaitingPeer = false;
                prefs.setPairAwaitingPeer(false);
                disconnectPairWs();
                break;

            default:
                setPairResultVisible(getString(R.string.pair_unknown_response, message.getType()));
        }
    }

    /** 创建配对后等待对方加入（配对面板停留态）：优先内存标志，其次 prefs 持久值 */
    private boolean isPairAwaitingPeer() {
        return pairAwaitingPeer || prefs.isPairAwaitingPeer();
    }

    private void setPairResultVisible(String text) {
        if (tvPairResult != null) {
            tvPairResult.setText(text);
            tvPairResult.setVisibility(android.view.View.VISIBLE);
        }
    }

    private void disconnectPairWs() {
        pairHandler.removeCallbacksAndMessages(null);
        if (pairWsClient != null) {
            pairWsClient.disconnect();
            pairWsClient = null;
        }
    }

    /** 配对成功后：关闭配对用 WS，由 MonitorService 自建单一连接并 pair_recover 恢复配对
     *  （旧实现把配对 WS 转移给服务，但服务已运行时转移不生效，导致双连接触发
     *   服务端 WsSessionManager 单设备互踢，每秒重连死循环） */
    private void startMonitorAfterPair() {
        disconnectPairWs();
        Intent intent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    // --- 聊天逻辑 ---

    private void sendChatMessage() {
        String text = etChatInput.getText().toString().trim();
        if (text.isEmpty()) return;

        String from = prefs.getNickname();
        long now = System.currentTimeMillis();
        long refId = pendingRefMsgId;
        String refText = pendingRefText;
        ChatItem ci = new ChatItem(TYPE_SELF, text, from,
                TIME_FORMAT.format(new Date(now)), now, refId, refText);
        // 发送前预判：未连接（断网/未配对）直接标未送达，等回执细化
        boolean preFailed = !MonitorService.isWsConnected();
        ci.failed = preFailed;
        if (preFailed) failedMsgTs.add(now);
        chatAdapter.addItem(ci);

        // 本地入库（Room 禁止主线程操作，走 dbExecutor）
        AppDatabase db = AppDatabase.getInstance(this);
        final ChatEntity entity = new ChatEntity("chat", text, from, true, now, refId, refText);
        entity.sendState = preFailed ? "pending" : "sent"; // 持久化送达状态（重启后未送达仍可见）
        AppDatabase.dbExecutor.execute(() -> db.chatDao().insert(entity));

        // 借道 MonitorService 的 WebSocket 发送（带引用 + 显式时间戳，保证本地/服务端 ts 同源）
        MonitorService.sendChat(this, text, from, refId, refText, now);
        clearPendingQuote();

        etChatInput.setText("");
        morePanelController.hide();
        hideEmojiPanel();
        scrollToBottom();
        // M2 点缀：发送钮小爆发 + 新气泡弹入（位置一次性动画，防 onBindViewHolder 重复触发）
        try {
            btnSend.animate().cancel();
            btnSend.setScaleX(0.85f);
            btnSend.setScaleY(0.85f);
            btnSend.animate().scaleX(1.10f).scaleY(1.10f).setDuration(80).start();
            btnSend.postDelayed(() -> btnSend.animate().scaleX(1f).scaleY(1f)
                    .setDuration(120).start(), 80);
            final int lastPos = chatAdapter.getItemCount() - 1;
            rvChat.post(() -> {
                if (rvChat.getLayoutManager() == null) return;
                View b = rvChat.getLayoutManager().findViewByPosition(lastPos);
                if (b != null) {
                    b.setScaleX(0.92f);
                    b.setScaleY(0.92f);
                    b.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
                }
            });
        } catch (Exception ignored) {}
    }

    // --- 媒体发送（照片/视频，HTTP 上传 + WS 元数据） ---

    /** 打开微信式媒体选择器（照片/视频多选 ≤9，返回所选 Uri 列表） */
    private void pickMedia() {
        morePanelController.hide();
        hideKeyboard();
        if (!prefs.isPaired()) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.media_not_paired);
            return;
        }
        try {
            startActivityForResult(new Intent(this, MediaPickerActivity.class), REQ_PICK_MEDIA);
        } catch (Exception e) {
            Log.w(TAG, "打开媒体选择器失败", e);
            com.eyemonitor.util.Toasts.showRes(this, R.string.media_pick_failed);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_MEDIA && resultCode == RESULT_OK && data != null) {
            // 微信式选择器：多选列表
            java.util.ArrayList<Uri> uris = data.getParcelableArrayListExtra(
                    MediaPickerActivity.EXTRA_SELECTED_URIS);
            if (uris != null && !uris.isEmpty()) {
                for (Uri uri : uris) {
                    handleMediaPicked(uri);
                }
                return;
            }
            // 兜底：单 Uri（兼容旧系统选择器路径）
            Uri uri = data.getData();
            if (uri != null) {
                handleMediaPicked(uri);
            }
        }
    }

    /** 校验大小/视频时长后上传（拷贝与时长读取走后台线程，避免大文件阻塞 UI） */
    private void handleMediaPicked(Uri uri) {
        long size = MediaUtils.querySize(this, uri);
        if (size > MediaUtils.MAX_MEDIA_BYTES) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.media_file_too_large);
            return;
        }
        String name = MediaUtils.queryDisplayName(this, uri);
        String mime = MediaUtils.inferMime(getContentResolver().getType(uri), name);
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
                runOnUiThread(() -> com.eyemonitor.util.Toasts.showRes(MainActivity.this, R.string.media_pick_failed));
                return;
            }
            long duration = video ? MediaUtils.queryDurationMs(MainActivity.this, uri) : 0;
            final File file = tmp;
            final long finalDuration = duration;
            runOnUiThread(() -> {
                if (video && (finalDuration < 0 || finalDuration > MediaUtils.MAX_VIDEO_MS)) {
                    file.delete();
                    com.eyemonitor.util.Toasts.showRes(MainActivity.this, R.string.media_video_too_long);
                    return;
                }
                uploadMedia(file, finalName, finalMime, finalDuration, uri);
            });
        });
    }

    /** 上传到服务器并广播元数据；成功后本地归档 + 聊天气泡立即显示 */
    private void uploadMedia(File file, String name, String mime, long duration, Uri uri) {
        com.eyemonitor.util.Toasts.showRes(this, R.string.media_uploading);
        AuthManager.i(this).uploadMedia(this, file, prefs.getPairCode(), new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                final String fileId = data.has("fileId") ? data.get("fileId").getAsString() : null;
                if (fileId == null || fileId.isEmpty()) {
                    file.delete();
                    runOnUiThread(() -> com.eyemonitor.util.Toasts.showRes(MainActivity.this, R.string.auth_error_response));
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
                        com.eyemonitor.util.Toasts.showRes(MainActivity.this, R.string.media_send_success);
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
            List<TaskEntity> tasks = db.taskDao().getAll();
            runOnUiThread(() -> {
                mediaByFileId.clear();
                for (MediaCacheEntity m : media) mediaByFileId.put(m.fileId, m);
                taskByTaskId.clear();
                for (TaskEntity t : tasks) taskByTaskId.put(t.taskId, t);
                chatAdapter.clear();
                peerUpToTs = 0;
                for (ChatEntity e : all) {
                    int type;
                    if ("task".equals(e.kind)) {
                        type = e.isSelf ? TYPE_TASK_SELF : TYPE_TASK_PEER;
                    } else if ("media".equals(e.kind)) {
                        type = e.isSelf ? TYPE_MEDIA_SELF : TYPE_MEDIA_PEER;
                    } else if ("system".equals(e.kind)) {
                        type = TYPE_SYSTEM;
                    } else {
                        type = e.isSelf ? TYPE_SELF : TYPE_PEER;
                    }
                    ChatItem ci = new ChatItem(type, e.text, e.fromName,
                            TIME_FORMAT.format(new Date(e.timestamp)), e.timestamp,
                            e.refMsgId, e.refText);
                    ci.peerRead = e.peerRead;
                    ci.deleted = e.deleted;
                    ci.failed = "pending".equals(e.sendState) || failedMsgTs.contains(e.timestamp);
                    if (type == TYPE_TASK_SELF || type == TYPE_TASK_PEER) {
                        fillTaskItem(ci, taskByTaskId.get(e.text), type == TYPE_TASK_SELF);
                    }
                    if (!e.isSelf) peerUpToTs = Math.max(peerUpToTs, e.timestamp);
                    chatAdapter.addItem(ci);
                }
                if (chatAdapter.getItemCount() == 0) {
                    chatAdapter.addItem(new ChatItem(TYPE_SYSTEM,
                            getString(R.string.chat_empty), null, "", 0));
                }
                // 聊天页可见且有对方消息 → 补发已读回执（对方刷新我的已读态）
                if (chatForeground && viewChatPanel.getVisibility() == View.VISIBLE && peerUpToTs > 0) {
                    MonitorService.sendChatRead(MainActivity.this, peerUpToTs);
                }
                updateChatEmptyState();
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

    /** 任务气泡 ChatItem 填充（从 TaskEntity 拷贝展示字段；null=任务数据缺失仍可显示） */
    private void fillTaskItem(ChatItem ci, TaskEntity t, boolean self) {
        ci.taskMine = self;
        ci.taskMediaIds = t != null ? t.mediaFileIds : null;
        if (t == null) {
            ci.taskContent = ci.text != null ? ci.text : "";
            ci.taskReward = "";
            ci.taskStatusText = "";
            ci.taskStatusColor = R.color.text_secondary;
            return;
        }
        ci.taskContent = t.content != null ? t.content : "";
        String reward = (t.rewardText != null && !t.rewardText.isEmpty()) ? t.rewardText
                : (t.rewardType != null ? t.rewardType : "");
        ci.taskReward = reward.isEmpty() ? "" : getString(R.string.task_reward_of, reward);
        switch (t.status == null ? "" : t.status) {
            case TaskEntity.STATUS_PENDING:
                ci.taskStatusText = "⏳ " + getString(R.string.task_status_pending);
                break;
            case TaskEntity.STATUS_ACCEPTED:
                ci.taskStatusText = "⏳ " + getString(R.string.task_status_accepted)
                        + " · " + getString(R.string.task_status_await_reward);
                break;
            case TaskEntity.STATUS_REJECTED:
                ci.taskStatusText = "💔 " + getString(R.string.task_status_rejected)
                        + (t.reason != null && !t.reason.isEmpty() ? " · " + t.reason : "");
                break;
            case TaskEntity.STATUS_COMPLETED:
                ci.taskStatusText = "❤ " + getString(R.string.task_status_completed)
                        + " · " + getString(R.string.task_status_await_reward);
                break;
            case TaskEntity.STATUS_REWARDED:
                ci.taskStatusText = "✅ " + getString(R.string.task_status_rewarded);
                break;
            default:
                ci.taskStatusText = t.status;
        }
        switch (t.status == null ? "" : t.status) {
            // v2 §1.4：气泡状态文字改用 *_text 语义变体（AA），默认回落到图注级 text_tertiary
            case TaskEntity.STATUS_REJECTED:
                ci.taskStatusColor = R.color.status_error_text;
                break;
            case TaskEntity.STATUS_REWARDED:
                ci.taskStatusColor = R.color.status_success_text;
                break;
            case TaskEntity.STATUS_COMPLETED:
                ci.taskStatusColor = R.color.status_ok_text;
                break;
            default:
                ci.taskStatusColor = R.color.text_tertiary;
        }
    }

    // --- P2 聊天增强 ---

    /** 媒体按钮单击：快捷工具菜单（照片/视频 / 表情 / 语音），功能一目了然 */
    /** 表情面板（微信式底部上滑）：初始化 8 列网格 */
    private void initEmojiGrid() {
        if (emojiGrid == null) return;
        final String[] emojis = {"😀","😁","😂","🤣","😊","😍","🥰","😘","😎","🤔","😅","😭","😢","🥺","😳","😉","😇","🤗","😴","😡","❤️","💕","💔","👍","👌","🙏","✌️","🎉","🔥","✨","🌹","🎂","💪","🤝"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.item_emoji, emojis);
        emojiGrid.setAdapter(adapter);
        emojiGrid.setOnItemClickListener((parent, v, pos, id) -> {
            String emoji = emojis[pos];
            int sel = etChatInput.getSelectionEnd();
            if (sel < 0) sel = etChatInput.length();
            etChatInput.getText().insert(sel, emoji);
            etChatInput.requestFocus();
        });
    }

    /** 表情面板切换（微信式：滑入/滑出，与更多面板、输入法互斥） */
    private void toggleEmojiPanel() {
        if (emojiPanel.getVisibility() == View.VISIBLE) {
            hideEmojiPanel();
        } else {
            hideKeyboard();
            morePanelController.hideInstant(); // 互斥：另一面板立即消失（不走动画，避免叠加）
            emojiPanel.setVisibility(View.VISIBLE);
            bottomBar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom));
            scrollToBottom();
        }
    }

    private void hideEmojiPanel() {
        if (emojiPanel.getVisibility() == View.GONE) return;
        Animation out = AnimationUtils.loadAnimation(this, R.anim.slide_out_bottom);
        out.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation a) {}

            @Override
            public void onAnimationEnd(Animation a) {
                emojiPanel.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation a) {}
        });
        emojiPanel.startAnimation(out);
    }

    // --- 语音（微信式按压） ---

    /** 语音/键盘切换：语音模式隐藏输入框、显示「按住 说话」 */
    private void toggleVoiceMode() {
        voiceMode = !voiceMode;
        etChatInput.setVisibility(voiceMode ? View.GONE : View.VISIBLE);
        tvVoiceBar.setVisibility(voiceMode ? View.VISIBLE : View.GONE);
        btnMic.setImageResource(voiceMode ? R.drawable.ic_keyboard
                : ThemeManager.getCurrent(this).iconMicRes);
        if (voiceMode) {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(etChatInput.getWindowToken(), 0);
            }
        } else {
            etChatInput.requestFocus();
        }
    }

    private void showRecordPanel() {
        if (llRecordPanel != null) llRecordPanel.setVisibility(View.VISIBLE);
        if (tvRecordHint != null) tvRecordHint.setText(R.string.voice_release_to_send);
    }

    private void hideRecordPanel() {
        if (llRecordPanel != null) llRecordPanel.setVisibility(View.GONE);
    }

    /** 手指是否滑到「取消」按钮上：命中则松开即放弃录音 */
    private void updateVoiceCancelState(float rawX, float rawY) {
        if (llRecordPanel == null || llRecordPanel.getVisibility() != View.VISIBLE
                || btnRecordCancel == null || tvRecordHint == null) {
            return;
        }
        int[] loc = new int[2];
        btnRecordCancel.getLocationInWindow(loc);
        boolean over = rawX >= loc[0] && rawX <= loc[0] + btnRecordCancel.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + btnRecordCancel.getHeight();
        voiceCancelling = over;
        tvRecordHint.setText(over
                ? R.string.voice_release_cancel : R.string.voice_release_to_send);
    }

    /** 开始录音（60s 上限；按压条触发，松开发送/滑到取消放弃） */
    private void startVoiceRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_VOICE_PERMISSION);
            return;
        }
        File dir = new File(getCacheDir(), "voice_send");
        if (!dir.exists() && !dir.mkdirs()) return;
        final File f = new File(dir, System.currentTimeMillis() + ".m4a");
        try {
            voiceRecorder = new MediaRecorder();
            voiceRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            voiceRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            voiceRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            voiceRecorder.setOutputFile(f.getAbsolutePath());
            voiceRecorder.prepare();
            voiceRecorder.start();
            voiceFile = f;
            voiceStartTs = System.currentTimeMillis();
            showRecordPanel();
            voiceTimeoutRunnable = () -> {
                hideRecordPanel();
                tvVoiceBar.setText(R.string.voice_hold_hint);
                finishVoiceRecord(true);
            };
            chatUiHandler.postDelayed(voiceTimeoutRunnable, 60_000L);
        } catch (Exception e) {
            Log.e(TAG, "录音启动失败", e);
            com.eyemonitor.util.Toasts.showRes(this, R.string.voice_record_failed);
        }
    }

    private void finishVoiceRecord(final boolean send) {
        chatUiHandler.removeCallbacks(voiceTimeoutRunnable);
        MediaRecorder r = voiceRecorder;
        voiceRecorder = null;
        File f = voiceFile;
        voiceFile = null;
        long dur = System.currentTimeMillis() - voiceStartTs;
        try {
            if (r != null) {
                r.stop();
                r.release();
            }
        } catch (Exception ignored) {
        }
        if (f == null || !f.exists()) {
            if (f != null) f.delete();
            return;
        }
        if (!send) {
            f.delete();
            return;
        }
        if (dur < 1000) {
            f.delete();
            com.eyemonitor.util.Toasts.showRes(this, R.string.voice_too_short);
            return;
        }
        uploadVoice(f, Math.min(dur, 60_000L));
    }

    /** 语音走媒体上传管线（复用 /api/media/upload + WS media 元数据） */
    private void uploadVoice(final File file, final long durationMs) {
        com.eyemonitor.util.Toasts.showRes(this, R.string.voice_uploading);
        AuthManager.i(this).uploadMedia(this, file, prefs.getPairCode(), new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                final String fileId = data.has("fileId") ? data.get("fileId").getAsString() : null;
                if (fileId == null || fileId.isEmpty()) {
                    file.delete();
                    return;
                }
                final long now = System.currentTimeMillis();
                final MediaCacheEntity e = new MediaCacheEntity();
                e.fileId = fileId;
                e.serverFileName = data.has("fileName") && !data.get("fileName").isJsonNull()
                        ? data.get("fileName").getAsString() : file.getName();
                e.mime = "audio/mp4";
                e.size = data.has("size") ? data.get("size").getAsLong() : file.length();
                e.duration = durationMs;
                e.ts = now;
                AppDatabase db = AppDatabase.getInstance(MainActivity.this);
                AppDatabase.dbExecutor.execute(() -> {
                    File dst = MediaUtils.localMediaFile(MainActivity.this, fileId);
                    boolean archived = dst.exists() && dst.length() > 0 || file.renameTo(dst);
                    if (archived) e.localPath = dst.getAbsolutePath();
                    // 语音波形（新消息）：分析归档后的本地文件（rename 后再动，防 extractor 找不到）
                    try {
                        String wave = com.eyemonitor.util.WaveformAnalyzer.analyze(
                                archived ? dst : file);
                        if (wave != null) e.waveform = wave;
                    } catch (Exception ignored) {}
                    file.delete();
                    db.cacheDao().upsertMedia(e);
                    db.chatDao().insert(new ChatEntity("media", fileId,
                            prefs.getNickname(), true, now));
                    runOnUiThread(() -> {
                        String from = prefs.getNickname() != null ? prefs.getNickname() : "";
                        MonitorService.sendMediaMeta(MainActivity.this, fileId,
                                e.serverFileName, e.mime, e.size, durationMs, from);
                        mediaByFileId.put(fileId, e);
                        chatAdapter.addItem(new ChatItem(TYPE_MEDIA_SELF, fileId, from,
                                TIME_FORMAT.format(new Date(now)), now));
                        scrollToBottom();
                    });
                });
            }

            @Override
            public void onError(int code, String msg) {
                file.delete();
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        getString(R.string.voice_upload_failed,
                                msg != null && !msg.isEmpty() ? msg : "code=" + code),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** 语音气泡点击：未下载先下载；已下载则播放/停止 */
    private void toggleVoice(String fileId, String localPath, long duration,
                             ChatAdapter.ViewHolder h, int position) {
        if (localPath == null || !new File(localPath).exists()) {
            MediaUtils.ensureDownloaded(MainActivity.this, fileId, null, new MediaUtils.MediaCb() {
                @Override
                public void onReady(String path) {
                    h.itemView.post(() -> {
                        MediaCacheEntity m = mediaByFileId.get(fileId);
                        if (m != null) m.localPath = path;
                        chatAdapter.notifyItemChanged(position);
                    });
                }

                @Override
                public void onError(int code, String msg) {
                    h.itemView.post(() -> com.eyemonitor.util.Toasts.showRes(MainActivity.this, R.string.media_download_failed));
                }
            });
            return;
        }
        try {
            if (voicePlayer != null) {
                voicePlayer.release();
                voicePlayer = null;
            }
            voicePlayer = new MediaPlayer();
            voicePlayer.setDataSource(localPath);
            voicePlayer.prepare();
            voicePlayer.start();
            h.tvVoiceDuration.setText(R.string.voice_playing);
            // 波形播放高亮：挂当前气泡视图 + 100ms 进度推进
            resetVoiceWave(h.waveformView);
            if (h.waveformView != null) h.waveformView.reset();
            chatUiHandler.postDelayed(voiceProgressRunnable, 100);
            final MediaPlayer player = voicePlayer;
            player.setOnCompletionListener(mp -> {
                mp.release();
                if (voicePlayer == mp) voicePlayer = null;
                h.itemView.post(() -> {
                    h.tvVoiceDuration.setText(
                            formatVoiceDurationSeconds(duration));
                    resetVoiceWave(h.waveformView);
                });
            });
        } catch (Exception e) {
            Log.e(TAG, "语音播放失败", e);
        }
    }

    private static String formatVoiceDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + "″";
        return (sec / 60) + "′" + (sec % 60) + "″";
    }

    /** 语音气泡秒数：3s / 12s（微信式） */
    private static String formatVoiceDurationSeconds(long ms) {
        return (ms / 1000) + "s";
    }

    // --- 引用 ---

    private void setPendingQuote(ChatItem item) {
        pendingRefMsgId = item.ts;
        pendingRefText = quoteTextOf(item);
        if (tvQuoteStrip != null) {
            tvQuoteText.setText(getString(R.string.chat_quote_strip, pendingRefText));
            tvQuoteStrip.setVisibility(View.VISIBLE);
        }
    }

    /** 引用占位文本：文本=内容截断；语音→[语音]；图片→[图片]；视频→[视频] */
    private String quoteTextOf(ChatItem item) {
        if (item.type == TYPE_MEDIA_SELF || item.type == TYPE_MEDIA_PEER) {
            MediaCacheEntity m = mediaByFileId.get(item.text);
            String mime = m != null ? m.mime : null;
            if (mime != null && mime.startsWith("audio/")) {
                return getString(R.string.quote_media_voice);
            }
            if (MediaUtils.isVideo(mime)) {
                return getString(R.string.quote_media_video);
            }
            return getString(R.string.quote_media_image);
        }
        String t = item.text == null ? "" : item.text;
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private void clearPendingQuote() {
        pendingRefMsgId = 0;
        pendingRefText = null;
        if (tvQuoteStrip != null) tvQuoteStrip.setVisibility(View.GONE);
    }

    // --- 输入中 / 已读 / 撤回 ---

    private void showTypingHint() {
        if (tvTypingHint == null) return;
        // M6 点缀：淡入 + 轻上移（不再硬出现）
        tvTypingHint.animate().cancel();
        tvTypingHint.setAlpha(0f);
        tvTypingHint.setTranslationY(2 * getResources().getDisplayMetrics().density);
        tvTypingHint.setVisibility(View.VISIBLE);
        tvTypingHint.animate().alpha(1f).translationY(0f).setDuration(150).start();
        chatUiHandler.removeCallbacks(hideTypingRunnable);
        chatUiHandler.postDelayed(hideTypingRunnable, 3000);
    }

    private void handleChatRead(WsMessage message) {
        Object upTo = message.getPayload() != null ? message.getPayload().get("upToTs") : null;
        final long upToTs = upTo instanceof Number ? ((Number) upTo).longValue() : 0;
        if (upToTs <= 0) return;
        AppDatabase.dbExecutor.execute(() ->
                AppDatabase.getInstance(MainActivity.this).chatDao().markOwnRead(upToTs));
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < chatAdapter.getItemCount(); i++) {
            ChatItem it = chatAdapter.getItems().get(i);
            if (it.type == TYPE_SELF && !it.peerRead && it.ts <= upToTs) {
                it.peerRead = true;
                changed.add(i);
            }
        }
        if (!changed.isEmpty()) {
            for (int p : changed) {
                chatAdapter.notifyItemChanged(p);
            }
        } else if (chatAdapter.getItemCount() == 0) {
            // 仅当列表尚未加载（回执早于首次渲染）才整表重载；
            // 已渲染且无新匹配（对方重复已读）时跳过，避免反复 reload + scrollToBottom 拽底抖动
            loadChatHistory();
        }
    }

    private void handleChatRecall(WsMessage message) {
        Object ts = message.getPayload() != null ? message.getPayload().get("msgTs") : null;
        final long msgTs = ts instanceof Number ? ((Number) ts).longValue() : 0;
        if (msgTs <= 0) return;
        AppDatabase.dbExecutor.execute(() ->
                AppDatabase.getInstance(MainActivity.this).chatDao().markDeletedByTs(msgTs));
        for (int i = 0; i < chatAdapter.getItemCount(); i++) {
            ChatItem it = chatAdapter.getItems().get(i);
            if (it.ts == msgTs && !it.deleted) {
                it.deleted = true;
                chatAdapter.notifyItemChanged(i);
                return;
            }
        }
    }

    /** 发送结果回执（仅失败）：ok=false → 对应消息标「未送达」 */
    private void handleChatSendResult(WsMessage message) {
        Object tsObj = message.getPayload() != null ? message.getPayload().get("msgTs") : null;
        Object okObj = message.getPayload() != null ? message.getPayload().get("ok") : null;
        if (!(tsObj instanceof Number)) return;
        final long msgTs = ((Number) tsObj).longValue();
        final boolean ok = okObj instanceof Boolean && (Boolean) okObj;
        if (ok || msgTs == 0) return;
        failedMsgTs.add(msgTs);
        for (int i = 0; i < chatAdapter.getItemCount(); i++) {
            ChatItem it = chatAdapter.getItems().get(i);
            if (it.type == TYPE_SELF && it.ts == msgTs) {
                if (!it.failed) {
                    it.failed = true;
                    chatAdapter.notifyItemChanged(i);
                }
                return;
            }
        }
    }

    /** 服务器送达确认：清除未送达标记 */
    private void handleChatAck(WsMessage message) {
        Object tsObj = message.getPayload() != null ? message.getPayload().get("msgTs") : null;
        if (!(tsObj instanceof Number)) return;
        final long msgTs = ((Number) tsObj).longValue();
        failedMsgTs.remove(msgTs);
        for (int i = 0; i < chatAdapter.getItemCount(); i++) {
            ChatItem it = chatAdapter.getItems().get(i);
            if (it.type == TYPE_SELF && it.ts == msgTs && it.failed) {
                it.failed = false;
                chatAdapter.notifyItemChanged(i);
                return;
            }
        }
    }

    /** 点击「未送达」重发：复用原 ts（保持排序与回执匹配），成功由 ack 清除标记 */
    public void resendChat(ChatItem item) {
        if (item.type != TYPE_SELF || item.deleted || item.text == null) return;
        String from = prefs.getNickname();
        item.failed = false;
        failedMsgTs.remove(item.ts);
        int p = chatAdapter.getItems().indexOf(item);
        if (p >= 0) chatAdapter.notifyItemChanged(p);
        MonitorService.sendChat(this, item.text,
                from != null ? from : "", item.refMsgId, item.refText, item.ts);
    }

    /** 当前正在闪的引用消息 ts（防连点重复闪）；-1 = 无 */
    private long flashingRefTs = -1;

    public void scrollToRef(long refMsgId) {
        List<ChatItem> items = chatAdapter.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).ts == refMsgId) {
                rvChat.smoothScrollToPosition(i);
                flashWhenIdle(refMsgId);
                return;
            }
        }
    }

    /** 等列表滚动停稳再闪（smoothScroll 异步，行未到位时闪会落在空位/半路） */
    private void flashWhenIdle(long refMsgId) {
        final int[] tries = {0};
        chatUiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (rvChat == null) return;
                if (rvChat.getScrollState() == RecyclerView.SCROLL_STATE_IDLE || tries[0] >= 20) {
                    flashRefItem(refMsgId);
                } else {
                    tries[0]++;
                    chatUiHandler.postDelayed(this, 150);
                }
            }
        }, 150);
    }

    /** 引用跳转闪烁：行已就位 → 延迟找已绑定 ViewHolder 直接闪（不依赖 bind 时机——
     *  RecyclerView 先 bind 后 attach，bind 里闪会被 isAttachedToWindow 拦掉） */
    private void flashRefItem(long refMsgId) {
        if (flashingRefTs == refMsgId) return;   // 防连点重复
        flashingRefTs = refMsgId;
        chatUiHandler.postDelayed(() -> {
            int idx2 = indexOfTs(refMsgId);
            RecyclerView.ViewHolder vh = idx2 >= 0
                    ? rvChat.findViewHolderForAdapterPosition(idx2) : null;
            if (vh != null && vh.itemView.isAttachedToWindow()) {
                flashRow(vh.itemView, chatAdapter.getItems().get(idx2));
            } else {
                flashingRefTs = -1; // 闪不了（理论罕见），解锁以便下次点击
                if (idx2 >= 0) {
                    // 兜底：置标记走 bind（虽可能 pre-attach skip，聊胜于无）
                    chatAdapter.getItems().get(idx2).flash = true;
                    chatAdapter.notifyItemChanged(idx2);
                }
            }
        }, 250);
    }

    private int indexOfTs(long ts) {
        for (int i = 0; i < chatAdapter.getItems().size(); i++) {
            if (chatAdapter.getItems().get(i).ts == ts) return i;
        }
        return -1;
    }

    /**
     * 引用定位闪烁：高亮带 = view_chat_panel(FrameLayout) 的绝对定位子 View，
     * 覆盖在目标行上并向上探 overhang——完全不影响消息行布局（不挤内容）。
     * 明暗两轮后移除。文本/语音/图片/视频统一。
     */
    public void flashRow(android.view.View row, ChatItem item) {
        if (row == null || viewChatPanel == null) {
            return;
        }
        if (!row.isAttachedToWindow()) {
            return;
        }
        int density = (int) getResources().getDisplayMetrics().density;
        int overhang = 12 * density;
        int rowH = row.getHeight() > 0 ? row.getHeight() : 64 * density;
        int[] rl = new int[2];
        int[] pl = new int[2];
        row.getLocationOnScreen(rl);
        viewChatPanel.getLocationOnScreen(pl);

        int highlight = (0x22 << 24) | (getColor(R.color.primary) & 0xFFFFFF);
        android.view.View band = new android.view.View(this);
        band.setBackgroundColor(highlight);
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(row.getWidth(), rowH + overhang);
        lp.leftMargin = rl[0] - pl[0];
        lp.topMargin = rl[1] - pl[1] - overhang;   // 向上探 overhang
        ((android.view.ViewGroup) viewChatPanel).addView(band, lp);

        android.animation.ObjectAnimator anim =
                android.animation.ObjectAnimator.ofFloat(band, "alpha", 1f, 0f);
        anim.setDuration(650);
        anim.setRepeatCount(1);
        anim.setRepeatMode(android.animation.ValueAnimator.REVERSE); // 亮→淡→亮→淡（≈2s）
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                ((android.view.ViewGroup) viewChatPanel).removeView(band);
                if (item != null) item.flash = false;
                flashingRefTs = -1; // 解锁：允许下一次点击重新闪
            }
        });
        anim.start();
    }


    /** 长按文本消息：紧贴气泡上方弹出 QQ/微信式小菜单（引用 / 撤回，撤回仅自己且 2 分钟内） */
    public void showChatItemActions(ChatItem item, View bubble) {
        dismissChatMenu();
        if (bubble == null) return;
        boolean canRecall = (item.type == TYPE_SELF || item.type == TYPE_MEDIA_SELF)
                && System.currentTimeMillis() - item.ts <= 2 * 60_000L;

        View menu = LayoutInflater.from(this).inflate(R.layout.menu_chat_item, null, false);
        menu.findViewById(R.id.menu_action_quote).setOnClickListener(v -> {
            dismissChatMenu();
            setPendingQuote(item);
        });
        TextView tvRecall = menu.findViewById(R.id.menu_action_recall);
        if (canRecall) {
            tvRecall.setVisibility(View.VISIBLE);
            tvRecall.setOnClickListener(v -> {
                dismissChatMenu();
                MonitorService.sendChatRecall(MainActivity.this, item.ts);
                item.deleted = true;
                AppDatabase.dbExecutor.execute(() ->
                        AppDatabase.getInstance(MainActivity.this)
                                .chatDao().markDeletedByTs(item.ts));
                int p = chatAdapter.getItems().indexOf(item);
                if (p >= 0) chatAdapter.notifyItemChanged(p);
            });
        } else {
            tvRecall.setVisibility(View.GONE);
        }

        menu.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int w = menu.getMeasuredWidth();
        final int h = menu.getMeasuredHeight();
        chatMenuPopup = new PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        chatMenuPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT));
        chatMenuPopup.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT >= 21) {
            chatMenuPopup.setElevation(10 * getResources().getDisplayMetrics().density);
        }

        int density = (int) getResources().getDisplayMetrics().density;
        int margin = 8 * density;
        // 窗口坐标系：菜单水平居中于气泡、垂直紧贴气泡上沿
        int[] loc = new int[2];
        bubble.getLocationInWindow(loc);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int px = loc[0] + (bubble.getWidth() - w) / 2;
        if (px < margin) px = margin;
        if (px + w + margin > screenW) px = screenW - w - margin;
        int py = loc[1] - h - margin;          // 气泡上方
        if (py < 0) {
            py = loc[1] + bubble.getHeight() + margin; // 顶部放不下 → 气泡下方
        }
        chatMenuPopup.showAtLocation(bubble, Gravity.NO_GRAVITY, px, py);
    }

    private void dismissChatMenu() {
        if (chatMenuPopup != null && chatMenuPopup.isShowing()) {
            chatMenuPopup.dismiss();
        }
        chatMenuPopup = null;
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
                    // 对方已加入（配对真正完成）：结束等待态，切聊天页
                    if ((Boolean) peerOnlineObj) {
                        pairAwaitingPeer = false;
                        prefs.setPairAwaitingPeer(false);
                    }
                }
                boolean showChat = prefs.isPaired() && !isPairAwaitingPeer();
                switchView(showChat);
                peerStatusHeader.refresh();
                if (showChat) {
                    // 配对完成：好感度兜底拉取 + 头栏徽章/主题生效
                    fetchAffection();
                    refreshAffectionHeader();
                    applyTheme();
                }
                break;
            case "device_status":
                peerStatusHeader.refresh();
                break;
            case "chat":
                handleChatMessage(message);
                break;
            case "chat_refresh":
                // 打卡问候等服务端路径发的自消息：从 Room 重载（is_self 渲染自我气泡，避免按对方渲染）
                loadChatHistory();
                break;
            case "remark_synced":
                // 远端备注同步到达：刷新头栏与聊天列表（气泡名/系统行显示名即时更新）
                updateChatHeader();
                loadChatHistory();
                break;
            case "typing":
                showTypingHint();
                break;
            case "chat_read":
                handleChatRead(message);
                break;
            case "chat_recall":
                handleChatRecall(message);
                break;
            case "chat_send_result":
                handleChatSendResult(message);
                break;
            case "chat_ack":
                handleChatAck(message);
                break;
            case "sync_chat_done":
                // SyncManager 拉取历史后广播：从 Room 全量重载聊天（离线消息显示）
                runOnUiThread(this::loadChatHistory);
                break;
            case "app_switch":
                String appName = message.getPayload() != null
                        ? (String) message.getPayload().get("appName") : null;
                appendSystemItem(appName);
                break;
            case "anniversary_sync":
                anniversaryHeart.refresh();
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
            case "media":
            case "media_deleted":
                // 服务层已落库/清理，这里整页重载以渲染媒体气泡或移除被删项
                loadChatHistory();
                break;
            case "task_publish":
            case "task_respond":
            case "task_complete":
            case "task_reward":
                // 服务层已同步任务状态 + 聊天气泡，这里重载让气泡徽标更新（自己发布/响应也走同路径）
                loadChatHistory();
                break;
            case "affection_sync":
                // 亲密度快照：刷新头栏徽章 + 主题解锁即时生效
                refreshAffectionHeader();
                applyTheme();
                break;
            case "level_up":
                // 共同升级事件：全屏粒子动画 + 聊天系统提示（服务层已落库 system 行）
                handleLevelUp(message);
                break;
            case "check_in_result":
                // 打卡结果：0=成功 1=不在时段 2=本时段今日已打过（补一次 Toast，通知入口无 UI 时静默）
                Object resObj = message.getPayload() != null ? message.getPayload().get("result") : null;
                runOnUiThread(() -> {
                    int r = resObj instanceof Number ? ((Number) resObj).intValue() : -1;
                    if (r == MonitorService.CHECK_IN_NOT_IN_WINDOW) {
                        com.eyemonitor.util.Toasts.showRes(MainActivity.this,
                                R.string.affection_check_in_outside);
                    } else if (r == MonitorService.CHECK_IN_ALREADY) {
                        com.eyemonitor.util.Toasts.showRes(MainActivity.this,
                                R.string.affection_check_in_already);
                    } else if (r == MonitorService.CHECK_IN_OK) {
                        com.eyemonitor.util.Toasts.showRes(MainActivity.this,
                                R.string.affection_check_in_success);
                    }
                });
                break;
            case "error":
                handleError(message);
                break;
        }
    }

    /** 服务器错误处理：带 code 的业务错误仅提示；无 code 的才视为配对失效清空本地配对 */
    private void handleError(WsMessage message) {
        if (!prefs.isPaired()) return;
        Map<String, Object> payload = message.getPayload();
        Object codeObj = payload != null ? payload.get("code") : null;
        Object msg = payload != null ? payload.get("message") : null;
        String err = msg instanceof String ? (String) msg : "";
        // 配对类错误 code 恒为 null（见 PairService.createError）；带 code 的是业务级错误（如撤回失败）
        if (codeObj instanceof String && !((String) codeObj).isEmpty()) {
            Log.w(TAG, "业务错误(" + codeObj + "): " + err);
            runOnUiThread(() -> Toast.makeText(this,
                    err.isEmpty() ? getString(R.string.chat_recall_failed) : err,
                    Toast.LENGTH_LONG).show());
            return;
        }
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
        long refMsgId = 0;
        String refText = null;
        if (payload != null) {
            Object rid = payload.get("refMsgId");
            if (rid instanceof Number) refMsgId = ((Number) rid).longValue();
            Object rt = payload.get("refText");
            if (rt instanceof String) refText = (String) rt;
        }
        chatAdapter.addItem(new ChatItem(TYPE_PEER, text, from,
                TIME_FORMAT.format(new Date(now)), now, refMsgId, refText));
        // 持久化已在 MonitorService（服务层）完成，这里只渲染
        scrollToBottom();

        // 聊天页可见且有对方消息 → 回执已读（含更早未回执的）
        if (now > peerUpToTs) peerUpToTs = now;
        if (chatForeground && viewChatPanel.getVisibility() == View.VISIBLE && peerUpToTs > 0) {
            MonitorService.sendChatRead(MainActivity.this, peerUpToTs);
        }
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
    /** 围栏系统提示分段分隔符（控制符，用户输入打不出来，昵称/围栏名随便取都不会破坏分段） */
    private static final String SYS_SEG_SEP = "\u001F";

    public CharSequence styleSystemText(ChatItem item) {
        String text = item.text;
        // 围栏富文本系统提示：\u001F 分隔 [时间, 昵称, 动作, 围栏名] → 分段配色
        if (text != null && text.startsWith(SYS_SEG_SEP)) {
            CharSequence rich = styleRichSystemText(text);
            if (rich != null) return rich;
        }
        String header = formatSystemTime(item.ts);
        String prefix = getString(R.string.chat_peer_opened_prefix);
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

    /** 富文本系统提示分段渲染：
     *  围栏（5 段）= 时间灰/昵称粉/动作灰/围栏名橙；
     *  SOS（4 段）= 时间灰/昵称粉/动作红。
     *  昵称段渲染时动态取当前对方显示名（备注/昵称变化后历史提示同步更新）；
     *  「你/我」= 自身视角提示，保持原样。 */
    private CharSequence styleRichSystemText(String text) {
        String[] parts = text.split(SYS_SEG_SEP, -1);
        if (parts.length != 5 && parts.length != 4) return null;
        // 注意：文本以 \u001F 开头 → parts[0]=空串，时段在 parts[1]，昵称段在 parts[2]，动作 parts[3]
        String who = parts[2];
        String cur = prefs.getPeerNickname();
        if (cur != null && !cur.isEmpty() && who != null
                && !who.isEmpty() && !"你".equals(who) && !"我".equals(who)) {
            who = cur; // 昵称段动态换成当前对方显示名（备注/昵称变化后历史提示同步更新）
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (parts.length == 5) {
            appendColored(sb, parts[1], R.color.text_secondary); // 时间灰
            sb.append(" ");
            appendColored(sb, who, R.color.primary);             // 昵称粉（当前显示名）
            sb.append(" ");
            appendColored(sb, parts[3], R.color.text_secondary); // 动作灰
            sb.append("「");
            appendColored(sb, parts[4], R.color.accent);         // 围栏名橙
            sb.append("」范围");
        } else {
            appendColored(sb, parts[1], R.color.text_secondary); // 时间灰
            sb.append(" ");
            appendColored(sb, who, R.color.primary);             // 昵称粉（当前显示名）
            sb.append(" ");
            appendColored(sb, parts[3], R.color.status_error);   // 动作红
        }
        return sb;
    }

    private void appendColored(SpannableStringBuilder sb, String part, int colorRes) {
        if (part == null) return;
        SpannableString span = new SpannableString(part);
        span.setSpan(new ForegroundColorSpan(getColor(colorRes)),
                0, part.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(span);
    }

    /** 发送按钮状态色：无输入灰色，有输入粉色（狗狗乐园发送键自带配色，不做 tint） */
    private void updateSendButtonState() {
        if (ThemeManager.getCurrent(this).customSendIcon) return;
        boolean hasText = etChatInput.getText().length() > 0;
        int color = hasText ? getColor(R.color.primary) : getColor(R.color.text_secondary);
        btnSend.setImageTintList(ColorStateList.valueOf(color));
    }

    // --- 更多面板（QQ 风格网格，弹出时顶起输入栏，与输入法互斥） ---
    // ⑧ 切片重构：面板/分页/动画已迁至 ChatMorePanelController，动作经 Actions 回调回来

    /** 互斥用：立即隐藏表情面板（不走动画，仅用于切到另一面板时） */
    private void hideEmojiPanelInstant() {
        emojiPanel.clearAnimation();
        emojiPanel.setVisibility(View.GONE);
    }

    /** Phase 4 深色模式：外观入口（更多面板第二页）。点一下按顺序循环：跟随系统→浅色→深色→… */
    private void cycleAppearance() {
        String current = prefs.getNightMode();
        String next;
        if (PrefsManager.NIGHT_DARK.equals(current)) {
            next = PrefsManager.NIGHT_FOLLOW_SYSTEM;
        } else if (PrefsManager.NIGHT_LIGHT.equals(current)) {
            next = PrefsManager.NIGHT_DARK;
        } else {
            next = PrefsManager.NIGHT_LIGHT;
        }
        prefs.setNightMode(next);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                PrefsManager.NIGHT_DARK.equals(next)
                        ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        : PrefsManager.NIGHT_LIGHT.equals(next)
                        ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        // 提示当前切换到的模式
        int labelRes = PrefsManager.NIGHT_DARK.equals(next) ? R.string.night_dark
                : PrefsManager.NIGHT_LIGHT.equals(next) ? R.string.night_light
                : R.string.night_follow_system;
        com.eyemonitor.util.Toasts.showRes(MainActivity.this, labelRes);
        // 重开后自动停在更多面板第 2 页（用户可连续点按切换，不丢失上下文）
        getIntent().putExtra(EXTRA_REOPEN_MORE_PAGE2, true);
        recreate();
    }

    /** 清空本地聊天记录 */
    private void clearChatHistory() {
        UiDialogs.confirm(this,
                getString(R.string.dialog_clear_chat_title),
                getString(R.string.dialog_clear_chat_message),
                getString(R.string.ok), true, () -> {
                    AppDatabase.dbExecutor.execute(() ->
                            AppDatabase.getInstance(this).chatDao().clear());
                    chatAdapter.clear();
                    com.eyemonitor.util.Toasts.showRes(this, R.string.toast_chat_cleared);
                });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etChatInput.getWindowToken(), 0);
        }
    }

    // --- SOS 紧急求助（更多菜单 → 长按面板） ---

    /** 更多菜单 → SOS 长按面板：大按钮长按 3 秒发送（未配对/冷却中拦截） */
    private void showSosPanel() {
        if (!prefs.isPaired()) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.sos_need_pair);
            return;
        }
        long remaining = remainingSosCooldown();
        if (remaining > 0) {
            Toast.makeText(this,
                    getString(R.string.sos_cooldown, (remaining + 999) / 1000),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        View body = getLayoutInflater().inflate(R.layout.dialog_sos, null);
        Button btn = body.findViewById(R.id.btn_sos_press);
        btn.setOnTouchListener((v, event) -> {
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
                    sosFired = false;
                    return true;
                default:
                    return true;
            }
        });
        sosPanelDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.sos_alert_entrance_desc)
                .setView(body)
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 发送 SOS：记录冷却时间（跨重启持久化）→ 借道服务发送 → 提示已发送 */
    private void sendSosNow() {
        prefs.setSosLastTrigger(System.currentTimeMillis());
        MonitorService.sendSos(this, getString(R.string.sos_help_me));
        com.eyemonitor.util.Toasts.showRes(this, R.string.sos_sent);
    }

    /** 剩余冷却毫秒（0 = 不在冷却） */
    private long remainingSosCooldown() {
        long last = prefs.getSosLastTrigger();
        if (last <= 0) return 0L;
        long remaining = last + SOS_COOLDOWN_MS - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    /** 收到对方 SOS：前台时弹聊天内快捷回执弹窗（后台靠高优先级通知） */
    private void showSosIncomingDialog(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        Object t = payload != null ? payload.get("text") : null;
        String text = t instanceof String ? (String) t : getString(R.string.sos_help_me);
        Object latObj = payload != null ? payload.get("lat") : null;
        Object lngObj = payload != null ? payload.get("lng") : null;
        showSosIncomingDialog(text,
                latObj instanceof Number ? ((Number) latObj).doubleValue() : Double.NaN,
                lngObj instanceof Number ? ((Number) lngObj).doubleValue() : Double.NaN);
    }

    /** 收到对方 SOS（前台广播 / 点 SOS 通知进入两条路径共用）：弹回执弹窗 */
    private void showSosIncomingDialog(String text, double lat, double lng) {
        if (isFinishing() || isDestroyed() || !hasWindowFocus() || sosDialogShowing) return;
        // 「确认」= 停止每分钟重发 + 跳转高德导航到发送方位置
        android.app.Dialog dialog = UiDialogs.confirm(this,
                getString(R.string.sos_notification_title),
                getString(R.string.sos_peer_alert, text),
                getString(R.string.sos_ack_action), false,
                () -> confirmSosNavigate(text, lat, lng));
        dialog.setOnDismissListener(d -> sosDialogShowing = false);
        sosDialogShowing = true;
    }

    /** 点「确认」：停止重发提醒（服务侧）并跳转高德导航到发送方位置 */
    private void confirmSosNavigate(WsMessage message) {
        java.util.Map<String, Object> payload = message.getPayload();
        Object latObj = payload != null ? payload.get("lat") : null;
        Object lngObj = payload != null ? payload.get("lng") : null;
        confirmSosNavigate(
                payload != null && payload.get("text") instanceof String
                        ? (String) payload.get("text") : getString(R.string.sos_help_me),
                latObj instanceof Number ? ((Number) latObj).doubleValue() : Double.NaN,
                lngObj instanceof Number ? ((Number) lngObj).doubleValue() : Double.NaN);
    }

    private void confirmSosNavigate(String text, double lat, double lng) {
        // 服务侧停止每分钟重发 + 移除通知（不发送任何回执）
        MonitorService.sendSosAck(this);
        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
            String who = prefs.getPeerNickname();
            if (who == null || who.isEmpty()) who = getString(R.string.chat_title_default);
            MapNav.navigate(this, lat, lng, who);
        } else {
            com.eyemonitor.util.Toasts.showRes(this, R.string.sos_no_location);
        }
    }

    private void openPermissionSettings() {
        startActivity(new Intent(this, PermissionActivity.class));
        Transitions.push(this);
    }

    private void showNicknameDialog() {
        UiDialogs.input(this,
                getString(R.string.dialog_nickname_title),
                getString(R.string.dialog_nickname_hint),
                prefs.getNickname(),
                getString(R.string.ok),
                nick -> {
                    prefs.setNickname(nick);
                    Toast.makeText(this, nick, Toast.LENGTH_SHORT).show();
                });
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
        UiDialogs.confirm(this,
                getString(R.string.dialog_unpair_title),
                getString(R.string.dialog_unpair_message),
                getString(R.string.ok), true, () -> {
                    prefs.clearPairCode();
                    // 清除“等对方加入”停留态，避免残留影响后续流程
                    pairAwaitingPeer = false;
                    prefs.setPairAwaitingPeer(false);
                    stopService(new Intent(this, MonitorService.class));
                    AppDatabase.dbExecutor.execute(() ->
                            AppDatabase.getInstance(this).chatDao().clear());
                    chatAdapter.clear();
                    switchView(false);
                });
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

    /** 省电 P3：白名单引导（已配对+未提示过+未豁免 → 弹一次；拒绝后状态页可再进） */
    private void maybePromptBatteryOptimization() {
        if (prefs == null) prefs = new PrefsManager(this);
        if (prefs.isBatteryWhitelistPrompted()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            prefs.setBatteryWhitelistPrompted(true);
            return;
        }
        // 本次后不再弹（无论去不去）；状态页提供重新入口
        prefs.setBatteryWhitelistPrompted(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_opt_dialog_title)
                .setMessage(R.string.battery_opt_dialog_msg)
                .setPositiveButton(R.string.battery_opt_dialog_ok, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        Log.w(TAG, "无法跳转电池优化设置", e);
                    }
                })
                .setNegativeButton(R.string.battery_opt_dialog_cancel, null)
                .show();
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
                com.eyemonitor.util.Toasts.showRes(this, R.string.toast_notification_granted);
            }
        }
    }

    /** 测试无障碍服务是否工作 */
    // --- 数据类与适配器 ---


    // --- 媒体气泡渲染 ---

    /** 媒体气泡：已下载显示缩略图（视频带播放角标），未下载显示点击下载占位 */
    /** 任务气泡绑定（票券式卡片）：自己=右侧珊瑚渐变卡白字，对方=左侧白卡粉描边；点击进任务详情 */
    public void bindTask(ChatAdapter.ViewHolder h, ChatItem item) {
        final boolean self = item.type == TYPE_TASK_SELF;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.llTaskCard.getLayoutParams();
        lp.gravity = self ? Gravity.END : Gravity.START;
        h.llTaskCard.setLayoutParams(lp);
        h.llTaskCard.setBackgroundResource(self ? R.drawable.bg_task_bubble_self : R.drawable.bg_task_bubble_peer);
        // 分隔线：自己=白色半透明（珊瑚卡上），对方=标准 divider
        int dividerColor = self ? R.color.header_btn_bg : R.color.divider;
        h.vTaskDivider1.setBackgroundColor(getResources().getColor(dividerColor));
        h.vTaskDivider2.setBackgroundColor(getResources().getColor(dividerColor));
        int titleColor = self ? R.color.white : R.color.primary_text;
        h.ivTaskBubbleIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(titleColor)));
        h.tvTaskTitle.setTextColor(getResources().getColor(titleColor));
        h.tvTaskTime.setText(item.time);
        h.tvTaskTime.setTextColor(getResources().getColor(
                self ? R.color.text_on_primary_muted : R.color.text_tertiary));
        h.tvTaskContent.setTextColor(getResources().getColor(
                self ? R.color.white : R.color.text_primary));
        h.tvTaskContent.setText(item.taskContent);
        MediaUtils.loadTaskPhotos(MainActivity.this, h.llTaskPhotos, item.taskMediaIds, 80,
                fid -> MediaUtils.openPhotoPreview(MainActivity.this, fid));
        h.tvTaskReward.setText(item.taskReward);
        h.tvTaskReward.setTextColor(getResources().getColor(
                self ? R.color.text_on_primary_muted : R.color.accent_text));
        h.tvTaskStatus.setText(item.taskStatusText);
        h.tvTaskStatus.setTextColor(self ? R.color.white
                : getResources().getColor(item.taskStatusColor));
        h.tvTaskHint.setTextColor(getResources().getColor(
                self ? R.color.text_on_primary_muted : R.color.primary_text));
        h.llTaskCard.setOnClickListener(v -> openTaskDetail(item.text));
    }

    /** 打开任务详情（聊天气泡点击 → TaskActivity，带 taskId） */
    private void openTaskDetail(String taskId) {
        if (taskId == null || taskId.isEmpty()) return;
        morePanelController.hide();
        Intent intent = new Intent(this, TaskActivity.class);
        intent.putExtra(MonitorService.EXTRA_TASK_ID, taskId);
        startActivity(intent);
        Transitions.push(this);
    }

    public void bindMedia(ChatAdapter.ViewHolder h, ChatItem item, int position) {
        final boolean self = item.type == TYPE_MEDIA_SELF;
        h.bindBubbleDecor(self);   // 媒体/语音气泡同样套主题装饰（耳/爪）
        // 已撤回：隐藏媒体内容，占位区显示「已撤回」（媒体 ViewHolder 无 tv_recalled，复用 tv_media_hint）
        if (item.deleted) {
            String who = prefs.getPeerDisplayName() != null ? prefs.getPeerDisplayName()
                    : (item.from != null && !item.from.isEmpty()
                    ? item.from
                    : (prefs.getPeerNickname() != null ? prefs.getPeerNickname()
                    : getString(R.string.chat_title_default)));
            h.flMediaContainer.setVisibility(View.VISIBLE);
            h.ivMediaThumb.setVisibility(View.GONE);
            h.llVoiceBubble.setVisibility(View.GONE);
            h.llMediaPlaceholder.setVisibility(View.VISIBLE);
            h.ivMediaPlaceholderIcon.setVisibility(View.GONE);
            h.tvMediaHint.setText(self
                    ? getString(R.string.chat_recalled_self)
                    : getString(R.string.chat_recalled_peer, who));
            h.tvMediaHint.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        h.flMediaContainer.setVisibility(View.VISIBLE);
        h.ivMediaThumb.setVisibility(View.VISIBLE);
        h.llVoiceBubble.setVisibility(View.VISIBLE);
        h.llMediaPlaceholder.setVisibility(View.GONE);
        final int density = (int) getResources().getDisplayMetrics().density;
        final int outerPad = 60 * density;
        final int nearPad = 12 * density;
        // 与文本项边距一致：自己=左 60/右 12，对方=左 12/右 60（之前写反导致对方图片整体右移一格）
        int startPad = self ? outerPad : nearPad;
        int endPad = self ? nearPad : outerPad;
        h.llMediaBubble.setGravity(self ? Gravity.END : Gravity.START);
        h.llMediaBubble.setPadding(startPad, 0, endPad, 0);
        h.flMediaContainer.setBackgroundResource(R.drawable.bg_card);
        h.tvTime.setText(item.time);
        if (self) {
            h.tvFrom.setVisibility(View.GONE);
        } else {
            h.tvFrom.setVisibility(View.VISIBLE);
            String from = prefs.getPeerDisplayName() != null ? prefs.getPeerDisplayName()
                    : (item.from != null && !item.from.isEmpty()
                    ? item.from : prefs.getPeerNickname());
            h.tvFrom.setText(from != null && !from.isEmpty()
                    ? from : getString(R.string.chat_title_default));
        }

        final String fileId = item.text;
        final MediaCacheEntity meta = mediaByFileId.get(fileId);
        final String mime = meta != null ? meta.mime : null;
        final long duration = meta != null ? meta.duration : 0;
        final boolean video = MediaUtils.isVideo(mime);
        final boolean audio = mime != null && mime.startsWith("audio/");
        h.flVideoBadge.setVisibility(video ? View.VISIBLE : View.GONE);

        final String localPath = meta != null ? meta.localPath : null;
        final boolean downloaded = localPath != null && new File(localPath).exists();
        final int mediaBoxPx = 180 * density;
        if (audio) {
            // 语音消息：容器收缩为内容大小（微信式小气泡，非图片大卡）
            ViewGroup.LayoutParams lp = h.flMediaContainer.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            h.flMediaContainer.setLayoutParams(lp);
            h.ivMediaThumb.setVisibility(View.GONE);
            h.llMediaPlaceholder.setVisibility(View.GONE);
            h.llVoiceBubble.setVisibility(View.VISIBLE);
            // 微信式：自己弧线朝左（-90°），对方朝右（+90°），互为 180° 翻转
            h.ivVoiceWifi.setRotation(self ? -90f : 90f);
            h.ivVoiceWifi.setScaleX(1f);
            // 语音波形（新消息有缓存；老消息/未分析回退 wifi 图标）
            float[] wave = meta != null
                    ? com.eyemonitor.util.WaveformMath.fromCsv(meta.waveform) : null;
            if (wave != null) {
                h.ivVoiceWifi.setVisibility(View.GONE);
                h.waveformView.setVisibility(View.VISIBLE);
                h.waveformView.setData(wave);
                h.waveformView.reset();
            } else {
                h.waveformView.setVisibility(View.GONE);
                h.ivVoiceWifi.setVisibility(View.VISIBLE);
            }
            h.tvVoiceDuration.setText(formatVoiceDurationSeconds(duration));
        } else {
            // 图片/视频：恢复 180dp 方形容器（防回收复用残留）
            ViewGroup.LayoutParams lp = h.flMediaContainer.getLayoutParams();
            lp.width = mediaBoxPx;
            lp.height = mediaBoxPx;
            h.flMediaContainer.setLayoutParams(lp);
            h.llVoiceBubble.setVisibility(View.GONE);
            if (downloaded) {
                h.ivMediaThumb.setVisibility(View.VISIBLE);
                h.llMediaPlaceholder.setVisibility(View.GONE);
                loadThumb(h.ivMediaThumb, localPath, mime);
            } else {
                h.ivMediaThumb.setVisibility(View.GONE);
                h.llMediaPlaceholder.setVisibility(View.VISIBLE);
                h.ivMediaPlaceholderIcon.setVisibility(View.VISIBLE);
                h.ivMediaPlaceholderIcon.setImageResource(R.drawable.ic_image);
                h.ivMediaPlaceholderIcon.setImageTintList(ColorStateList.valueOf(
                        getColor(R.color.text_secondary)));
                h.tvMediaHint.setText(R.string.media_download_hint);
            }
        }

        final boolean unmetered = isUnmeteredConnected();
        h.flMediaContainer.setOnClickListener(v -> {
            if (audio) {
                // P2 语音：下载（如有）并播放/停止
                toggleVoice(fileId, localPath, duration, h, position);
            } else if (downloaded) {
                MediaUtils.launchViewer(MainActivity.this, fileId, mime, duration, localPath);
            } else {
                // 点击占位图：下载并直接打开（原行为）
                h.tvMediaHint.setText(R.string.media_downloading);
                MediaUtils.openMedia(MainActivity.this, fileId, mime, duration,
                        null, new MediaUtils.MediaCb() {
                            @Override
                            public void onReady(String path) {
                                h.itemView.post(() -> {
                                    MediaCacheEntity m = mediaByFileId.get(fileId);
                                    if (m != null) m.localPath = path;
                                    chatAdapter.notifyItemChanged(position);
                                });
                            }

                            @Override
                            public void onError(int code, String msg) {
                                h.itemView.post(() -> {
                                    h.tvMediaHint.setText(R.string.media_download_hint);
                                    com.eyemonitor.util.Toasts.showRes(MainActivity.this, R.string.media_download_failed);
                                });
                            }
                        });
            }
        });
        // 媒体气泡长按：引用（文本消息已有；媒体补上——语音/图片/视频均可引用）
        h.flMediaContainer.setOnLongClickListener(v -> {
            if (item.deleted) return true;
            showChatItemActions(item, h.flMediaContainer);
            return true;
        });
        // 非计费网络（WiFi/Ethernet 等）自动下载：仅下载不打开查看器（避免弹窗打扰）
        if (!downloaded && unmetered && mediaAutoDownloading.add(fileId)) {
            h.tvMediaHint.setText(R.string.media_downloading);
            android.util.Log.i("EyeMonitor", "媒体自动下载开始 fileId=" + fileId);
            MediaUtils.ensureDownloaded(MainActivity.this, fileId, null,
                    new MediaUtils.MediaCb() {
                        @Override
                        public void onReady(String path) {
                            h.itemView.post(() -> {
                                MediaCacheEntity m = mediaByFileId.get(fileId);
                                if (m != null) m.localPath = path;
                                chatAdapter.notifyItemChanged(position);
                            });
                        }

                        @Override
                        public void onError(int code, String msg) {
                            // 失败：移除去重标记允许下次重试；吐司暴露真实失败原因（下载挂了而非没触发）
                            mediaAutoDownloading.remove(fileId);
                            android.util.Log.w("EyeMonitor",
                                    "媒体自动下载失败 fileId=" + fileId + " code=" + code
                                            + " msg=" + msg);
                            h.itemView.post(() -> {
                                h.tvMediaHint.setText(R.string.media_download_hint);
                                Toast.makeText(MainActivity.this,
                                        getString(R.string.media_auto_download_failed,
                                                msg != null && !msg.isEmpty() ? msg : "code=" + code),
                                        Toast.LENGTH_LONG).show();
                            });
                        }
                    });
        }
    }

    /** 当前是否为非计费网络（WiFi/以太网等 —— 模拟器常以 Ethernet 上报，不能只用 TYPE_WIFI 判断） */
    private boolean isUnmeteredConnected() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (nc != null) {
            if (nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                return true;
            }
        }
        // 兜底：老接口按网卡类型判断
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && (ni.getType() == ConnectivityManager.TYPE_WIFI
                || ni.getType() == ConnectivityManager.TYPE_ETHERNET);
    }
}
