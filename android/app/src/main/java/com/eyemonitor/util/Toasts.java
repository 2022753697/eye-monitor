package com.eyemonitor.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.eyemonitor.R;

/**
 * 统一 Toast（UI 改造 v2）：深色圆角 pill + 语义 icon，替换裸 Toast.makeText。
 * <p>
 * 职责：仅告知类反馈；确认类操作仍走 UiDialogs.confirm。不改任何业务参数/时长语义。
 */
public final class Toasts {

    private Toasts() {}

    /** 普通提示 */
    public static void show(Context ctx, CharSequence text) {
        show(ctx, text, 0, Toast.LENGTH_SHORT);
    }

    /** 字符串资源版（保持 SHORT） */
    public static void showRes(Context ctx, int resId) {
        show(ctx, ctx.getString(resId), 0, Toast.LENGTH_SHORT);
    }

    /** 长时长版（保持原 LONG 语义） */
    public static void showLong(Context ctx, CharSequence text) {
        show(ctx, text, 0, Toast.LENGTH_LONG);
    }

    /** 成功态 ✅ */
    public static void showOk(Context ctx, CharSequence text) {
        show(ctx, text, R.drawable.ic_toast_ok, Toast.LENGTH_SHORT);
    }

    /** 警示态 ⚠ */
    public static void showWarn(Context ctx, CharSequence text) {
        show(ctx, text, R.drawable.ic_toast_warn, Toast.LENGTH_SHORT);
    }

    /** 错误态 ✕ */
    public static void showError(Context ctx, CharSequence text) {
        show(ctx, text, R.drawable.ic_toast_error, Toast.LENGTH_SHORT);
    }

    /** 庆祝态 💗（配对/完成/兑现/纪念日等“高光时刻”专用，不扩大使用面） */
    public static void showCelebrate(Context ctx, CharSequence text) {
        show(ctx, text, R.drawable.ic_toast_heart, Toast.LENGTH_SHORT, true);
    }

    /** 庆祝态（字符串资源版） */
    public static void showCelebrateRes(Context ctx, int resId) {
        show(ctx, ctx.getString(resId), R.drawable.ic_toast_heart, Toast.LENGTH_SHORT, true);
    }

    private static void show(Context ctx, CharSequence text, int iconRes, int duration) {
        show(ctx, text, iconRes, duration, false);
    }

    private static void show(Context ctx, CharSequence text, int iconRes, int duration, boolean celebrate) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_toast);
        int pad = (int) (ctx.getResources().getDisplayMetrics().density * 12);
        row.setPadding(pad, pad - 2, pad, pad - 2);
        if (iconRes != 0) {
            ImageView icon = new ImageView(ctx);
            icon.setImageResource(iconRes);
            int sz = (int) (ctx.getResources().getDisplayMetrics().density * 18);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
            ilp.setMarginEnd((int) (ctx.getResources().getDisplayMetrics().density * 6));
            icon.setLayoutParams(ilp);
            row.addView(icon);
        }
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        row.addView(tv);
        Toast t = Toast.makeText(ctx, text, duration);
        t.setView(row);
        t.setGravity(Gravity.CENTER, 0, 0);
        if (celebrate) {
            row.setScaleX(0.8f);
            row.setScaleY(0.8f);
            row.setAlpha(0f);
            row.post(() -> {
                try {
                    row.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start();
                } catch (Exception ignored) {
                    // 优雅降级：动画失败仍是普通 Toast
                }
            });
        }
        t.show();
    }
}
