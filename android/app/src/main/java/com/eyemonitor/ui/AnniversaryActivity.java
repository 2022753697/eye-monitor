package com.eyemonitor.ui;

import android.app.DatePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.db.AnniversaryCacheEntity;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.service.SyncManager;
import com.eyemonitor.util.AnniversaryUtils;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 纪念日页面（Wave2）。
 * <p>
 * 双方共同维护一份纪念日列表：列表读 Room 缓存（AnniversaryCacheEntity），
 * 增删改走服务端 REST /api/anniversaries（鉴权头由 AuthManager 提供），
 * 成功后立即刷新本地缓存，并依赖 anniversary_sync 广播保持两端一致。
 */
public class AnniversaryActivity extends AppCompatActivity {

    private static final String TAG = "AnniversaryActivity";

    private RecyclerView rvList;
    private TextView tvEmpty;
    private AnniversaryAdapter adapter;
    private volatile boolean busy;

    /** 收到 anniversary_sync 广播（任意一端变更）时重渲染列表 */
    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(MonitorService.EXTRA_EVENT_JSON);
            if (json == null) return;
            WsMessage msg = WsMessage.fromJson(json);
            if (msg != null && "anniversary_sync".equals(msg.getType())) {
                reload();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_anniversary);

        Button btnBack = findViewById(R.id.btn_anniversary_back);
        btnBack.setOnClickListener(v -> finish());
        Button btnAdd = findViewById(R.id.btn_anniversary_add);
        btnAdd.setOnClickListener(v -> showEditDialog(null));

        tvEmpty = findViewById(R.id.tv_anniversary_empty);
        rvList = findViewById(R.id.rv_anniversaries);
        rvList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AnniversaryAdapter();
        rvList.setAdapter(adapter);

        IntentFilter filter = new IntentFilter(MonitorService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(eventReceiver, filter);
        }

        reload();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(eventReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    /** 从 Room 缓存刷新列表（缓存由 SyncManager/anniversary_sync 维护，禁止主线程查 Room） */
    private void reload() {
        AppDatabase.dbExecutor.execute(() -> {
            List<AnniversaryCacheEntity> all =
                    AppDatabase.getInstance(this).cacheDao().getAnniversaries();
            runOnUiThread(() -> {
                adapter.setData(all);
                tvEmpty.setVisibility(all == null || all.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** 新增(null)或编辑(existing)对话框：名称 + 日期(DatePicker) + 每年重复开关 */
    private void showEditDialog(final AnniversaryCacheEntity existing) {
        final View form = getLayoutInflater().inflate(R.layout.dialog_anniversary_edit, null);
        final EditText etName = form.findViewById(R.id.et_anni_name);
        final EditText etDate = form.findViewById(R.id.et_anni_date);
        final SwitchCompat swRepeat = form.findViewById(R.id.sw_anni_repeat);

        final Calendar picked = Calendar.getInstance();
        if (existing != null) {
            etName.setText(existing.name);
            Calendar c = AnniversaryUtils.parseDate(existing.date);
            if (c != null) picked.setTimeInMillis(c.getTimeInMillis());
            etDate.setText(existing.date);
            swRepeat.setChecked(existing.repeat);
        }
        // 日期只走 DatePicker（focusable=false 防止弹出键盘）
        etDate.setOnClickListener(v -> new DatePickerDialog(this,
                (dp, year, month, dayOfMonth) -> {
                    picked.set(Calendar.YEAR, year);
                    picked.set(Calendar.MONTH, month);
                    picked.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    etDate.setText(String.format(Locale.getDefault(), "%04d-%02d-%02d",
                            year, month + 1, dayOfMonth));
                },
                picked.get(Calendar.YEAR), picked.get(Calendar.MONTH),
                picked.get(Calendar.DAY_OF_MONTH)).show());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.anniversary_dialog_add_title
                        : R.string.anniversary_dialog_edit_title)
                .setView(form)
                .setPositiveButton(R.string.anniversary_save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        // 手动接管确定按钮：校验输入 + 防连点
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(btn -> {
                    String name = etName.getText().toString().trim();
                    String date = etDate.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        Toast.makeText(this, R.string.anniversary_name_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (TextUtils.isEmpty(date)) {
                        Toast.makeText(this, R.string.anniversary_date_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!busy) save(existing, dialog, name, date, swRepeat.isChecked());
                }));
        dialog.show();
    }

    /** 调服务端 REST 落库（POST 新增 / PUT 编辑），成功后刷新本地缓存与列表 */
    private void save(AnniversaryCacheEntity existing, AlertDialog dialog,
                      String name, String date, boolean repeat) {
        if (busy) return;
        busy = true;
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("date", date);
        body.addProperty("repeat", repeat);

        AuthManager.Callback cb = new AuthManager.Callback() {
            @Override
            public void onSuccess(JsonObject data) {
                busy = false;
                // 服务端已落库：直接 upsert 本地缓存（与 dbExecutor 同线程队列，先落库后渲染）
                upsertLocal(data);
                SyncManager.syncAnniversaries(AnniversaryActivity.this);
                runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(AnniversaryActivity.this, R.string.anniversary_save_ok,
                            Toast.LENGTH_SHORT).show();
                    reload();
                });
            }

            @Override
            public void onError(int code, String msg) {
                busy = false;
                runOnUiThread(() -> Toast.makeText(AnniversaryActivity.this,
                        getString(R.string.anniversary_op_failed, msg != null ? msg : String.valueOf(code)),
                        Toast.LENGTH_LONG).show());
            }
        };
        if (existing != null) {
            AuthManager.i(this).putJson(this,
                    "/api/anniversaries/" + existing.serverId, body.toString(), cb);
        } else {
            AuthManager.i(this).postJson(this, "/api/anniversaries", body.toString(), cb);
        }
    }

    /** 将服务端返回的纪念日数据直接写入 Room 缓存 */
    private void upsertLocal(JsonObject data) {
        if (data == null) return;
        try {
            AnniversaryCacheEntity e = new AnniversaryCacheEntity();
            e.serverId = data.get("id").getAsLong();
            e.name = data.has("name") && !data.get("name").isJsonNull()
                    ? data.get("name").getAsString() : "";
            e.date = data.has("date") && !data.get("date").isJsonNull()
                    ? data.get("date").getAsString() : "";
            e.repeat = data.has("repeat") && data.get("repeat").getAsBoolean();
            e.isMine = true;
            e.updatedAt = data.has("updatedAt")
                    ? data.get("updatedAt").getAsLong() : System.currentTimeMillis();
            AppDatabase.dbExecutor.execute(() ->
                    AppDatabase.getInstance(this).cacheDao().upsertAnniversary(e));
        } catch (Exception ex) {
            Log.w(TAG, "本地缓存写入纪念日失败", ex);
        }
    }

    /** 删除确认 → DELETE → 清本地缓存并刷新 */
    private void delete(final AnniversaryCacheEntity entity) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.anniversary_delete_confirm_title)
                .setMessage(getString(R.string.anniversary_delete_confirm_message, entity.name))
                .setPositiveButton(R.string.anniversary_delete, (d, w) -> {
                    if (busy) return;
                    busy = true;
                    AuthManager.i(this).delete(this, "/api/anniversaries/" + entity.serverId,
                            new AuthManager.Callback() {
                                @Override
                                public void onSuccess(JsonObject data) {
                                    busy = false;
                                    AppDatabase.dbExecutor.execute(() ->
                                            AppDatabase.getInstance(AnniversaryActivity.this)
                                                    .cacheDao().deleteAnniversary(entity.serverId));
                                    SyncManager.syncAnniversaries(AnniversaryActivity.this);
                                    runOnUiThread(() -> {
                                        Toast.makeText(AnniversaryActivity.this,
                                                R.string.anniversary_delete_ok, Toast.LENGTH_SHORT).show();
                                        reload();
                                    });
                                }

                                @Override
                                public void onError(int code, String msg) {
                                    busy = false;
                                    runOnUiThread(() -> Toast.makeText(AnniversaryActivity.this,
                                            getString(R.string.anniversary_op_failed,
                                                    msg != null ? msg : String.valueOf(code)),
                                            Toast.LENGTH_LONG).show());
                                }
                            });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // --- 列表适配器 ---

    private class AnniversaryAdapter extends RecyclerView.Adapter<AnniversaryAdapter.ViewHolder> {
        private final List<AnniversaryCacheEntity> items = new ArrayList<>();
        private final Calendar today = AnniversaryUtils.today();

        void setData(List<AnniversaryCacheEntity> list) {
            items.clear();
            if (list != null) items.addAll(list);
            // 每次刷新重置为今天 0 点（避免跨天旧值）
            today.setTimeInMillis(AnniversaryUtils.today().getTimeInMillis());
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_anniversary, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int position) {
            final AnniversaryCacheEntity e = items.get(position);
            h.tvName.setText(e.name);
            h.tvDate.setText(e.date);
            h.tvRepeat.setVisibility(e.repeat ? View.VISIBLE : View.GONE);

            long days = AnniversaryUtils.daysUntilNext(e, today);
            if (days == 0) {
                h.tvCountdown.setText(R.string.anniversary_countdown_today_short);
            } else if (days > 0) {
                h.tvCountdown.setText(getString(R.string.anniversary_countdown_days_short, days));
            } else {
                h.tvCountdown.setText("");
            }
            h.btnEdit.setOnClickListener(v -> showEditDialog(e));
            h.btnDelete.setOnClickListener(v -> delete(e));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView tvName, tvDate, tvRepeat, tvCountdown;
            final ImageButton btnEdit, btnDelete;

            ViewHolder(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_anni_name);
                tvDate = v.findViewById(R.id.tv_anni_date);
                tvRepeat = v.findViewById(R.id.tv_anni_repeat);
                tvCountdown = v.findViewById(R.id.tv_anni_countdown);
                btnEdit = v.findViewById(R.id.btn_anni_edit);
                btnDelete = v.findViewById(R.id.btn_anni_delete);
            }
        }
    }
}