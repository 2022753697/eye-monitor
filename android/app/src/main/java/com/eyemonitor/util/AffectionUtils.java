package com.eyemonitor.util;

import java.util.Calendar;
import java.util.Locale;

/**
 * 好感度/等级系统小工具（打卡时段 / 契约常量）。
 */
public final class AffectionUtils {

    private AffectionUtils() {}

    /** REST 兜底拉取路径（契约：GET /api/affection；服务端统一 /api/** 前缀，与其它 controller 一致） */
    public static final String AFFECTION_API = "/api/affection";

    /** 打卡窗口：早安 5:00-11:00 */
    public static final String WINDOW_MORNING = "morning";
    /** 打卡窗口：晚安 19:00-24:00 */
    public static final String WINDOW_EVENING = "evening";

    /** 当前所处打卡窗口（不在窗口返回 null）：morning | evening */
    public static String currentWindow(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 11) return WINDOW_MORNING;
        if (hour >= 19 && hour < 24) return WINDOW_EVENING;
        return null;
    }

    /** 今日日期 yyyy-MM-dd（打卡单窗口单次判定：本地偏好记录，服务端 dedupKey 兜底） */
    public static String todayDate() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
