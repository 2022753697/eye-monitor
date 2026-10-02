package com.eyemonitor.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.MediaCacheEntity;
import com.eyemonitor.db.TaskEntity;
import com.eyemonitor.service.MonitorService;
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

    private static final int REQ_PICK_PHOTO = 1001;

    private RecyclerView rvTasks;
    private TextView tvEmpty;
    private LinearLayout llFilters;
    private final List<TaskEntity> all = new ArrayList<>();
    private int filterIndex = 0; // 0 全部 / 1 进行中 / 2 已完成 / 3 已拒绝
    private final List<TextView> filterChips = new ArrayList<>();

    /** 发布弹窗状态：选中的配图 fileId（从选图器回程后重新打开弹窗带入） */
    private String pendingPhotoFileId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task);

        findViewById(R.id.btn_task_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_task_publish).setOnClickListener(v -> showPublishDialog());

        rvTasks = findViewById(R.id.rv_tasks);
        rvTasks.setLayoutManager(new LinearLayoutManager(this));
        rvTasks.setAdapter(new TaskAdapter());
        llFilters = findViewById(R.id.ll_task_filters);
        tvEmpty = findViewById(R.id.tv_task_empty);
        buildFilterChips();

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
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
        if (tvEmpty != null) tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void reload() {
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            List<TaskEntity> list = db.taskDao().getAll();
            runOnUiThread(() -> {
                all.clear();
                all.addAll(list);
                render();
            });
        });
    }

    // --- 列表适配器 ---

    private class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.Holder> {
        private final List<TaskEntity> data = new ArrayList<>();

        void setData(List<TaskEntity> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = new LinearLayout(TaskActivity.this);
            ((LinearLayout) v).setOrientation(LinearLayout.VERTICAL);
            int pad = dp(12);
            v.setPadding(pad, pad, pad, pad);
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
            LinearLayout root;

            Holder(View itemView) {
                super(itemView);
                root = (LinearLayout) itemView;
            }

            void bind(TaskEntity e) {
                root.removeAllViews();
                root.setBackgroundResource(R.drawable.bg_card);
                root.setClickable(true);
                root.setFocusable(true);

                // 发布方 + 时间行
                LinearLayout head = row();
                String who = e.isMine ? getString(R.string.chat_self_name)
                        : (e.peerName != null && !e.peerName.isEmpty() ? e.peerName : getString(R.string.chat_title_default));
                TextView tvWho = text(who, 12, true, R.color.primary);
                TextView tvTime = text(fmtTime(e.ts), 11, false, R.color.text_secondary);
                head.addView(tvWho, lp(0, 1f));
                head.addView(tvTime);
                root.addView(head);

                // 任务内容
                root.addView(text(e.content != null ? e.content : "", 15, false, R.color.text_primary));

                // 奖励行
                String reward = rewardDisplay(e);
                if (reward != null && !reward.isEmpty()) {
                    root.addView(text(getString(R.string.task_reward_of, reward), 12, false, R.color.accent));
                }

                // 状态行
                TextView tvStatus = text(statusDisplay(e), 12, true, statusColor(e));
                root.addView(tvStatus);

                root.setOnClickListener(v -> showDetailDialog(e));
            }
        }
    }

    // --- 详情弹窗（任务信息 + 时间线 + 按角色×状态的操作按钮） ---

    private void showDetailDialog(final TaskEntity e) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        body.setPadding(pad, pad, pad, pad);

        String who = e.isMine ? getString(R.string.chat_self_name)
                : (e.peerName != null && !e.peerName.isEmpty() ? e.peerName : getString(R.string.chat_title_default));
        body.addView(text(getString(R.string.task_publisher_of, who), 12, true, R.color.primary));
        body.addView(text(e.content != null ? e.content : "", 16, false, R.color.text_primary));
        String reward = rewardDisplay(e);
        if (reward != null && !reward.isEmpty()) {
            body.addView(text(getString(R.string.task_reward_of, reward), 13, false, R.color.accent));
        }

        // 时间线
        body.addView(text(fmtTime(e.ts), 11, false, R.color.text_secondary));
        if (TaskEntity.STATUS_REJECTED.equals(e.status) && e.reason != null && !e.reason.isEmpty()) {
            body.addView(text(getString(R.string.task_rejected_at, e.reason), 12, false, R.color.status_error));
        }
        if (e.completedTs > 0) {
            body.addView(text(getString(R.string.task_completed_at, fmtTime(e.completedTs)), 12, false, R.color.status_ok));
        }
        if (e.rewardedTs > 0) {
            body.addView(text(getString(R.string.task_rewarded_at, fmtTime(e.rewardedTs)), 12, false, R.color.status_success));
        }
        body.addView(text(statusDisplay(e), 13, true, statusColor(e)));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.task_detail_title)
                .setView(body)
                .setNegativeButton(R.string.cancel, null)
                .create();

        // 操作按钮（按 角色×状态）
        boolean isReceiver = !e.isMine;
        if (isReceiver && TaskEntity.STATUS_PENDING.equals(e.status)) {
            dlg.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.task_accept),
                    (d, w) -> {
                        MonitorService.sendTaskRespond(this, e.taskId, "accept", null);
                        Toast.makeText(this, R.string.task_toast_accept, Toast.LENGTH_SHORT).show();
                        reload();
                    });
            dlg.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.task_reject),
                    (d, w) -> showRejectDialog(e));
        } else if (e.isMine && TaskEntity.STATUS_ACCEPTED.equals(e.status)) {
            dlg.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.task_confirm_complete),
                    (d, w) -> {
                        MonitorService.sendTaskComplete(this, e.taskId);
                        Toast.makeText(this, R.string.task_toast_complete, Toast.LENGTH_SHORT).show();
                        reload();
                    });
        } else if (isReceiver && TaskEntity.STATUS_COMPLETED.equals(e.status)) {
            dlg.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.task_confirm_reward),
                    (d, w) -> {
                        MonitorService.sendTaskReward(this, e.taskId);
                        Toast.makeText(this, R.string.task_toast_reward, Toast.LENGTH_SHORT).show();
                        reload();
                    });
        }
        dlg.show();
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
                .setPositiveButton(R.string.task_reject, (d, w) -> {
                    String reason = etReason.getText().toString().trim();
                    if (reason.isEmpty()) {
                        Toast.makeText(this, R.string.task_reject_need_reason, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    MonitorService.sendTaskRespond(this, e.taskId, "reject", reason);
                    Toast.makeText(this, R.string.task_toast_reject, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.show();
    }

    // --- 发布弹窗（文字 + 奖励预置 ChipGroup/自定义 + 可选配图） ---

    private void showPublishDialog() {
        View body = getLayoutInflater().inflate(R.layout.dialog_task_publish, null);
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

        // 配图行（pendingPhotoFileId 非空 = 已选，点击移除）
        if (pendingPhotoFileId != null) {
            tvPhoto.setText(R.string.task_remove_photo);
        }
        body.findViewById(R.id.ll_task_photo).setOnClickListener(v -> {
            if (pendingPhotoFileId != null) {
                pendingPhotoFileId = null;
                tvPhoto.setText(R.string.task_add_photo);
                Toast.makeText(this, R.string.task_remove_photo, Toast.LENGTH_SHORT).show();
            } else {
                Intent pick = new Intent(this, MediaPickerActivity.class);
                startActivityForResult(pick, REQ_PICK_PHOTO);
            }
        });

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.task_publish_title)
                .setView(body)
                .setPositiveButton(R.string.task_publish_send, (d, w) -> {
                    String content = etContent.getText().toString().trim();
                    if (content.isEmpty()) {
                        Toast.makeText(this, R.string.task_publish_empty, Toast.LENGTH_SHORT).show();
                        return;
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
                        return;
                    }
                    String taskId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    MonitorService.sendTaskPublish(this, taskId, content,
                            pendingPhotoFileId, preset, custom.isEmpty() ? preset : custom);
                    pendingPhotoFileId = null;
                    Toast.makeText(this, R.string.task_toast_sent, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 选图器回程：取第 1 张 → 拷贝 → 上传 → 拿 fileId 重新打开发布弹窗 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_PHOTO && resultCode == RESULT_OK && data != null) {
            ArrayList<Uri> uris = data.getParcelableArrayListExtra(MediaPickerActivity.EXTRA_SELECTED_URIS);
            if (uris != null && !uris.isEmpty()) {
                uploadTaskPhoto(uris.get(0));
            }
        }
    }

    private void uploadTaskPhoto(Uri uri) {
        try {
            File tmp = new File(getCacheDir(), "task_photo_" + System.currentTimeMillis());
            if (!MediaUtils.copyUriToFile(this, uri, tmp)) {
                Toast.makeText(this, R.string.media_pick_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            String mime = getContentResolver().getType(uri);
            if (mime != null && mime.startsWith("video")) {
                Toast.makeText(this, R.string.media_pick_failed, Toast.LENGTH_SHORT).show();
                tmp.delete();
                return;
            }
            Toast.makeText(this, R.string.media_uploading, Toast.LENGTH_SHORT).show();
            String pairCode = new PrefsManager(this).getPairCode();
            AuthManager.i(this).uploadMedia(this, tmp, pairCode, new AuthManager.Callback() {
                @Override
                public void onSuccess(com.google.gson.JsonObject data) {
                    final String fileId = data.has("fileId") ? data.get("fileId").getAsString() : null;
                    if (fileId == null || fileId.isEmpty()) {
                        tmp.delete();
                        runOnUiThread(() -> Toast.makeText(TaskActivity.this,
                                R.string.media_upload_failed, Toast.LENGTH_SHORT).show());
                        return;
                    }
                    // 本地归档（图库/气泡渲染数据源）
                    File dst = MediaUtils.localMediaFile(TaskActivity.this, fileId);
                    boolean archived = dst.exists() && dst.length() > 0
                            || MediaUtils.copyUriToFile(TaskActivity.this, uri, dst);
                    if (!archived) archived = tmp.renameTo(dst);
                    if (archived) {
                        MediaCacheEntity e = new MediaCacheEntity();
                        e.fileId = fileId;
                        e.mime = mime;
                        e.size = tmp.length();
                        e.ts = System.currentTimeMillis();
                        e.localPath = dst.getAbsolutePath();
                        AppDatabase.getInstance(TaskActivity.this).dbExecutor.execute(
                                () -> AppDatabase.getInstance(TaskActivity.this)
                                        .cacheDao().upsertMedia(e));
                    }
                    tmp.delete();
                    runOnUiThread(() -> {
                        pendingPhotoFileId = fileId;
                        showPublishDialog();
                    });
                }

                @Override
                public void onError(int code, String msg) {
                    tmp.delete();
                    runOnUiThread(() -> Toast.makeText(TaskActivity.this,
                            getString(R.string.media_upload_failed, msg), Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception ex) {
            Toast.makeText(this, R.string.media_upload_failed, Toast.LENGTH_SHORT).show();
        }
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
