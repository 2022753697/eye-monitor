package com.eyemonitor.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AffectionCacheEntity;
import com.eyemonitor.db.AffectionStateHolder;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.util.AffectionUtils;
import com.eyemonitor.util.Transitions;

/**
 * Phase 6 亲密度独立页（从 Profile 迁出）：
 * 等级详情 + 等级里程碑（六级爬坡）+ 积分获取方式 + 周互动对比。
 */
public class AffectionActivity extends BaseActivity {

    /** 等级门槛（与 AffectionService.LEVEL_THRESHOLDS 对齐：Lv1 初识 0 / Lv2 心动 200 / Lv3 热恋 600 / Lv4 情深 1500 / Lv5 挚爱 3000 / Lv6 永恒 6000） */
    private static final long[] LEVEL_THRESHOLDS = {0, 200, 600, 1500, 3000, 6000};
    private static final String[] LEVEL_TITLES = {"初识", "心动", "热恋", "情深", "挚爱", "永恒"};

    private TextView tvAffLevel, tvAffTitle, tvAffPoints, tvAffProgress, tvAffEmpty;
    private ProgressBar progressAffection;
    private TextView tvWeekChats, tvWeekCheckins, tvWeekOnline;
    private LinearLayout llMilestones;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_affection);

        findViewById(R.id.btn_aff_back).setOnClickListener(v -> finish());
        tvAffLevel = findViewById(R.id.tv_aff_level);
        tvAffTitle = findViewById(R.id.tv_aff_title);
        tvAffPoints = findViewById(R.id.tv_aff_points);
        tvAffProgress = findViewById(R.id.tv_aff_progress);
        tvAffEmpty = findViewById(R.id.tv_aff_empty);
        progressAffection = findViewById(R.id.progress_affection);
        tvWeekChats = findViewById(R.id.tv_week_chats);
        tvWeekCheckins = findViewById(R.id.tv_week_checkins);
        tvWeekOnline = findViewById(R.id.tv_week_online);
        llMilestones = findViewById(R.id.ll_level_milestones);
        // 积分获取方式（静态文案，布局占位 + 这里赋值）
        ((android.widget.TextView) findViewById(R.id.tv_earn_rules))
                .setText(getString(R.string.affection_earn_rules));

        renderMilestones(0);
        loadAffection();
    }

    /** 亲密度数据：先读 Room 缓存渲染，再 GET /affection 拉最新（含周对比） */
    private void loadAffection() {
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            final AffectionCacheEntity e = db.cacheDao().getAffection();
            runOnUiThread(() -> renderLevel(e));
        });
        PrefsManager prefs = new PrefsManager(this);
        if (!prefs.isLoggedIn() || !prefs.isPaired()) return;
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
                    AppDatabase db = AppDatabase.getInstance(AffectionActivity.this);
                    AppDatabase.dbExecutor.execute(() -> {
                        db.cacheDao().upsertAffection(e);
                        AffectionStateHolder.update(e);
                        runOnUiThread(() -> {
                            renderLevel(e);
                            renderMilestones(e.level);
                        });
                    });
                    renderWeek(data);
                } catch (Exception ex) {
                    android.util.Log.d("AffectionActivity", "亲密度解析失败", ex);
                }
            }

            @Override
            public void onError(int code, String msg) {
                android.util.Log.d("AffectionActivity", "亲密度拉取失败: " + code + " " + msg);
            }
        });
    }

    /** 等级详情区渲染（缓存空 → 空态文案） */
    private void renderLevel(AffectionCacheEntity e) {
        if (tvAffLevel == null) return;
        if (e == null || e.level <= 0) {
            tvAffEmpty.setVisibility(View.VISIBLE);
            tvAffLevel.setVisibility(View.GONE);
            tvAffTitle.setVisibility(View.GONE);
            progressAffection.setVisibility(View.GONE);
            tvAffPoints.setVisibility(View.GONE);
            tvAffProgress.setVisibility(View.GONE);
            return;
        }
        tvAffEmpty.setVisibility(View.GONE);
        tvAffLevel.setVisibility(View.VISIBLE);
        tvAffTitle.setVisibility(View.VISIBLE);
        progressAffection.setVisibility(View.VISIBLE);
        tvAffPoints.setVisibility(View.VISIBLE);
        tvAffProgress.setVisibility(View.VISIBLE);
        tvAffLevel.setText(getString(R.string.profile_affection_level, e.level));
        String title = e.title != null && !e.title.isEmpty() ? e.title : "";
        tvAffTitle.setText(title.isEmpty()
                ? getString(R.string.affection_badge_title_short, e.level)
                : getString(R.string.affection_badge_title, e.level, title));
        tvAffPoints.setText(getString(R.string.profile_affection_points, e.points));
        int pct = (int) Math.max(0, Math.min(100, Math.round(e.progress * 100)));
        progressAffection.setProgress(pct);
        tvAffProgress.setText(getString(R.string.profile_affection_progress_pct, pct));
    }

    /** 等级里程碑：六级称号 + 门槛，当前级高亮 */
    private void renderMilestones(int currentLevel) {
        if (llMilestones == null) return;
        llMilestones.removeAllViews();
        for (int i = 0; i < LEVEL_THRESHOLDS.length; i++) {
            final int lv = i + 1;
            final boolean current = currentLevel > 0 && lv == currentLevel;
            TextView row = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_affection_milestone, llMilestones, false);
            row.setText(getString(R.string.affection_milestone_format,
                    lv, LEVEL_TITLES[i], LEVEL_THRESHOLDS[i]));
            row.setSelected(current);
            row.setTextColor(getColor(current ? R.color.primary_text : R.color.text_secondary));
            llMilestones.addView(row);
        }
    }

    /** 周互动对比卡：{chats:{me,peer}, checkIns:{me,peer}, onlineMinutes} */
    private void renderWeek(com.google.gson.JsonObject data) {
        if (tvWeekChats == null) return;
        try {
            int meChats = 0, peerChats = 0, meCheckIns = 0, peerCheckIns = 0, onlineMin = 0;
            if (data != null && data.has("weekStats") && data.get("weekStats").isJsonObject()) {
                com.google.gson.JsonObject ws = data.getAsJsonObject("weekStats");
                com.google.gson.JsonObject chats = ws.has("chats") ? ws.getAsJsonObject("chats") : null;
                com.google.gson.JsonObject checkIns = ws.has("checkIns") ? ws.getAsJsonObject("checkIns") : null;
                if (chats != null) {
                    meChats = chats.has("me") ? chats.get("me").getAsInt() : 0;
                    peerChats = chats.has("peer") ? chats.get("peer").getAsInt() : 0;
                }
                if (checkIns != null) {
                    meCheckIns = checkIns.has("me") ? checkIns.get("me").getAsInt() : 0;
                    peerCheckIns = checkIns.has("peer") ? checkIns.get("peer").getAsInt() : 0;
                }
                onlineMin = ws.has("onlineMinutes") ? ws.get("onlineMinutes").getAsInt() : 0;
            }
            tvWeekChats.setText(getString(R.string.profile_week_value_pair, meChats, peerChats));
            tvWeekCheckins.setText(getString(R.string.profile_week_value_pair, meCheckIns, peerCheckIns));
            tvWeekOnline.setText(getString(R.string.profile_week_online_value, onlineMin));
        } catch (Exception ex) {
            android.util.Log.w("AffectionActivity", "周对比渲染失败", ex);
        }
    }
}
