package com.eyemonitor.ui.chat;

import android.content.Intent;
import android.view.View;

import com.eyemonitor.R;
import com.eyemonitor.db.AnniversaryCacheEntity;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.ui.AnniversaryActivity;
import com.eyemonitor.ui.MainActivity;
import com.eyemonitor.util.AnniversaryUtils;
import com.eyemonitor.util.Transitions;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 头栏纪念日爱心控制器（⑧ 切片重构）：爱心轮播 + 心跳动画 + 左右滑动切换 + 点击进纪念日页。 */
public class AnniversaryHeartController {

    private final MainActivity activity;

    private View viewAnniversaryHeart;
    private View ivAnniversaryHeart;
    private android.widget.TextView tvAnniversaryHeartCount;
    private android.widget.TextView tvAnniversaryHeartLabel;
    private android.animation.ValueAnimator heartBeatAnim;
    /** 每个纪念日一颗爱心（空态「+」在列表末尾） */
    private final List<AnniversaryCacheEntity> anniversaryList = new ArrayList<>();
    private int anniversaryIndex = 0;
    private android.view.GestureDetector anniversaryGesture;

    public AnniversaryHeartController(MainActivity activity, View root) {
        this.activity = activity;
        // 纪念日：静态爱心 + 左右滑动切换（点击进纪念日页）
        viewAnniversaryHeart = root.findViewById(R.id.view_anniversary_heart);
        tvAnniversaryHeartCount = root.findViewById(R.id.tv_anniversary_heart_count);
        tvAnniversaryHeartLabel = root.findViewById(R.id.tv_anniversary_heart_label);
        anniversaryGesture = new android.view.GestureDetector(activity,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(android.view.MotionEvent e) {
                        return true; // 接收滑动事件
                    }

                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        // OnTouchListener 拦截了 click，这里手动触发跳转纪念日页
                        activity.startActivity(new Intent(activity, AnniversaryActivity.class));
                        Transitions.push(activity);
                        return true;
                    }

                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (Math.abs(velocityX) > Math.abs(velocityY) && Math.abs(velocityX) > 300) {
                            if (velocityX < 0) {
                                cycleAnniversary(1);      // 左滑：下一个
                            } else {
                                cycleAnniversary(-1);     // 右滑：上一个
                            }
                            return true;
                        }
                        return false;
                    }
                });
        viewAnniversaryHeart.setOnTouchListener((v, event) -> {
            // M1 点缀：按下微缩、松开回弹（触控热区不变）
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(120).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(180)
                            .withEndAction(() -> v.animate()
                                    .scaleX(1f).scaleY(1f).setDuration(120).start()).start();
                    break;
            }
            // 滑动手势切换纪念日
            return anniversaryGesture.onTouchEvent(event);
        });
        ivAnniversaryHeart = root.findViewById(R.id.iv_anniversary_heart);
    }

    /** 刷新头栏爱心轮播：读 Room 缓存，每个纪念日一颗爱心（无数据显示单颗“+”）；无起始则显示最近倒计时 */
    public void refresh() {
        AppDatabase.dbExecutor.execute(() -> {
            List<AnniversaryCacheEntity> list = AppDatabase.getInstance(activity)
                    .cacheDao().getAnniversaries();
            final List<AnniversaryCacheEntity> snapshot = new ArrayList<>();
            if (list != null) snapshot.addAll(list);
            activity.runOnUiThread(() -> {
                anniversaryList.clear();
                anniversaryList.addAll(snapshot);
                anniversaryIndex = 0;
                renderAnniversaryHeart();
            });
        });
    }

    /** 爱心内容切换：只换数字与名称（爱心图标不动），带淡入淡出 */
    private void cycleAnniversary(int delta) {
        if (anniversaryList.isEmpty()) return;
        int size = anniversaryList.size() + 1; // 含末尾空态「+」
        anniversaryIndex = ((anniversaryIndex + delta) % size + size) % size;
        renderAnniversaryHeart();
    }

    /** 渲染当前爱心：空态「+/添加」；否则数字（已在一起/距离）+ 名称 */
    private void renderAnniversaryHeart() {
        if (tvAnniversaryHeartCount == null || tvAnniversaryHeartLabel == null) return;
        String countText;
        String labelText;
        if (anniversaryList.isEmpty()) {
            countText = "+";
            labelText = activity.getString(R.string.anniversary_add);
            stopHeartbeat();
        } else if (anniversaryIndex < anniversaryList.size()) {
            AnniversaryCacheEntity e = anniversaryList.get(anniversaryIndex);
            Calendar today = AnniversaryUtils.today();
            long since = AnniversaryUtils.daysSinceStart(e, today);
            long next = AnniversaryUtils.daysUntilNext(e, today);
            countText = since >= 0 ? String.valueOf(since)
                    : next >= 0 ? String.valueOf(next) : "+";
            // E1 点缀：当天即纪念日 → 高光文案
            boolean isToday = next == 0;
            labelText = e.name != null && !isToday ? e.name
                    : e.name != null ? activity.getString(R.string.anniversary_today, e.name)
                    : activity.getString(R.string.anniversary_add);
            startHeartbeat();
        } else {
            countText = "+";
            labelText = activity.getString(R.string.anniversary_add);
            stopHeartbeat();
        }
        // 淡入淡出切换（爱心图标本身不动）
        android.view.animation.AlphaAnimation out = new android.view.animation.AlphaAnimation(1f, 0f);
        out.setDuration(120L);
        final String fCount = countText;
        final String fLabel = labelText;
        out.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
            @Override public void onAnimationStart(android.view.animation.Animation a) {}
            @Override public void onAnimationEnd(android.view.animation.Animation a) {
                tvAnniversaryHeartCount.setText(fCount);
                tvAnniversaryHeartLabel.setText(fLabel);
                android.view.animation.AlphaAnimation in = new android.view.animation.AlphaAnimation(0f, 1f);
                in.setDuration(120L);
                tvAnniversaryHeartCount.startAnimation(in);
                tvAnniversaryHeartLabel.startAnimation(in);
            }
            @Override public void onAnimationRepeat(android.view.animation.Animation a) {}
        });
        tvAnniversaryHeartCount.startAnimation(out);
        tvAnniversaryHeartLabel.startAnimation(out);
    }

    /** M1 点缀：爱心心跳呼吸（1.5s 周期，幅度 ≤6%，动画缩放=0 时跳过） */
    private void startHeartbeat() {
        stopHeartbeat();
        if (ivAnniversaryHeart == null) return;
        try {
            if (android.provider.Settings.Global.getFloat(activity.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return;
        } catch (Exception ignored) {}
        heartBeatAnim = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                ivAnniversaryHeart,
                android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
                android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f));
        heartBeatAnim.setDuration(900);
        heartBeatAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        heartBeatAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        heartBeatAnim.start();
    }

    private void stopHeartbeat() {
        if (heartBeatAnim != null) {
            heartBeatAnim.cancel();
            heartBeatAnim = null;
        }
        if (ivAnniversaryHeart != null) {
            ivAnniversaryHeart.setScaleX(1f);
            ivAnniversaryHeart.setScaleY(1f);
        }
    }
}