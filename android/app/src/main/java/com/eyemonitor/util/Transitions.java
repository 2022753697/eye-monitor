package com.eyemonitor.util;

import android.app.Activity;

import com.eyemonitor.R;

/**
 * 页面转场动效（UI 二期 B6）
 * 用法（startActivity 之后立即调用）：
 *   Transitions.push(activity) —— 普通页面右入（配合 fade）
 *   Transitions.up(activity)   —— 全屏媒体页由下而上
 *   Transitions.pop(activity)  —— 返回时右出（在 finish 前/后调用）
 */
public final class Transitions {

    private Transitions() {
    }

    public static void push(Activity a) {
        if (a == null) return;
        a.overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out);
    }

    public static void up(Activity a) {
        if (a == null) return;
        a.overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out);
    }

    public static void pop(Activity a) {
        if (a == null) return;
        a.overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right);
    }
}