package com.eyemonitor.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.TaskEntity;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.service.SyncManager;
import com.eyemonitor.util.MediaUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 情侣任务页（发布 + 四筛选 + 列表 + 详情操作）。
 * 状态机：PENDING → ACCEPTED → COMPLETED（发布方确认）→ REWARDED（接收方确认）
 *                       ↘ REJECTED（含理由）
 * 操作走 MonitorService（本地状态源 + WS 同步），Room 禁止主线程。
 */
public class TaskActivity extends BaseActivity {

    private static final String TAG = "TaskActivity";

    private static final int REQ_PICK_PHOTO = 1001;

    private RecyclerView rvTasks;
    private TextView tvEmpty;
    private View esTask;
    private LinearLayout llFilters;
    private final List<TaskEntity> all = new ArrayList<>();
    private int filterIndex = 0; // 0 全部 / 1 进行中 / 2 已完成 / 3 已拒绝
    private final List<TextView> filterChips = new ArrayList<>();

    /** 发布弹窗状态：已选配图 fileId 列表（上传成功后就地更新弹窗图片条，不重开弹窗） */
    private final List<String> pendingPhotoFileIds = new ArrayList<>();
    /** 从聊天气泡/通知点进来的任务：列表加载后直接弹详情 */
    private String pendingDetailTaskId;
    /** 待上传队列（选图器可一次多选，逐张上传） */
    private final java.util.ArrayDeque<Uri> photoUploadQueue = new java.util.ArrayDeque<>();
    /** 一批多张只弹一次「上传中」（避免 9 张弹 9 次） */
    private boolean uploadToastShown = false;
    private androidx.appcompat.app.AlertDialog publishDialog;
    private View publishBody;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task);

        findViewById(R.id.btn_task_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_task_publish).setOnClickListener(v -> showPublishDialog());
        pendingDetailTaskId = getIntent().getStringExtra(MonitorService.EXTRA_TASK_ID);

        rvTasks = findViewById(R.id.rv_tasks);
        rvTasks.setLayoutManager(new LinearLayoutManager(this));
        rvTasks.setAdapter(new TaskAdapter());
        llFilters = findViewById(R.id.ll_task_filters);

        esTask = findViewById(R.id.es_task);
        if (esTask != null) {
            // 空态组件：图标 + 标题 + 副文案 + 主按钮（直达发布弹窗）
            android.widget.ImageView esIcon = esTask.findViewById(R.id.es_icon);
            if (esIcon != null) esIcon.setImageResource(R.drawable.ic_task);
            TextView esTitle = esTask.findViewById(R.id.es_title);
            if (esTitle != null) esTitle.setText(R.string.task_empty);
            TextView esSub = esTask.findViewById(R.id.es_subtitle);
            if (esSub != null) esSub.setText(R.string.task_empty_sub);
            Button esAction = esTask.findViewById(R.id.es_action);
            if (esAction != null) {
                esAction.setText(R.string.task_publish);
                esAction.setOnClickListener(v -> showPublishDialog());
            }
        }
        buildFilterChips();

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 拉取服务端任务对账（离线补收）+ 等同步落地后再刷一次列表
        SyncManager.syncTasks(this);
        reload();
        rvTasks.postDelayed(this::reload, 700);
    }

    // --- 筛选 chips ---

    private void buildFilterChips() {
        int[] labels = {R.string.task_filter_all, R.string.task_filter_ongoing,
                R.string.task_filter_done, R.string.task_filter_rejected};
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(13);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setBackgroundResource(R.drawable.bg_btn_round_white);
            final int idx = i;
            chip.setOnClickListener(v -> setFilter(idx));
            llFilters.addView(chip);
            filterChips.add(chip);
        }
        setFilter(0);
    }

    private void setFilter(int idx) {
        filterIndex = idx;
        for (int i = 0; i < filterChips.size(); i++) {
            TextView c = filterChips.get(i);
            if (i == idx) {
                c.setBackgroundResource(R.drawable.bg_btn_white_rect);
                c.setTextColor(getResources().getColor(R.color.primary));
            } else {
                c.setBackgroundResource(R.drawable.bg_btn_round_white);
                c.setTextColor(getResources().getColor(R.color.text_primary));
            }
        }
        render();
    }

    private void render() {
        List<TaskEntity> shown = new ArrayList<>();
        for (TaskEntity e : all) {
            switch (filterIndex) {
                case 1:
                    if (!TaskEntity.STATUS_PENDING.equals(e.status)
                            && !TaskEntity.STATUS_ACCEPTED.equals(e.status)) continue;
                    break;
                case 2:
                    if (!TaskEntity.STATUS_COMPLETED.equals(e.status)
                            && !TaskEntity.STATUS_REWARDED.equals(e.status)) continue;
                    break;
                case 3:
                    if (!TaskEntity.STATUS_REJECTED.equals(e.status)) continue;
                    break;
            }
            shown.add(e);
        }
        if (rvTasks.getAdapter() != null) ((TaskAdapter) rvTasks.getAdapter()).setData(shown);
        if (esTask != null) esTask.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
    }

    /** 操作后的延迟刷新：Room 落库在 MonitorService 的 dbExecutor 异步进行，立即 reload 读到旧数据 */
    private void reloadSoon() {
        rvTasks.postDelayed(this::reload, 300);
    }

    private void reload() {
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            List<TaskEntity> list = db.taskDao().getAll();
            runOnUiThread(() -> {
                all.clear();
                all.addAll(list);
                render();
                maybeOpenPendingDetail();
            });
        });
    }

    /** 从聊天气泡/通知点进任务页时（EXTRA_TASK_ID），列表加载完成后直接弹该任务详情 */
    private void maybeOpenPendingDetail() {
        if (pendingDetailTaskId == null) return;
        final String want = pendingDetailTaskId;
        pendingDetailTaskId = null;
        for (TaskEntity t : all) {
            if (t.taskId.equals(want)) {
                showDetailDialog(t);
                break;
            }
        }
    }

    // --- 列表适配器（面板卡片：日期+状态pill / 内容大字 / 奖励） ---

    private class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.Holder> {
        private final List<TaskEntity> data = new ArrayList<>();

        void setData(List<TaskEntity> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_task_card, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            TaskEntity e = data.get(position);
            h.bind(e);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView tvDate, tvStatus, tvContent, tvReward;
            final LinearLayout llPhotos;

            Holder(View itemView) {
                super(itemView);
                tvDate = itemView.findViewById(R.id.tv_task_card_date);
                tvStatus = itemView.findViewById(R.id.tv_task_card_status);
                tvContent = itemView.findViewById(R.id.tv_task_card_content);
                tvReward = itemView.findViewById(R.id.tv_task_card_reward);
                llPhotos = itemView.findViewById(R.id.ll_card_photos);
                itemView.setOnClickListener(v -> showDetailDialog(data.get(getBindingAdapterPosition())));
            }

            void bind(TaskEntity e) {
                tvDate.setText(fmtTime(e.ts));
                tvContent.setText(e.content != null ? e.content : "");
                String reward = rewardDisplay(e);
                tvReward.setText(reward != null && !reward.isEmpty()
                        ? "🎁 " + getString(R.string.task_reward_of, reward) : "");
                MediaUtils.loadTaskPhotos(TaskActivity.this, llPhotos, e.mediaFileIds, 120,
                        fid -> showPhotoPreview(fid));
                tvStatus.setText(statusDisplay(e));
                int pillBg = R.drawable.bg_pill_gray;
                int pillColor = R.color.text_secondary;
                switch (e.status == null ? "" : e.status) {
                    case TaskEntity.STATUS_ACCEPTED:
                        pillBg = R.drawable.bg_pill_pink;
                        pillColor = R.color.primary;
                        break;
                    case TaskEntity.STATUS_COMPLETED:
                    case TaskEntity.STATUS_REWARDED:
                        pillBg = R.drawable.bg_pill_green;
                        pillColor = R.color.status_ok;
                        break;
                    case TaskEntity.STATUS_REJECTED:
                        pillBg = R.drawable.bg_pill_red;
                        pillColor = R.color.status_error;
                        break;
                }
                tvStatus.setBackgroundResource(pillBg);
                tvStatus.setTextColor(getResources().getColor(pillColor));
            }
        }
    }

    // --- 详情弹窗（dialog_task_detail：信息卡 + 时间线 + 按角色×状态的操作按钮） ---

    private void showDetailDialog(final TaskEntity e) {
        View body = getLayoutInflater().inflate(R.layout.dialog_task_detail, null);
        String who = e.isMine ? getString(R.string.chat_self_name)
                : (e.peerName != null && !e.peerName.isEmpty() ? e.peerName : getString(R.string.chat_title_default));
        ((TextView) body.findViewById(R.id.tv_detail_who))
                .setText(getString(R.string.task_publisher_of, who) + " · " + fmtTime(e.ts));
        ((TextView) body.findViewById(R.id.tv_detail_content))
                .setText(e.content != null ? e.content : "");
        // 配图条（多图全部显示，点击放大）
        MediaUtils.loadTaskPhotos(this, body.findViewById(R.id.ll_detail_photos),
                e.mediaFileIds, 140, fid -> showPhotoPreview(fid));
        String reward = rewardDisplay(e);
        ((TextView) body.findViewById(R.id.tv_detail_reward))
                .setText(reward != null && !reward.isEmpty()
                        ? "🎁 " + getString(R.string.task_reward_of, reward) : "");
        TextView tvReason = body.findViewById(R.id.tv_detail_reason);
        if (TaskEntity.STATUS_REJECTED.equals(e.status) && e.reason != null && !e.reason.isEmpty()) {
            tvReason.setText(getString(R.string.task_rejected_at, e.reason));
            tvReason.setVisibility(View.VISIBLE);
        }

        // 时间线：4 步（发布/接受/完成/兑现）——绿实心●=已完成，灰空心○=待进行
        LinearLayout tl = body.findViewById(R.id.ll_detail_timeline);
        addTimelineRow(tl, getString(R.string.tl_published), fmtTime(e.ts),
                true, R.color.status_ok);
        boolean accepted = !TaskEntity.STATUS_PENDING.equals(e.status);
        addTimelineRow(tl, getString(R.string.tl_accepted),
                accepted && e.ts > 0 ? "—" : "", accepted, accepted ? R.color.status_ok : R.color.text_secondary);
        boolean completed = TaskEntity.STATUS_COMPLETED.equals(e.status)
                || TaskEntity.STATUS_REWARDED.equals(e.status);
        addTimelineRow(tl, getString(R.string.tl_completed),
                completed && e.completedTs > 0 ? fmtTime(e.completedTs) : (accepted ? getString(R.string.tl_await) : ""),
                completed, completed ? R.color.status_ok : R.color.text_secondary);
        boolean rewarded = TaskEntity.STATUS_REWARDED.equals(e.status);
        addTimelineRow(tl, getString(R.string.tl_rewarded),
                rewarded && e.rewardedTs > 0 ? fmtTime(e.rewardedTs) : (completed ? getString(R.string.tl_await) : ""),
                rewarded, rewarded ? R.color.status_ok : R.color.text_secondary);

        // 操作按钮（按 角色×状态）；动作成功后关闭详情弹窗
        TextView btnPrimary = body.findViewById(R.id.btn_detail_primary);
        TextView btnSecondary = body.findViewById(R.id.btn_detail_secondary);
        final boolean isReceiver = !e.isMine;
        Log.i(TAG, "详情弹窗: status=" + e.status + " isMine=" + e.isMine
                + " isReceiver=" + isReceiver + " btnPrimary=" + (btnPrimary != null)
                + " btnSecondary=" + (btnSecondary != null));
        final androidx.appcompat.app.AlertDialog dlg = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("💌 " + getString(R.string.task_detail_title))
                .setView(body)
                .setNegativeButton(R.string.cancel, null)
                .create();
        if (isReceiver && TaskEntity.STATUS_PENDING.equals(e.status)) {
            btnPrimary.setText(R.string.task_accept);
            btnPrimary.setVisibility(View.VISIBLE);
            btnPrimary.setOnClickListener(v -> {
                MonitorService.sendTaskRespond(this, e.taskId, "accept", null);
                Toast.makeText(this, R.string.task_toast_accept, Toast.LENGTH_SHORT).show();
                dlg.dismiss();
                reloadSoon();
            });
            btnSecondary.setText(R.string.task_reject);
            btnSecondary.setVisibility(View.VISIBLE);
            btnSecondary.setOnClickListener(v -> {
                dlg.dismiss();
                showRejectDialog(e);
            });
        } else if (e.isMine && TaskEntity.STATUS_ACCEPTED.equals(e.status)) {
            btnPrimary.setText(R.string.task_confirm_complete);
            btnPrimary.setVisibility(View.VISIBLE);
            btnPrimary.setOnClickListener(v -> {
                MonitorService.sendTaskComplete(this, e.taskId);
                Toast.makeText(this, R.string.task_toast_complete, Toast.LENGTH_SHORT).show();
                dlg.dismiss();
                reloadSoon();
            });
        } else if (isReceiver && TaskEntity.STATUS_COMPLETED.equals(e.status)) {
            btnPrimary.setText(R.string.task_confirm_reward);
            btnPrimary.setVisibility(View.VISIBLE);
            btnPrimary.setOnClickListener(v -> {
                MonitorService.sendTaskReward(this, e.taskId);
                Toast.makeText(this, R.string.task_toast_reward, Toast.LENGTH_SHORT).show();
                dlg.dismiss();
                reloadSoon();
            });
        }
        dlg.show();
    }

    /** 时间线行：●/○ + 文字 + 时间 */
    private void addTimelineRow(LinearLayout tl, String label, String time, boolean done, int dotColor) {
        LinearLayout row = row();
        TextView dot = new TextView(this);
        dot.setText(done ? "●" : "○");
        dot.setTextSize(10);
        dot.setTextColor(getResources().getColor(dotColor));
        row.addView(dot);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(getResources().getColor(done ? R.color.text_primary : R.color.text_secondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        tv.setLayoutParams(lp);
        row.addView(tv, lp(0, 1f));
        TextView tvTime = new TextView(this);
        tvTime.setText(time);
        tvTime.setTextSize(11);
        tvTime.setTextColor(getResources().getColor(R.color.text_secondary));
        row.addView(tvTime);
        tl.addView(row);
    }

    /** 拒绝：预置理由 chips（Material ChipGroup）+ 自定义输入（必填） */
    private void showRejectDialog(final TaskEntity e) {
        View body = getLayoutInflater().inflate(R.layout.dialog_task_reject, null);
        final EditText etReason = body.findViewById(R.id.et_task_reject_reason);
        com.google.android.material.chip.ChipGroup cg = body.findViewById(R.id.cg_reject);
        cg.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (group.getCheckedChipId() != View.NO_ID) {
                com.google.android.material.chip.Chip chip = group.findViewById(group.getCheckedChipId());
                if (chip != null) {
                    etReason.setText(chip.getText());
                    etReason.setSelection(etReason.length());
                }
            }
        });

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.task_reject_reason_title)
                .setView(body)
                .setPositiveButton(R.string.task_reject, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String reason = etReason.getText().toString().trim();
                    if (reason.isEmpty()) {
                        Toast.makeText(this, R.string.task_reject_need_reason, Toast.LENGTH_SHORT).show();
                        return; // 不关闭
                    }
                    MonitorService.sendTaskRespond(this, e.taskId, "reject", reason);
                    Toast.makeText(this, R.string.task_toast_reject, Toast.LENGTH_SHORT).show();
                    reloadSoon();
                    dlg.dismiss();
                }));
        dlg.show();
    }

    // --- 发布弹窗（文字 + 奖励预置 ChipGroup/自定义 + 可选配图） ---

    private void showPublishDialog() {
        publishBody = getLayoutInflater().inflate(R.layout.dialog_task_publish, null);
        final View body = publishBody;
        final EditText etContent = body.findViewById(R.id.et_task_content);
        final EditText etRewardCustom = body.findViewById(R.id.et_task_reward_custom);
        final TextView tvPhoto = body.findViewById(R.id.tv_task_photo_state);
        final com.google.android.material.chip.ChipGroup cgReward = body.findViewById(R.id.cg_reward);

        // 自定义奖励输入时取消预置选中（单选互斥）
        etRewardCustom.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (s != null && s.length() > 0) {
                    cgReward.clearCheck();
                }
            }
        });

        // 配图条（已有图=点行清空；空=去选图；条内每格 ✕ 可单独删）
        renderPhotoStrip();
        body.findViewById(R.id.ll_task_photo).setOnClickListener(v -> {
            if (!pendingPhotoFileIds.isEmpty()) {
                pendingPhotoFileIds.clear();
                renderPhotoStrip();
                Toast.makeText(this, R.string.task_remove_photo, Toast.LENGTH_SHORT).show();
            } else {
                startPhotoPick();
            }
        });

        // 自定义按钮点击：校验失败不关弹窗（Material 默认点完即 dismiss）
        publishDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.task_publish_title)
                .setView(body)
                .setPositiveButton(R.string.task_publish_send, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        publishDialog.setOnShowListener(d -> publishDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String content = etContent.getText().toString().trim();
                    if (content.isEmpty()) {
                        Toast.makeText(this, R.string.task_publish_empty, Toast.LENGTH_SHORT).show();
                        return; // 不关闭
                    }
                    // 奖励：预置选中（ChipGroup）或 自定义非空，二选一
                    String preset = null;
                    if (cgReward.getCheckedChipId() != View.NO_ID) {
                        com.google.android.material.chip.Chip chip =
                                cgReward.findViewById(cgReward.getCheckedChipId());
                        if (chip != null && chip.getText() != null) {
                            preset = chip.getText().toString();
                        }
                    }
                    String custom = etRewardCustom.getText().toString().trim();
                    if (preset == null && custom.isEmpty()) {
                        Toast.makeText(this, R.string.task_reward_empty, Toast.LENGTH_SHORT).show();
                        return; // 不关闭
                    }
                    String taskId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    String ids = TextUtils.join(",", pendingPhotoFileIds);
                    MonitorService.sendTaskPublish(this, taskId, content,
                            ids.isEmpty() ? null : ids, preset, custom.isEmpty() ? preset : custom);
                    pendingPhotoFileIds.clear();
                    Toast.makeText(this, R.string.task_toast_sent, Toast.LENGTH_SHORT).show();
                    reloadSoon();
                    publishDialog.dismiss();
                }));
        publishDialog.show();
    }

    /** 打开选图器（最多 9 张，追加到上传队列） */
    private void startPhotoPick() {
        Intent pick = new Intent(this, MediaPickerActivity.class);
        startActivityForResult(pick, REQ_PICK_PHOTO);
    }

    /** 渲染配图条：每格 72dp 缩略图 + ✕移除（点图放大预览），末尾 + 添加格 */
    private void renderPhotoStrip() {
        if (publishBody == null) return;
        LinearLayout strip = publishBody.findViewById(R.id.ll_photo_strip);
        if (strip == null) return;
        strip.removeAllViews();
        for (int i = 0; i < pendingPhotoFileIds.size(); i++) {
            final String fid = pendingPhotoFileIds.get(i);
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(dp(72), dp(72));
            cellLp.rightMargin = dp(6);
            cell.setLayoutParams(cellLp);

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_media_placeholder);
            FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            iv.setLayoutParams(ivLp);
            File f = MediaUtils.localMediaFile(this, fid);
            if (f != null && f.exists()) {
                com.bumptech.glide.Glide.with(this).load(f).centerCrop().into(iv);
            }
            iv.setOnClickListener(v -> showPhotoPreview(fid));
            cell.addView(iv);

            TextView x = new TextView(this);
            x.setText("✕");
            x.setTextSize(11);
            x.setTextColor(getResources().getColor(R.color.white));
            x.setBackgroundResource(R.drawable.bg_btn_white_rect);
            x.setPadding(dp(4), dp(1), dp(4), dp(1));
            FrameLayout.LayoutParams xLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.TOP);
            x.setLayoutParams(xLp);
            x.setOnClickListener(v -> {
                pendingPhotoFileIds.remove(fid);
                renderPhotoStrip();
            });
            cell.addView(x);
            strip.addView(cell);
        }
        // + 添加格（上限 9 张）
        if (pendingPhotoFileIds.size() < 9) {
            TextView add = new TextView(this);
            add.setText("＋");
            add.setTextSize(22);
            add.setTextColor(getResources().getColor(R.color.text_secondary));
            add.setGravity(Gravity.CENTER);
            add.setBackgroundResource(R.drawable.bg_btn_round_white);
            LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(dp(72), dp(72));
            add.setLayoutParams(addLp);
            add.setOnClickListener(v -> startPhotoPick());
            strip.addView(add);
        }
        // 标签：添加配图 / 已选 N 张
        TextView tvPhoto = publishBody.findViewById(R.id.tv_task_photo_state);
        if (tvPhoto != null) {
            tvPhoto.setText(pendingPhotoFileIds.isEmpty()
                    ? getString(R.string.task_add_photo)
                    : getString(R.string.task_photo_count, pendingPhotoFileIds.size()));
        }
    }

    /** 全屏放大预览（本地文件缺失时先下载） */
    private void showPhotoPreview(final String fileId) {
        final ImageView big = new ImageView(this);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        big.setBackgroundColor(0xFF000000);
        big.setClickable(true);
        final android.app.Dialog dlg = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.setContentView(big);
        big.setOnClickListener(v -> dlg.dismiss());
        File f = MediaUtils.localMediaFile(this, fileId);
        if (f != null && f.exists()) {
            com.bumptech.glide.Glide.with(this).load(f).into(big);
        } else {
            MediaUtils.ensureTaskMedia(this, fileId, null, new MediaUtils.MediaCb() {
                @Override
                public void onReady(String localPath) {
                    runOnUiThread(() -> com.bumptech.glide.Glide.with(TaskActivity.this)
                            .load(new File(localPath)).into(big));
                }

                @Override
                public void onError(int code, String msg) {
                    runOnUiThread(() -> Toast.makeText(TaskActivity.this,
                            R.string.media_download_failed, Toast.LENGTH_SHORT).show());
                }
            });
        }
        dlg.show();
    }

    /** 选图器回程：全部入队 → 逐张上传 */
    @Override
    protected void onDestroy() {
        if (publishDialog != null && publishDialog.isShowing()) {
            publishDialog.dismiss();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_PHOTO && resultCode == RESULT_OK && data != null) {
            ArrayList<Uri> uris = data.getParcelableArrayListExtra(MediaPickerActivity.EXTRA_SELECTED_URIS);
            if (uris != null && !uris.isEmpty()) {
                uploadToastShown = false;
                for (Uri u : uris) {
                    if (pendingPhotoFileIds.size() + photoUploadQueue.size() >= 9) break;
                    photoUploadQueue.offer(u);
                }
                uploadNextPhoto();
            }
        }
    }

    /** 逐张上传队列中的下一张 */
    private void uploadNextPhoto() {
        final Uri uri = photoUploadQueue.poll();
        if (uri == null) return;
        try {
            String mime = getContentResolver().getType(uri);
            if (mime == null) mime = "image/jpeg";
            if (mime.startsWith("video")) {
                Toast.makeText(this, R.string.media_pick_failed, Toast.LENGTH_SHORT).show();
                uploadNextPhoto();
                return;
            }
            // 临时文件必须带扩展名：AuthManager 按文件名推 mime，无扩展名会被服务器拒（400）
            String ext = "jpg";
            if (mime.contains("png")) ext = "png";
            else if (mime.contains("webp")) ext = "webp";
            File tmp = new File(getCacheDir(), "task_photo_" + System.currentTimeMillis() + "." + ext);
            if (!MediaUtils.copyUriToFile(this, uri, tmp)) {
                Toast.makeText(this, R.string.media_pick_failed, Toast.LENGTH_SHORT).show();
                uploadNextPhoto();
                return;
            }
            if (!uploadToastShown) {
                uploadToastShown = true;
                Toast.makeText(this, R.string.media_uploading, Toast.LENGTH_SHORT).show();
            }
            String pairCode = new PrefsManager(this).getPairCode();
            // 任务专用通道：taskOnly → 服务器标记，不进共享图库/不广播媒体气泡
            AuthManager.i(this).uploadTaskMedia(this, tmp, pairCode, new AuthManager.Callback() {
                @Override
                public void onSuccess(com.google.gson.JsonObject data) {
                    final String fileId = data.has("fileId") ? data.get("fileId").getAsString() : null;
                    if (fileId == null || fileId.isEmpty()) {
                        tmp.delete();
                        runOnUiThread(() -> Toast.makeText(TaskActivity.this,
                                R.string.media_upload_failed, Toast.LENGTH_SHORT).show());
                        uploadNextPhoto();
                        return;
                    }
                    // 本地归档（任务气泡/弹窗缩略图渲染数据源；不进 media_cache，避免出现在共享图库）
                    File dst = MediaUtils.localMediaFile(TaskActivity.this, fileId);
                    boolean archived = dst.exists() && dst.length() > 0
                            || MediaUtils.copyUriToFile(TaskActivity.this, uri, dst);
                    if (!archived) archived = tmp.renameTo(dst);
                    tmp.delete();
                    // 就地更新发布弹窗图片条（不重开弹窗，避免对话框叠加）
                    runOnUiThread(() -> {
                        pendingPhotoFileIds.add(fileId);
                        renderPhotoStrip();
                        uploadNextPhoto();
                    });
                }

                @Override
                public void onError(int code, String msg) {
                    tmp.delete();
                    runOnUiThread(() -> {
                        Toast.makeText(TaskActivity.this,
                                getString(R.string.media_upload_failed, msg), Toast.LENGTH_SHORT).show();
                        uploadNextPhoto();
                    });
                }
            });
        } catch (Exception ex) {
            Toast.makeText(this, R.string.media_upload_failed, Toast.LENGTH_SHORT).show();
            uploadNextPhoto();
        }
    }

    /** 逗号串第一项 */
    private static String firstId(String ids) {
        if (ids == null) return null;
        String t = ids.split(",")[0].trim();
        return t.isEmpty() ? null : t;
    }

    // --- 小工具 ---

    private static String rewardDisplay(TaskEntity e) {
        if (e.rewardText != null && !e.rewardText.isEmpty()) return e.rewardText;
        if (e.rewardType != null && !e.rewardType.isEmpty()) return e.rewardType;
        return null;
    }

    private static String statusDisplay(TaskEntity e) {
        switch (e.status == null ? "" : e.status) {
            case TaskEntity.STATUS_PENDING: return "⏳ 待响应";
            case TaskEntity.STATUS_ACCEPTED: return "⏳ 已接受 · 待确认完成";
            case TaskEntity.STATUS_REJECTED: return "💔 已拒绝";
            case TaskEntity.STATUS_COMPLETED: return "❤ 已完成 · 待兑现";
            case TaskEntity.STATUS_REWARDED: return "✅ 已兑现 🎉";
            default: return e.status;
        }
    }

    private static int statusColor(TaskEntity e) {
        switch (e.status == null ? "" : e.status) {
            case TaskEntity.STATUS_REJECTED: return R.color.status_error;
            case TaskEntity.STATUS_REWARDED: return R.color.status_success;
            case TaskEntity.STATUS_COMPLETED: return R.color.status_ok;
            default: return R.color.text_secondary;
        }
    }

    private static String fmtTime(long ts) {
        if (ts <= 0) return "";
        return new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(String s, int sp, boolean bold, int colorRes) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getResources().getColor(colorRes));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, float weight) {
        return new LinearLayout.LayoutParams(w == 0
                ? ViewGroup.LayoutParams.WRAP_CONTENT : w,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

}
