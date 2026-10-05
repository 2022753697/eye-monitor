package com.eyemonitor.ui.chat;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.eyemonitor.R;
import com.eyemonitor.ui.MainActivity;

/** 更多面板控制器（⑧ 切片重构）：QQ 风格分页网格（第 1 页 8 入口 / 第 2 页清空聊天+预留位），与输入法/表情面板互斥。 */
public class ChatMorePanelController {

    /** MainActivity 动作回调：面板点击只负责 UI 显隐与导航壳，业务动作回 MainActivity 执行（单向依赖，不循环） */
    public interface Actions {
        void onPickMedia();
        void onShowSos();
        void onOpenPermissions();
        void onOpenTask();
        void onOpenMemo();
        void onShowUnpairDialog();
        void onOpenProfile();
        void onOpenTrack();
        void onClearChat();
        void onCheckIn();
        void onOpenAnniversary();
        void onScrollToBottom();
        void onHideEmojiPanelInstant();
        void onHideKeyboard();
        /** Phase 4 深色模式：外观三态入口（跟随系统/浅色/深色） */
        void onChangeAppearance();
        /** Phase 5 消息搜索入口 */
        void onOpenSearch();
    }

    private final MainActivity activity;
    private final Actions actions;
    private View morePanel;
    private View bottomBar;
    private ViewPager2 vpMore;

    public ChatMorePanelController(MainActivity activity, View root, Actions actions) {
        this.activity = activity;
        this.actions = actions;
        morePanel = root.findViewById(R.id.more_panel);
        bottomBar = root.findViewById(R.id.bottom_bar);

        ViewPager2 vp = morePanel.findViewById(R.id.vp_more);
        this.vpMore = vp;
        vp.setAdapter(new MorePageAdapter());
        vp.setOffscreenPageLimit(2);
        vp.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateMoreDots(position);
            }
        });
        updateMoreDots(0);
    }

    /** 外观切换后重开面板并停在第 2 页（连续点按不丢上下文；无滑入动画，视觉原位保持） */
    public void reopenOnSecondPage() {
        morePanel.clearAnimation();
        morePanel.setVisibility(View.VISIBLE);
        if (vpMore != null) {
            vpMore.post(() -> vpMore.setCurrentItem(1, false));
        }
        actions.onScrollToBottom();
    }

    /** 页码指示点刷新（当前页高亮） */
    private void updateMoreDots(int position) {
        View d0 = morePanel.findViewById(R.id.more_dot_0);
        View d1 = morePanel.findViewById(R.id.more_dot_1);
        d0.setAlpha(position == 0 ? 1f : 0.25f);
        d1.setAlpha(position == 1 ? 1f : 0.25f);
    }

    /** 切换更多面板：输入栏+面板整个底部块一起滑入/滑出，显示时收起输入法并滚到最新消息 */
    public void toggle() {
        if (morePanel.getVisibility() == View.VISIBLE) {
            hide();
        } else {
            actions.onHideKeyboard();
            actions.onHideEmojiPanelInstant(); // 互斥：另一面板立即消失（不走动画，避免叠加）
            morePanel.setVisibility(View.VISIBLE);
            bottomBar.startAnimation(AnimationUtils.loadAnimation(activity, R.anim.slide_in_bottom));
            actions.onScrollToBottom();
        }
    }

    /** 互斥用：立即隐藏更多面板（不走动画，仅用于切到另一面板时） */
    public void hideInstant() {
        morePanel.clearAnimation();
        morePanel.setVisibility(View.GONE);
    }

    public void hide() {
        if (morePanel.getVisibility() == View.GONE) return;
        Animation out = AnimationUtils.loadAnimation(activity, R.anim.slide_out_bottom);
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

    /** 更多面板分页适配器：第 1 页 = 图片/SOS/权限/任务/备忘录/解除/轨迹/我的；第 2 页 = 清空聊天 + 预留位 */
    private class MorePageAdapter extends RecyclerView.Adapter<MorePageAdapter.Holder> {
        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(
                    viewType == 0 ? R.layout.item_more_page1 : R.layout.item_more_page2,
                    parent, false);
            return new Holder(v, viewType);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {}

        @Override
        public int getItemCount() {
            return 2;
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(View v, int page) {
                super(v);
                if (page == 0) {
                    v.findViewById(R.id.grid_image).setOnClickListener(x -> {
                        hide();
                        actions.onPickMedia();
                    });
                    v.findViewById(R.id.grid_sos).setOnClickListener(x -> {
                        hide();
                        actions.onShowSos();
                    });
                    v.findViewById(R.id.grid_permissions).setOnClickListener(x -> {
                        hide();
                        actions.onOpenPermissions();
                    });
                    v.findViewById(R.id.grid_task).setOnClickListener(x -> {
                        hide();
                        actions.onOpenTask();
                    });
                    v.findViewById(R.id.grid_memo).setOnClickListener(x -> {
                        hide();
                        actions.onOpenMemo();
                    });
                    v.findViewById(R.id.grid_unpair).setOnClickListener(x -> {
                        hide();
                        actions.onShowUnpairDialog();
                    });
                    v.findViewById(R.id.grid_profile).setOnClickListener(x -> {
                        hide();
                        actions.onOpenProfile();
                    });
                    v.findViewById(R.id.grid_track).setOnClickListener(x -> {
                        hide();
                        actions.onOpenTrack();
                    });
                } else {
                    v.findViewById(R.id.grid_clear).setOnClickListener(x -> {
                        hide();
                        actions.onClearChat();
                    });
                    // 打卡（三入口之一：更多面板）
                    v.findViewById(R.id.grid_checkin).setOnClickListener(x -> {
                        hide();
                        actions.onCheckIn();
                    });
                    // v2 §2.3 补位：纪念日入口（纯 UI 接线，不触业务）
                    v.findViewById(R.id.grid_anniversary).setOnClickListener(x -> {
                        hide();
                        actions.onOpenAnniversary();
                    });
                    // v2 §2.3 补位：亲密度入口（复用资料页等级详情）
                    v.findViewById(R.id.grid_affection).setOnClickListener(x -> {
                        hide();
                        actions.onOpenProfile();
                    });
                    // Phase 5 消息搜索入口（更多面板第 2 页）
                    v.findViewById(R.id.grid_search).setOnClickListener(x -> {
                        hide();
                        actions.onOpenSearch();
                    });
                    // Phase 4 深色模式：外观三态入口（跟随系统/浅色/深色）
                    v.findViewById(R.id.grid_appearance).setOnClickListener(x -> {
                        hide();
                        actions.onChangeAppearance();
                    });
                }
            }
        }
    }
}