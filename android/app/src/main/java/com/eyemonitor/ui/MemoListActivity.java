package com.eyemonitor.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.eyemonitor.R;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.MemoDao;
import com.eyemonitor.db.MemoEntity;
import com.eyemonitor.db.MemoRow;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 备忘录列表页（更多面板「备忘录」入口）。
 * <p>
 * 置顶在前、时间倒序；每条：缩略图 + 正文缩略 + 时间 + 置顶/提醒/子项角标。
 */
public class MemoListActivity extends BaseActivity {

    private final List<MemoEntity> all = new ArrayList<>();
    private MemoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memo_list);

        findViewById(R.id.btn_memo_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_memo_new).setOnClickListener(v ->
                startActivity(new Intent(this, MemoEditActivity.class)));

        RecyclerView rv = findViewById(R.id.rv_memos);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MemoAdapter();
        rv.setAdapter(adapter);

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        AppDatabase db = AppDatabase.getInstance(this);
        AppDatabase.dbExecutor.execute(() -> {
            List<MemoRow> rows = db.memoDao().getAllWithCount();
            runOnUiThread(() -> {
                all.clear();
                for (MemoRow r : rows) {
                    r.memo.itemCount = r.itemCount;
                    all.add(r.memo);
                }
                adapter.notifyDataSetChanged();
                boolean empty = all.isEmpty();
                findViewById(R.id.rv_memos).setVisibility(empty ? View.GONE : View.VISIBLE);
                findViewById(R.id.tv_memo_empty).setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        });
    }

    private static String fmtTime(long ts) {
        if (ts <= 0) return "";
        return new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    class MemoAdapter extends RecyclerView.Adapter<MemoAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(MemoListActivity.this)
                    .inflate(R.layout.item_memo_card, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            MemoEntity m = all.get(position);
            String snippet = m.content == null ? "" : m.content.replace("\n", " ").trim();
            if (snippet.length() > 60) snippet = snippet.substring(0, 60) + "…";
            h.tvSnippet.setText(snippet);
            h.tvTime.setText(fmtTime(m.updatedAt));

            // 缩略图：第一张
            String first = firstImage(m.images);
            if (first != null) {
                h.ivThumb.setVisibility(View.VISIBLE);
                h.ivPlaceholder.setVisibility(View.GONE);
                Glide.with(h.itemView).load(new File(first)).override(128, 128).centerCrop()
                        .into(h.ivThumb);
            } else {
                h.ivThumb.setVisibility(View.GONE);
                h.ivPlaceholder.setVisibility(View.VISIBLE);
            }

            // 角标
            h.ivPin.setVisibility(m.isPinned ? View.VISIBLE : View.GONE);
            h.ivAlarm.setVisibility(m.reminderAt != null ? View.VISIBLE : View.GONE);
            int itemCount = m.itemCount;
            if (itemCount > 0) {
                h.tvItems.setVisibility(View.VISIBLE);
                h.tvItems.setText(getString(R.string.memo_items_count, itemCount));
            } else {
                h.tvItems.setVisibility(View.GONE);
            }

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(MemoListActivity.this, MemoDetailActivity.class);
                i.putExtra("memo_id", m.id);
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() {
            return all.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView ivThumb, ivPlaceholder, ivPin, ivAlarm;
            final TextView tvSnippet, tvTime, tvItems;

            VH(View v) {
                super(v);
                ivThumb = v.findViewById(R.id.iv_memo_thumb);
                ivPlaceholder = v.findViewById(R.id.iv_memo_thumb_placeholder);
                ivPin = v.findViewById(R.id.iv_memo_pin);
                ivAlarm = v.findViewById(R.id.iv_memo_alarm);
                tvSnippet = v.findViewById(R.id.tv_memo_snippet);
                tvTime = v.findViewById(R.id.tv_memo_time);
                tvItems = v.findViewById(R.id.tv_memo_items);
            }
        }
    }

    private static String firstImage(String csv) {
        if (csv == null || csv.isEmpty()) return null;
        String f = csv.split(",")[0].trim();
        return f.isEmpty() ? null : f;
    }
}
