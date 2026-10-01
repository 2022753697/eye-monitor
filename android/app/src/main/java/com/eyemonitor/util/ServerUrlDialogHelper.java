package com.eyemonitor.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.eyemonitor.R;
import com.eyemonitor.config.PrefsManager;

/**
 * 服务器地址设置弹窗（复用一套逻辑）：
 * 登录/注册前可改（解决"登录后才能改地址"的死锁） + Profile 页可改。
 * 校验 ws:// / wss:// 前缀，保存到 PrefsManager，重启监控后生效。
 */
public final class ServerUrlDialogHelper {

    private ServerUrlDialogHelper() {}

    /** 弹出对话框；valueView 非空时保存成功后回显新地址 */
    public static void show(Activity activity, TextView valueView) {
        PrefsManager prefs = new PrefsManager(activity);
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.profile_server_url_hint);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.profile_server_url_label)
                .setView(input)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()
                            || (!url.startsWith("ws://") && !url.startsWith("wss://"))) {
                        Toast.makeText(activity, R.string.profile_server_url_invalid,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.setServerUrl(url);
                    if (valueView != null) valueView.setText(url);
                    Toast.makeText(activity, R.string.profile_server_url_saved,
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}