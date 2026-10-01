package com.eyemonitor.util;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * 请求日志（调试面板）：记录最近发出的 API 请求与结果，显示在登录页底部。
 * 线程安全：任何线程可 add()，UI 更新在主线程回调。
 */
public final class RequestLog {

    private static final int MAX_LINES = 30;
    private static final LinkedList<String> LINES = new LinkedList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable listener;

    private RequestLog() {}

    public static synchronized void add(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
        LINES.addFirst("[" + ts + "] " + line);
        while (LINES.size() > MAX_LINES) {
            LINES.removeLast();
        }
        if (listener != null) {
            MAIN.post(listener);
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** UI 注册监听：每次新日志产生时在主线程回调一次 */
    public static synchronized void setListener(Runnable r) {
        listener = r;
    }
}