package com.eyemonitor.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.eyemonitor.R;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.MemoDao;
import com.eyemonitor.db.MemoEntity;
import com.eyemonitor.db.MemoItemEntity;
import com.eyemonitor.util.MemoImageStore;
import com.eyemonitor.util.ReminderScheduler;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 备忘录详情页：只读正文 + 图片条（点击全屏）+ 子项勾选 + 删除。
 */
public class MemoDetailActivity extends BaseActivity {

    private long memoId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memo_detail);

        memoId = getIntent().getLongExtra("memo_id", -1);

        findViewById(R.id.btn_memo_detail_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_memo_edit).setOnClickListener(v -> {
            Intent i = new Intent(this, MemoEditActivity.class);
            i.putExtra("memo_id", memoId);
            startActivity(i);
        });
        findViewById(R.id.btn_memo_delete).setOnClickListener(v -> confirmDelete());

        if (memoId < 0) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.memo_err_empty);
            finish();
            return;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 编辑保存返回后自动刷新（编辑页 finish → 本页 onResume）
        if (memoId >= 0) load();
    }

    private void load() {
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            MemoDao dao = db.memoDao();
            MemoEntity memo = dao.getById(memoId);
            List<MemoItemEntity> items = dao.getItems(memoId);
            runOnUiThread(() -> {
                if (memo == null) {
                    com.eyemonitor.util.Toasts.showRes(this, R.string.memo_err_empty);
                    finish();
                    return;
                }
                render(memo, items);
            });
        });
    }

    private void render(MemoEntity memo, List<MemoItemEntity> items) {
        TextView tvContent = findViewById(R.id.tv_memo_content);
        tvContent.setText(memo.content);

        // 图片条（横向 LinearLayout，本地路径）
        LinearLayout strip = findViewById(R.id.ll_memo_detail_images);
        strip.setVisibility(View.GONE);
        List<String> imgs = splitImages(memo.images);
        if (!imgs.isEmpty()) {
            strip.setVisibility(View.VISIBLE);
            strip.removeAllViews();
            for (String path : imgs) {
                final String p = path;
                android.widget.ImageView iv = new android.widget.ImageView(this);
                int sz = dp(90);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
                lp.setMarginEnd(dp(8));
                iv.setLayoutParams(lp);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundResource(R.drawable.bg_card_alt);
                com.bumptech.glide.Glide.with(this).load(new java.io.File(p)).into(iv);
                iv.setOnClickListener(v ->
                        com.eyemonitor.util.MediaUtils.openLocalPhotoPreview(this, p));
                strip.addView(iv);
            }
        }

        // 子项勾选（CheckBox + 划线样式）
        LinearLayout itemsBox = findViewById(R.id.ll_memo_detail_items);
        itemsBox.removeAllViews();
        for (MemoItemEntity it : items) {
            final long itemId = it.id;
            final CheckBox cb = new CheckBox(this);
            cb.setTextAppearance(this, R.style.TextAppearance_EyeMonitor_Body);
            cb.setTextColor(getColor(R.color.text_primary));
            cb.setText(it.text);
            cb.setChecked(it.checked);
            applyStrike(cb, it.checked);
            cb.setOnCheckedChangeListener((b, checked) -> {
                applyStrike(cb, checked);
                AppDatabase.dbExecutor.execute(() ->
                        AppDatabase.getInstance(this).memoDao().setItemChecked(itemId, checked));
            });
            itemsBox.addView(cb);
        }

        // 信息行
        StringBuilder info = new StringBuilder();
        info.append(getString(R.string.memo_updated_at, fmtTime(memo.updatedAt)));
        if (memo.reminderAt != null) {
            info.append("\n").append(getString(R.string.memo_reminder_at, fmtTime(memo.reminderAt)));
        }
        if (memo.isPinned) {
            info.append("\n").append(getString(R.string.memo_pin));
        }
        ((TextView) findViewById(R.id.tv_memo_info)).setText(info.toString());
    }

    private void applyStrike(CheckBox cb, boolean checked) {
        if (checked) {
            SpannableString ss = new SpannableString(cb.getText());
            ss.setSpan(new StrikethroughSpan(), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            cb.setText(ss);
            cb.setTextColor(getColor(R.color.text_secondary));
        } else {
            // 重建纯文本，去掉残留的删除线 span
            CharSequence t = cb.getText();
            if (t instanceof Spanned) cb.setText(t.toString());
            cb.setTextColor(getColor(R.color.text_primary));
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.memo_delete_confirm)
                .setPositiveButton(R.string.memo_delete_ok, (d, w) -> doDelete())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doDelete() {
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            MemoDao dao = db.memoDao();
            MemoEntity memo = dao.getById(memoId);
            if (memo != null) MemoImageStore.deleteImages(memo.images);
            dao.delete(memoId);
        });
        ReminderScheduler.cancel(this, memoId);
        finish();
    }

    private static List<String> splitImages(String csv) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        if (csv == null || csv.isEmpty()) return list;
        for (String s : csv.split(",")) {
            if (!s.trim().isEmpty()) list.add(s.trim());
        }
        return list;
    }

    private static String fmtTime(long ts) {
        if (ts <= 0) return "";
        return new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }
}
