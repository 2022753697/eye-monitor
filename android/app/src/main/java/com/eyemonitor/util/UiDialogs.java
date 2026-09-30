package com.eyemonitor.util;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.eyemonitor.R;

/**
 * 统一样式弹窗（UI 二期组件表）
 * 替代散落各处的裸 AlertDialog：圆角白卡 + Display 标题 + Body 说明 + Primary/Danger 确认。
 * 用法：
 *   UiDialogs.confirm(ctx, 标题, 说明, 确认文案, 危险?, () -> ...)
 *   UiDialogs.input  (ctx, 标题, 输入hint, 确认文案, (text) -> ...)
 *   UiDialogs.info    (ctx, 标题, 说明)
 */
public final class UiDialogs {

    private UiDialogs() {
    }

    /** 确认弹窗：danger=true 时确认按钮为状态红色（删除/解除等危险操作）。返回 Dialog 便于附加监听 */
    public static android.app.Dialog confirm(Context ctx, String title, String message,
                                            String okText, boolean danger, Runnable onOk) {
        DialogConfig cfg = base(ctx, title);
        TextView tvMsg = cfg.dialog.findViewById(R.id.tv_dialog_message);
        if (message == null || message.isEmpty()) {
            tvMsg.setVisibility(View.GONE);
        } else {
            tvMsg.setVisibility(View.VISIBLE);
            tvMsg.setText(message);
        }
        cfg.ok.setText(okText);
        if (danger) {
            cfg.ok.setBackgroundTintList(
                    ContextCompat.getColorStateList(ctx, R.color.status_error));
        }
        cfg.cancel.setOnClickListener(v -> cfg.dialog.dismiss());
        cfg.ok.setOnClickListener(v -> {
            cfg.dialog.dismiss();
            if (onOk != null) onOk.run();
        });
        cfg.dialog.show();
        return cfg.dialog;
    }

    /** 双操作弹窗：ok/取消 都是动作（如打开无障碍 / 打开使用情况访问） */
    public static android.app.Dialog actions(Context ctx, String title, String message,
                                            String okText, String cancelText, boolean okDanger,
                                            Runnable onOk, Runnable onCancel) {
        DialogConfig cfg = base(ctx, title);
        TextView tvMsg = cfg.dialog.findViewById(R.id.tv_dialog_message);
        if (message == null || message.isEmpty()) {
            tvMsg.setVisibility(View.GONE);
        } else {
            tvMsg.setVisibility(View.VISIBLE);
            tvMsg.setText(message);
        }
        cfg.ok.setText(okText);
        cfg.cancel.setText(cancelText);
        if (okDanger) {
            cfg.ok.setBackgroundTintList(
                    ContextCompat.getColorStateList(ctx, R.color.status_error));
        }
        cfg.cancel.setOnClickListener(v -> {
            cfg.dialog.dismiss();
            if (onCancel != null) onCancel.run();
        });
        cfg.ok.setOnClickListener(v -> {
            cfg.dialog.dismiss();
            if (onOk != null) onOk.run();
        });
        cfg.dialog.show();
        return cfg.dialog;
    }

    /** 输入弹窗：确认时回调文本（空文本不回调） */
    public static void input(Context ctx, String title, String hint,
                            String okText, java.util.function.Consumer<String> onOk) {
        input(ctx, title, hint, null, okText, onOk);
    }

    /** 输入弹窗（支持预填 initial） */
    public static void input(Context ctx, String title, String hint, String initial,
                            String okText, java.util.function.Consumer<String> onOk) {
        DialogConfig cfg = base(ctx, title);
        EditText et = cfg.dialog.findViewById(R.id.et_dialog_input);
        et.setVisibility(View.VISIBLE);
        et.setHint(hint);
        if (initial != null && !initial.isEmpty()) et.setText(initial);
        cfg.ok.setText(okText);
        cfg.cancel.setOnClickListener(v -> cfg.dialog.dismiss());
        cfg.ok.setOnClickListener(v -> {
            String text = et.getText().toString().trim();
            if (text.isEmpty()) return;
            cfg.dialog.dismiss();
            if (onOk != null) onOk.accept(text);
        });
        et.requestFocus();
        if (cfg.dialog.getWindow() != null) {
            cfg.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        cfg.dialog.show();
    }

    /** 信息弹窗：单个确认按钮 */
    public static void info(Context ctx, String title, String message) {
        confirm(ctx, title, message, resolveString(ctx, R.string.ok), false, null);
    }

    private static String resolveString(Context ctx, int res) {
        return ctx.getString(res);
    }

    private static DialogConfig base(Context ctx, String title) {
        DialogConfig cfg = new DialogConfig();
        cfg.dialog = new android.app.Dialog(ctx);
        cfg.dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        cfg.dialog.setContentView(R.layout.dialog_card);
        Window window = cfg.dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            int w = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.85f);
            window.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        TextView tvTitle = cfg.dialog.findViewById(R.id.tv_dialog_title);
        tvTitle.setText(title);
        cfg.message = cfg.dialog.findViewById(R.id.tv_dialog_message);
        cfg.input = cfg.dialog.findViewById(R.id.et_dialog_input);
        cfg.cancel = cfg.dialog.findViewById(R.id.btn_dialog_cancel);
        cfg.ok = cfg.dialog.findViewById(R.id.btn_dialog_ok);
        return cfg;
    }

    private static final class DialogConfig {
        android.app.Dialog dialog;
        TextView message;
        EditText input;
        Button cancel;
        Button ok;
    }
}