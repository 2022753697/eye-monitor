package com.eyemonitor.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eyemonitor.R;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.ChatEntity;
import com.eyemonitor.ui.BaseActivity;
import com.eyemonitor.util.Transitions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Phase 5 消息搜索：关键词 LIKE + 时间范围 + 类型筛选（Room 本地，限聊天记录）。
 * 结果点击回聊天页定位（EXTRA_TARGET_TS → MainActivity 滚动到该消息）。
 */
public class SearchActivity extends BaseActivity {

    public static final String EXTRA_TARGET_TS = "target_ts";


    private android.widget.EditText etSearchInput;
    private LinearLayoutManager llm;
    private RecyclerView rvResults;
    private TextView tvCount;
    private TextView tvEmpty;

    // 筛选态
    private List<String> selectedKinds;      // null = 全部类型
    private int timeWindowDays = 0;          // 0 = 全部时间；1/7/30 = 近 N 天
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    private final List<ChatEntity> results = new ArrayList<>();
    private final ResultAdapter adapter = new ResultAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        findViewById(R.id.btn_search_back).setOnClickListener(v -> finish());
        etSearchInput = findViewById(R.id.et_search_input);
        rvResults = findViewById(R.id.rv_search_results);
        tvCount = findViewById(R.id.tv_search_result_count);
        tvEmpty = findViewById(R.id.tv_search_empty);

        llm = new LinearLayoutManager(this);
        rvResults.setLayoutManager(llm);
        rvResults.setAdapter(adapter);

        buildTypeChips();
        buildTimeChips();

        // 输入即搜（防抖由 Room 查询开销决定，本地查询足够快）
        etSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { runSearch(); }
        });
        etSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });

        // 默认全量展示（便于「时间/类型筛选」浏览）
        runSearch();
    }

    /** 类型 chips：全部 / 文本 / 图片视频 / 语音 / 系统（对应 kind: * / chat / media / media(audio) / system） */
    private void buildTypeChips() {
        final String[][] types = {
                {"all", getString(R.string.search_all_types)},
                {"chat", getString(R.string.search_type_text)},
                {"media_img", getString(R.string.search_type_media)},
                {"media_voice", getString(R.string.search_type_voice)},
                {"system", getString(R.string.search_type_system)},
        };
        android.widget.LinearLayout host = findViewById(R.id.ll_type_chips);
        for (final String[] t : types) {
            TextView chip = chip(host, t[1], t[0].equals("all"));
            chip.setOnClickListener(v -> {
                selectedKinds = t[0].equals("all") ? null : kindsFor(t[0]);
                refreshChips(host, t[0].equals("all") ? "all" : t[0]);
                runSearch();
            });
        }
        refreshChips(host, "all");
    }

    /** 时间 chips：全部 / 今天 / 近7天 / 近30天 */
    private void buildTimeChips() {
        final int[][] times = {
                {0, 0, 0},            // 占位（all）——时间用 label 单独处理
        };
        final String[] labels = {getString(R.string.search_time_all),
                getString(R.string.search_time_today),
                getString(R.string.search_time_week),
                getString(R.string.search_time_month)};
        final int[] days = {0, 1, 7, 30};
        android.widget.LinearLayout host = findViewById(R.id.ll_time_chips);
        for (int i = 0; i < labels.length; i++) {
            final int d = days[i];
            TextView chip = chip(host, labels[i], d == 0);
            chip.setOnClickListener(v -> {
                timeWindowDays = d;
                refreshChips(host, String.valueOf(d));
                runSearch();
            });
        }
        refreshChips(host, "0");
    }

    private TextView chip(android.widget.LinearLayout host, String label, boolean selected) {
        // inflate item_search_chip（Chip 根 + 样式），选中态/无√由样式与 checkable 驱动
        com.google.android.material.chip.Chip chip = (com.google.android.material.chip.Chip)
                LayoutInflater.from(this).inflate(R.layout.item_search_chip, host, false);
        chip.setText(label);
        chip.setChecked(selected);
        host.addView(chip);
        return chip;
    }

    private void refreshChips(android.widget.LinearLayout host, String selectedKey) {
        for (int i = 0; i < host.getChildCount(); i++) {
            com.google.android.material.chip.Chip t =
                    (com.google.android.material.chip.Chip) host.getChildAt(i);
            // chips 无独立 id，用 text 匹配选中项（类型 chips 键=类型码，时间 chips 键=天数字）
            t.setChecked(t.getText().toString().equals(labelOf(selectedKey)));
        }
    }

    private String labelOf(String key) {
        switch (key) {
            case "all": return getString(R.string.search_all_types);
            case "chat": return getString(R.string.search_type_text);
            case "media_img": return getString(R.string.search_type_media);
            case "media_voice": return getString(R.string.search_type_voice);
            case "system": return getString(R.string.search_type_system);
            case "1": return getString(R.string.search_time_today);
            case "7": return getString(R.string.search_time_week);
            case "30": return getString(R.string.search_time_month);
            default: return getString(R.string.search_time_all);
        }
    }

    private List<String> kindsFor(String type) {
        return com.eyemonitor.util.SearchFilter.kindsFor(type);
    }

    /** Room 搜索（禁主线程：dbExecutor） */
    private void runSearch() {
        final String kw = etSearchInput.getText().toString().trim();
        final long now = System.currentTimeMillis();
        final long startTs = com.eyemonitor.util.SearchFilter.startTsFor(timeWindowDays, now);
        final List<String> kinds = selectedKinds == null
                ? com.eyemonitor.util.SearchFilter.kindsFor(com.eyemonitor.util.SearchFilter.TYPE_ALL)
                : selectedKinds;
        AppDatabase.dbExecutor.execute(() -> {
            List<ChatEntity> found = AppDatabase.getInstance(this)
                    .chatDao().search(kw, startTs, now + 60_000L, kinds);
            runOnUiThread(() -> {
                results.clear();
                results.addAll(found);
                adapter.notifyDataSetChanged();
                boolean empty = results.isEmpty();
                rvResults.setVisibility(empty ? View.GONE : View.VISIBLE);
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                tvCount.setVisibility(empty ? View.GONE : View.VISIBLE);
                if (!empty) {
                    tvCount.setText(getString(R.string.search_result_count, results.size()));
                }
            });
        });
    }

    /** 结果行：类型标签 + 时间 + 内容摘要 */
    class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(SearchActivity.this)
                    .inflate(R.layout.item_search_result, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            ChatEntity e = results.get(position);
            h.tvType.setText(typeLabel(e));
            h.tvTime.setText(timeFmt.format(new java.util.Date(e.timestamp)));
            h.tvText.setText(textOf(e));
            h.itemView.setOnClickListener(v -> {
                // 回聊天页定位（MainActivity onNewIntent 滚动）
                android.content.Intent go = new android.content.Intent(SearchActivity.this, MainActivity.class);
                go.putExtra(EXTRA_TARGET_TS, e.timestamp);
                go.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(go);
                Transitions.push(SearchActivity.this);
            });
        }

        @Override
        public int getItemCount() {
            return results.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            TextView tvType, tvTime, tvText;
            Holder(View v) {
                super(v);
                tvType = v.findViewById(R.id.tv_search_item_type);
                tvTime = v.findViewById(R.id.tv_search_item_time);
                tvText = v.findViewById(R.id.tv_search_item_text);
            }
        }
    }

    private String typeLabel(ChatEntity e) {
        if ("task".equals(e.kind)) return getString(R.string.search_type_text);
        return com.eyemonitor.util.SearchFilter.kindLabel(e.kind);
    }

    private String textOf(ChatEntity e) {
        return com.eyemonitor.util.SearchFilter.displayText(e.kind, e.text);
    }
}
