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
        show(ctx, text, 0);
    }

    /** 成功态 ✅ */
    public static void showOk(Context ctx, CharSequence text) {
        show(ctx, text, R.drawable.ic_toast_ok);
    }

    /** 警示态 ⚠ */
    public static void showWarn(Context ctx, CharSequence text) {
        show(ctx, text, R.drawable.ic_toast_warn);
    }

    /** 错误态 ✕ */
    public static void showError(Context ctx, CharSequence text) {
        show(ctx, text, R.drawable.ic_toast_error);
    }

    private static void show(Context ctx, CharSequence text, int iconRes) {
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
        Toast t = Toast.makeText(ctx, text, Toast.LENGTH_SHORT);
        t.setView(row);
        t.setGravity(Gravity.CENTER, 0, 0);
        t.show();
    }
}
