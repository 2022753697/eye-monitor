package com.eyemonitor.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.MemoDao;
import com.eyemonitor.db.MemoEntity;
import com.eyemonitor.util.ReminderScheduler;

/**
 * 备忘录提醒广播接收器：AlarmManager 到点触发 → 发系统通知 + 清除提醒态（一次性语义）。
 */
public class MemoReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "MemoReminderReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final long memoId = intent.getLongExtra("memo_id", -1);
        if (memoId < 0) return;
        final PendingResult result = goAsync();
        AppDatabase.dbExecutor.execute(() -> {
            try {
                MemoDao dao = AppDatabase.getInstance(context).memoDao();
                MemoEntity memo = dao.getById(memoId);
                if (memo != null) {
                    ReminderScheduler.notifyReminder(context, memo);
                    dao.clearReminder(memoId, System.currentTimeMillis());
                    Log.i(TAG, "提醒触发: memoId=" + memoId);
                }
            } catch (Exception e) {
                Log.w(TAG, "提醒处理失败", e);
            } finally {
                result.finish();
            }
        });
    }
}
