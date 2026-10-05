package com.eyemonitor.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Phase 5 消息搜索筛选参数计算（纯逻辑，JVM 可测）。
 * <p>
 * 类型码 → Room kind 列表；时间窗口 → startTs。
 */
public final class SearchFilter {

    private SearchFilter() {}

    public static final String TYPE_ALL = "all";
    public static final String TYPE_TEXT = "chat";
    public static final String TYPE_MEDIA = "media_img";
    public static final String TYPE_VOICE = "media_voice";
    public static final String TYPE_SYSTEM = "system";

    public static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** 类型码 → kind 列表（null = 全部类型） */
    public static List<String> kindsFor(String type) {
        switch (type) {
            case TYPE_TEXT:
                return Arrays.asList("chat");
            case TYPE_MEDIA:
                return Arrays.asList("media");
            case TYPE_VOICE:
                return Arrays.asList("voice", "media");
            case TYPE_SYSTEM:
                return Arrays.asList("system");
            case TYPE_ALL:
            default:
                return Arrays.asList("chat", "media", "voice", "system", "task");
        }
    }

    /** 时间窗口（天）→ 起始 ts（0 = 全部时间；1/7/30 = 近 N 天） */
    public static long startTsFor(int windowDays, long now) {
        if (windowDays <= 0) return 0L;
        return now - (long) windowDays * DAY_MS;
    }

    /** 结果文本展示（media 气泡 text 是 fileId 无意义 → 类型占位） */
    public static String displayText(String kind, String text) {
        if (text == null) return "";
        if ("media".equals(kind)) return "[" + kindLabel(kind) + "]";
        if ("voice".equals(kind)) return "[" + kindLabel(kind) + "]";
        return text;
    }

    public static String kindLabel(String kind) {
        switch (kind) {
            case "chat": return "文本";
            case "media": return "图片/视频";
            case "voice": return "语音";
            case "task": return "任务";
            default: return "系统";
        }
    }
}
