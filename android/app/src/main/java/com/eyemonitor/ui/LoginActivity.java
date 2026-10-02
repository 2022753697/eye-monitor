package com.eyemonitor.ui;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.util.PermissionHelper;
import com.eyemonitor.util.ServerUrlDialogHelper;

/**
 * 登录 / 注册界面（组件 7：强制登录，用户名 + 密码）。
 * <p>
 * 登录 / 注册成功后写入 token 与资料缓存，跳转主界面。
 * 无感刷新由 AuthManager / MonitorService 协作处理，本页仅在登出/被顶替时出现。
 */
public class LoginActivity extends AppCompatActivity {

    // 登录面板
    private EditText etUsername;
    private EditText etPassword;
    private Button btnLogin;
    private TextView tvGoRegister;

    // 注册面板
    private EditText etRegUsername;
    private EditText etRegPassword;
    private EditText etRegNickname;
    private EditText etRegBirthday;
    private EditText etRegBio;
    private RadioButton rbRegFemale;
    private Button btnRegister;
    private TextView tvGoLogin;

    // 服务器地址入口（登录前可改）
    private TextView tvServerUrlValue;
    private PrefsManager prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        prefs = new PrefsManager(this);

        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        tvGoRegister = findViewById(R.id.tv_go_register);

        etRegUsername = findViewById(R.id.et_reg_username);
        etRegPassword = findViewById(R.id.et_reg_password);
        etRegNickname = findViewById(R.id.et_reg_nickname);
        etRegBirthday = findViewById(R.id.et_reg_birthday);
        etRegBio = findViewById(R.id.et_reg_bio);
        rbRegFemale = findViewById(R.id.rb_reg_female);
        btnRegister = findViewById(R.id.btn_register);
        tvGoLogin = findViewById(R.id.tv_go_login);

        tvServerUrlValue = findViewById(R.id.tv_server_url_login_value);
        tvServerUrlValue.setText(prefs.getServerUrl());
        findViewById(R.id.row_server_url_login)
                .setOnClickListener(v -> ServerUrlDialogHelper.show(this, tvServerUrlValue));

        // 进入 App：一次性请求运行时权限（批次1安全集合 → 后台定位单独批次 → 监控设置引导）
        PermissionHelper.startEntryPermissionFlow(this);

        btnLogin.setOnClickListener(v -> doLogin());
        btnRegister.setOnClickListener(v -> doRegister());

        tvGoRegister.setOnClickListener(v -> switchMode(true));
        tvGoLogin.setOnClickListener(v -> switchMode(false));

        setupBirthdayPicker();
    }

    /** 生日：点击弹出 DatePicker（禁止手输），选择后回填 yyyy-MM-dd */
    private void setupBirthdayPicker() {
        etRegBirthday.setFocusable(false);
        etRegBirthday.setCursorVisible(false);
        etRegBirthday.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            DatePickerDialog dialog = new DatePickerDialog(this,
                    (view, y, m, d) -> etRegBirthday.setText(
                            String.format(Locale.CHINA, "%04d-%02d-%02d", y, m + 1, d)),
                    cal.get(Calendar.YEAR) - 18,
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH));
            dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
            dialog.setTitle(getString(R.string.reg_birthday_hint));
            dialog.show();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 权限流程后继步骤：批次1结束→后台定位批次；批次2结束→监控设置引导
        PermissionHelper.onEntryFlowStep(this, requestCode);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从监控设置页返回：复查仍缺则再提醒一次（上限 2 次）
        PermissionHelper.guideIfReturnedFromSettings(this);
    }

    private void switchMode(boolean register) {
        findViewById(R.id.panel_login).setVisibility(register ? android.view.View.GONE : android.view.View.VISIBLE);
        findViewById(R.id.panel_register).setVisibility(register ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void doLogin() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString();
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.login_required_tip);
            return;
        }
        // 服务器地址必须已设置，否则弹窗引导输入
        if (TextUtils.isEmpty(prefs.getServerUrl())) {
            promptServerUrl();
            return;
        }
        setBusy(true);
        AuthManager.i(this).login(this, username, password, new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                runOnUiThread(() -> {
                    setBusy(false);
                    com.eyemonitor.util.Toasts.showRes(LoginActivity.this, R.string.login_title);
                    enterMain();
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(LoginActivity.this,
                            getString(R.string.login_failed, msg != null ? msg : code + ""),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void doRegister() {
        String username = etRegUsername.getText().toString().trim();
        String password = etRegPassword.getText().toString();
        String nickname = etRegNickname.getText().toString().trim();
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password) || TextUtils.isEmpty(nickname)) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.login_required_tip);
            return;
        }
        // 服务器地址必须已设置，否则弹窗引导输入
        if (TextUtils.isEmpty(prefs.getServerUrl())) {
            promptServerUrl();
            return;
        }
        String birthday = etRegBirthday.getText().toString().trim();
        String bio = etRegBio.getText().toString().trim();
        String gender = rbRegFemale.isChecked() ? "female" : "male";
        setBusy(true);
        AuthManager.i(this).register(this, username, password, nickname, gender,
                birthday.isEmpty() ? null : birthday, bio.isEmpty() ? null : bio,
                new AuthManager.Callback() {
                    @Override
                    public void onSuccess(com.google.gson.JsonObject data) {
                        runOnUiThread(() -> {
                            setBusy(false);
                            com.eyemonitor.util.Toasts.showRes(LoginActivity.this, R.string.profile_saved);
                            enterMain();
                        });
                    }

                    @Override
                    public void onError(int code, String msg) {
                        runOnUiThread(() -> {
                            setBusy(false);
                            Toast.makeText(LoginActivity.this,
                                    getString(R.string.reg_failed, msg != null ? msg : code + ""),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    /** 服务器地址为空：提示并直接弹出设置弹窗（登录/注册前强制设置） */
    private void promptServerUrl() {
        Toast.makeText(this, R.string.profile_server_url_required, Toast.LENGTH_LONG).show();
        ServerUrlDialogHelper.show(this, tvServerUrlValue);
    }

    private void enterMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finishAffinity();
    }

    private void setBusy(boolean busy) {
        btnLogin.setEnabled(!busy);
        btnRegister.setEnabled(!busy);
    }
}