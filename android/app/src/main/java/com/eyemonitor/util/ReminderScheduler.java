package com.eyemonitor.util;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.eyemonitor.R;
import com.eyemonitor.db.AppDatabase;
import com.eyemonitor.db.MemoDao;
import com.eyemonitor.db.MemoEntity;
import com.eyemonitor.service.MemoReminderReceiver;
import com.eyemonitor.ui.MemoDetailActivity;

import java.util.List;

/**
 * 备忘录定时提醒：AlarmManager 一次性 + 系统通知。
 * <p>
 * 复用既有通知渠道 eye_monitor_channel；到点由 MemoReminderReceiver 发通知并清除提醒态。
 */
public final class ReminderScheduler {

    private static final String ACTION = "com.eyemonitor.action.MEMO_REMINDER";

    private ReminderScheduler() {}

    public static PendingIntent buildIntent(Context ctx, long memoId) {
        Intent i = new Intent(ctx, MemoReminderReceiver.class);
        i.setAction(ACTION + "_" + memoId);
        i.putExtra("memo_id", memoId);
        return PendingIntent.getBroadcast(ctx, (int) memoId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 排一个一次性提醒（RTC_WAKEUP + setAndAllowWhileIdle，免 SCHEDULE_EXACT_ALARM 权限） */
    public static void schedule(Context ctx, long memoId, long when) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, buildIntent(ctx, memoId));
        } catch (SecurityException e) {
            // 兜底：精确闹钟权限被拒时退化为普通 set
            am.set(AlarmManager.RTC_WAKEUP, when, buildIntent(ctx, memoId));
        }
    }

    /** 取消提醒（删除备忘录/编辑清除时） */
    public static void cancel(Context ctx, long memoId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(buildIntent(ctx, memoId));
    }

    /** 开机重排：所有未触发的提醒重新排期 */
    public static void rescheduleAll(Context ctx) {
        try {
            MemoDao dao = AppDatabase.getInstance(ctx).memoDao();
            AppDatabase.dbExecutor.execute(() -> {
                List<MemoEntity> pending = dao.getPendingReminders(System.currentTimeMillis());
                for (MemoEntity m : pending) {
                    schedule(ctx, m.id, m.reminderAt);
                }
            });
        } catch (Exception e) {
            // 静默（数据库未就绪等）
        }
    }

    /** 提醒通知：到点由 Receiver 调用（复用 eye_monitor_channel） */
    public static void notifyReminder(Context ctx, MemoEntity memo) {
        String content = memo.content == null ? "" : memo.content;
        String snippet = content.replace("\n", " ").trim();
        if (snippet.length() > 30) snippet = snippet.substring(0, 30) + "…";
        Intent tap = new Intent(ctx, MemoDetailActivity.class);
        tap.putExtra("memo_id", memo.id);
        tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) memo.id, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        androidx.core.app.NotificationCompat.Builder nb =
                new androidx.core.app.NotificationCompat.Builder(ctx, "eye_monitor_channel")
                        .setSmallIcon(R.drawable.ic_memo)
                        .setContentTitle(ctx.getString(R.string.memo_notify_title))
                        .setContentText(ctx.getString(R.string.memo_notify_text, snippet))
                        .setAutoCancel(true)
                        .setContentIntent(pi);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int) memo.id, nb.build());
    }
}
