package com.eyemonitor.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.eyemonitor.R;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.MemoDao;
import com.eyemonitor.db.MemoEntity;
import com.eyemonitor.db.MemoItemEntity;
import com.eyemonitor.util.MemoImageStore;
import com.eyemonitor.util.ReminderScheduler;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 备忘录编辑页（新建/编辑同页）。
 * <p>
 * 正文 + 本地图片条（相册多选→本地拷贝）+ 子项编辑 + 定时提醒（一次性）+ 置顶。
 */
public class MemoEditActivity extends BaseActivity {

    private static final int REQ_PICK_MEMO_IMAGE = 8001;

    private long memoId = -1;
    private final List<String> selectedImages = new ArrayList<>();
    /** 子项编辑行：id(>0=既有, -1=新增), 文本 */
    private final List<long[]> itemIds = new ArrayList<>();
    private final List<EditText> itemEdits = new ArrayList<>();
    private Long reminderAt = null; // ms

    private EditText etContent;
    private LinearLayout imagesStrip, itemsBox, llReminderTime;
    private Switch swReminder, swPin;
    private TextView tvReminderTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memo_edit);

        memoId = getIntent().getLongExtra("memo_id", -1);

        findViewById(R.id.btn_memo_edit_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_memo_save).setOnClickListener(v -> save());

        etContent = findViewById(R.id.et_memo_content);
        imagesStrip = findViewById(R.id.ll_memo_edit_images);
        itemsBox = findViewById(R.id.ll_memo_edit_items);
        llReminderTime = findViewById(R.id.ll_memo_reminder_time);
        swReminder = findViewById(R.id.sw_memo_reminder);
        swPin = findViewById(R.id.sw_memo_pin);
        tvReminderTime = findViewById(R.id.tv_memo_reminder_time);

        ((TextView) findViewById(R.id.tv_memo_edit_title))
                .setText(memoId < 0 ? R.string.memo_edit_new : R.string.memo_edit_edit);

        findViewById(R.id.btn_memo_item_add).setOnClickListener(v -> addItemRow("", -1));

        swReminder.setOnCheckedChangeListener((b, on) -> {
            llReminderTime.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on && reminderAt == null) {
                reminderAt = System.currentTimeMillis() + 3600_000L; // 默认 now+1h
                updateReminderText();
            }
        });
        llReminderTime.setOnClickListener(v -> pickDateTime());

        if (memoId >= 0) {
            loadExisting();
        } else {
            addItemRow("", -1);
            imagesStrip.post(this::renderImageStrip);
        }
    }

    private void loadExisting() {
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            MemoDao dao = db.memoDao();
            MemoEntity memo = dao.getById(memoId);
            List<MemoItemEntity> items = dao.getItems(memoId);
            runOnUiThread(() -> {
                if (memo == null) { finish(); return; }
                etContent.setText(memo.content);
                if (memo.images != null && !memo.images.isEmpty()) {
                    for (String s : memo.images.split(",")) {
                        if (!s.trim().isEmpty()) selectedImages.add(s.trim());
                    }
                }
                swPin.setChecked(memo.isPinned);
                reminderAt = memo.reminderAt;
                swReminder.setChecked(reminderAt != null);
                if (reminderAt != null) {
                    llReminderTime.setVisibility(View.VISIBLE);
                    updateReminderText();
                }
                renderImageStrip();
                if (items.isEmpty()) {
                    addItemRow("", -1);
                } else {
                    for (MemoItemEntity it : items) addItemRow(it.text, it.id);
                }
            });
        });
    }

    // ---------- 图片条 ----------

    private void renderImageStrip() {
        imagesStrip.removeAllViews();
        for (int i = 0; i < selectedImages.size(); i++) {
            final String path = selectedImages.get(i);
            final int idx = i;
            ImageView iv = new ImageView(this);
            int sz = dp(64);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.setMarginEnd(dp(8));
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_card_alt);
            com.bumptech.glide.Glide.with(this).load(new java.io.File(path)).into(iv);
            iv.setOnClickListener(v ->
                    com.eyemonitor.util.MediaUtils.openLocalPhotoPreview(this, path));
            iv.setOnLongClickListener(v -> {
                selectedImages.remove(idx);
                renderImageStrip();
                return true;
            });
            imagesStrip.addView(iv);
        }
        // 「+」加图
        ImageView add = new ImageView(this);
        int sz = dp(64);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.setMarginEnd(dp(8));
        add.setLayoutParams(lp);
        add.setScaleType(ImageView.ScaleType.CENTER);
        add.setBackgroundResource(R.drawable.bg_card_alt);
        add.setImageResource(R.drawable.ic_plus);
        add.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.primary)));
        add.setOnClickListener(v -> openPicker());
        imagesStrip.addView(add);
    }

    private void openPicker() {
        try {
            startActivityForResult(new Intent(this, MediaPickerActivity.class), REQ_PICK_MEMO_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.media_pick_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_MEMO_IMAGE && resultCode == RESULT_OK && data != null) {
            ArrayList<Uri> uris = data.getParcelableArrayListExtra(
                    MediaPickerActivity.EXTRA_SELECTED_URIS);
            List<Uri> pick = uris != null ? uris
                    : (data.getData() != null ? java.util.Collections.singletonList(data.getData())
                    : java.util.Collections.emptyList());
            int before = selectedImages.size();
            for (Uri uri : pick) {
                String path = MemoImageStore.saveImage(this, uri);
                if (path != null) {
                    selectedImages.add(path);
                } else {
                    Toast.makeText(this, R.string.memo_err_image, Toast.LENGTH_SHORT).show();
                }
            }
            if (selectedImages.size() != before) renderImageStrip();
        }
    }

    // ---------- 子项编辑 ----------

    private void addItemRow(String text, long existingId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(rlp);

        EditText et = new EditText(this);
        et.setTextSize(14);
        et.setTextColor(getColor(R.color.text_primary));
        et.setHint(R.string.memo_item_hint);
        et.setText(text);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(elp);
        row.addView(et);

        ImageView del = new ImageView(this);
        del.setImageResource(R.drawable.ic_plus);
        del.setRotation(45f); // ×
        del.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text_secondary)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(24), dp(24));
        dlp.setMarginStart(dp(4));
        del.setLayoutParams(dlp);
        del.setOnClickListener(v -> {
            int pos = itemEdits.indexOf(et);
            if (pos >= 0) {
                long id = itemIds.get(pos)[0];
                if (id > 0) { /* 已入库的留在 DB，保存时全量替换 */ }
                itemIds.remove(pos);
                itemEdits.remove(pos);
                itemsBox.removeView(row);
            }
        });
        row.addView(del);

        itemIds.add(new long[]{existingId, 0});
        itemEdits.add(et);
        itemsBox.addView(row);
    }

    // ---------- 提醒 ----------

    private void updateReminderText() {
        if (reminderAt == null) return;
        tvReminderTime.setText(fmtTime(reminderAt));
    }

    private void pickDateTime() {
        Calendar c = Calendar.getInstance();
        if (reminderAt != null) c.setTimeInMillis(reminderAt);
        new android.app.DatePickerDialog(this, (dv, y, m, d) -> {
            final int year = y, month = m, day = d;
            new android.app.TimePickerDialog(this, (tv, h, mi) -> {
                Calendar cal = Calendar.getInstance();
                cal.set(year, month, day, h, mi, 0);
                cal.set(Calendar.MILLISECOND, 0);
                reminderAt = cal.getTimeInMillis();
                updateReminderText();
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ---------- 保存 ----------

    private void save() {
        String content = etContent.getText().toString().trim();
        if (content.isEmpty()) {
            Toast.makeText(this, R.string.memo_err_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final Long reminder = swReminder.isChecked() ? reminderAt : null;

        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            MemoDao dao = db.memoDao();
            long now = System.currentTimeMillis();
            MemoEntity memo = memoId >= 0 ? dao.getById(memoId) : new MemoEntity();
            if (memo == null) memo = new MemoEntity();
            memo.content = content;
            memo.images = selectedImages.isEmpty() ? null : String.join(",", selectedImages);
            memo.reminderAt = reminder;
            memo.isPinned = swPin.isChecked();
            memo.updatedAt = now;
            if (memoId >= 0) {
                dao.update(memo);
            } else {
                memo.createdAt = now;
                memoId = dao.insert(memo);
            }
            // 子项全量替换（删旧插新，保留勾选态：编辑页不展示勾选，默认 false）
            dao.deleteItems(memoId);
            List<MemoItemEntity> newItems = new ArrayList<>();
            for (int i = 0; i < itemEdits.size(); i++) {
                String t = itemEdits.get(i).getText().toString().trim();
                if (t.isEmpty()) continue;
                MemoItemEntity it = new MemoItemEntity();
                it.memoId = memoId;
                it.text = t;
                it.checked = false;
                newItems.add(it);
            }
            if (!newItems.isEmpty()) dao.insertItems(newItems);

            // 提醒重排：先取消旧，再排新（若有）；新备忘录需拿到新 id 后才可排期
            ReminderScheduler.cancel(this, memoId);
            if (reminder != null) ReminderScheduler.schedule(this, memoId, reminder);

            runOnUiThread(() -> {
                Toast.makeText(this, R.string.memo_save, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private static String fmtTime(long ts) {
        if (ts <= 0) return "";
        return new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }
}
