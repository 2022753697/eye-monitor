package com.eyemonitor.util;

import com.eyemonitor.db.AnniversaryCacheEntity;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 纪念日倒计时工具。
 * <p>
 * 规则：
 * - 一次性纪念日：日期即完整 yyyy-MM-dd，过期后没有下一次。
 * - 每年重复（repeat）：取月-日，今年已过则顺延到下一年（含闰年 2/29，非闰年由
 *   Calendar 宽松模式自动落到 3/1）。
 * - 今天当天：倒计时为 0，界面显示「今天」。
 */
public final class AnniversaryUtils {

    private AnniversaryUtils() {}

    /** 今天 0 点（日期对齐用，避免当天“已过”误判） */
    public static Calendar today() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    /** 解析 yyyy-MM-dd -> 当天 0 点 Calendar（失败返回 null） */
    public static Calendar parseDate(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.isEmpty()) return null;
        try {
            String[] p = yyyyMMdd.split("-");
            if (p.length != 3) return null;
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** 该纪念日是否今天到期（repeat 按月-日；一次性按完整日期） */
    public static boolean isAnniversaryToday(AnniversaryCacheEntity e, Calendar today) {
        Calendar c = parseDate(e.date);
        if (c == null) return false;
        if (e.repeat) {
            return c.get(Calendar.MONTH) == today.get(Calendar.MONTH)
                    && c.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH);
        }
        return sameDay(c, today);
    }

    /** 下一次纪念日相对今天的天数；-1 表示已过期且不重复（无下一次） */
    public static long daysUntilNext(AnniversaryCacheEntity e, Calendar today) {
        Calendar c = parseDate(e.date);
        if (c == null) return -1;
        Calendar next = nextOccurrence(c, e.repeat, today);
        if (next == null) return -1;
        return daysBetween(today, next);
    }

    /** 从缓存列表找倒计时最近的纪念日；无可用数据返回 null */
    public static AnniversaryCacheEntity findNearest(List<AnniversaryCacheEntity> list) {
        if (list == null || list.isEmpty()) return null;
        Calendar today = today();
        AnniversaryCacheEntity best = null;
        long bestDays = Long.MAX_VALUE;
        for (AnniversaryCacheEntity e : list) {
            long days = daysUntilNext(e, today);
            if (days < 0) continue;
            if (days < bestDays) {
                bestDays = days;
                best = e;
            }
        }
        return best;
    }

    /**
     * 「在一起」已过天数：自该纪念日日期起算的整天数（date <= 今天）。
     * 日期在未来返回 -1（尚未开始）；repeat 不影响该值（不按周期重置）。
     */
    public static long daysSinceStart(AnniversaryCacheEntity e, Calendar today) {
        Calendar c = parseDate(e.date);
        if (c == null) return -1;
        if (c.after(today)) return -1;
        return daysBetween(c, today);
    }

    /** 找「在一起」起始纪念日：所有 date <= 今天的条目里日期最早的一个；无则 null */
    public static AnniversaryCacheEntity findTogetherStart(List<AnniversaryCacheEntity> list) {
        if (list == null || list.isEmpty()) return null;
        Calendar today = today();
        AnniversaryCacheEntity best = null;
        Calendar bestDate = null;
        for (AnniversaryCacheEntity e : list) {
            Calendar c = parseDate(e.date);
            if (c == null || c.after(today)) continue;
            if (bestDate == null || c.before(bestDate)) {
                bestDate = c;
                best = e;
            }
        }
        return best;
    }

    /** 本地时区今天 yyyy-MM-dd */
    public static String todayString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().getTime());
    }

    /** 下一次发生日期；过期且不重复返回 null */
    private static Calendar nextOccurrence(Calendar anniversary, boolean repeat, Calendar today) {
        int month = anniversary.get(Calendar.MONTH);
        int day = anniversary.get(Calendar.DAY_OF_MONTH);
        int maxOffset = repeat ? 2 : 1;
        for (int yearOffset = 0; yearOffset < maxOffset; yearOffset++) {
            int year = today.get(Calendar.YEAR) + yearOffset;
            // 宽松模式：非闰年 2/29 自动滚动到 3/1
            Calendar cand = Calendar.getInstance();
            cand.clear();
            cand.set(year, month, day);
            if (cand.before(today)) continue;
            return cand;
        }
        return null;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** b 减 a 的天数（均为当天 0 点，DST 差值用四舍五入兜底） */
    private static long daysBetween(Calendar a, Calendar b) {
        long ms = b.getTimeInMillis() - a.getTimeInMillis();
        return Math.round(ms / (24 * 60 * 60 * 1000.0));
    }
}