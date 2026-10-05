package com.eyemonitor.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 打卡时段提醒接收器（AlarmManager.setWindow 非精确触发）。
 * <p>
 * 到点：发提醒通知（通知内一键打卡 PendingIntent → MonitorService ACTION_CHECK_IN），
 * 并重排次日同一窗口（Morning 5:00 / Evening 19:00）。
 */
public class CheckInReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "CheckInReminderReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !MonitorService.ACTION_CHECK_IN_REMINDER.equals(intent.getAction())) {
            return;
        }
        String window = intent.getStringExtra(MonitorService.EXTRA_CHECK_IN_WINDOW);
        if (window == null) {
            window = com.eyemonitor.util.AffectionUtils.currentWindow(System.currentTimeMillis());
        }
        Log.i(TAG, "打卡提醒触发: window=" + window);
        // 2026-10 用户决策：打卡不需要系统通知（想打就打），触发仅记录日志、不弹通知
        // 如需恢复：调用 MonitorService.showCheckInReminderNotification(context, window)
        // 重排次日提醒（双窗口各排一次，保证跨天/重启后不丢）
        MonitorService.scheduleCheckInReminders(context);
    }
}