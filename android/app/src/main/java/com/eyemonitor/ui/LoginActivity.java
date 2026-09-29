package com.eyemonitor.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

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

        btnLogin.setOnClickListener(v -> doLogin());
        btnRegister.setOnClickListener(v -> doRegister());

        tvGoRegister.setOnClickListener(v -> switchMode(true));
        tvGoLogin.setOnClickListener(v -> switchMode(false));
    }

    private void switchMode(boolean register) {
        findViewById(R.id.panel_login).setVisibility(register ? android.view.View.GONE : android.view.View.VISIBLE);
        findViewById(R.id.panel_register).setVisibility(register ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void doLogin() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString();
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, R.string.login_required_tip, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        AuthManager.i(this).login(this, username, password, new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(LoginActivity.this, R.string.login_title, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, R.string.login_required_tip, Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(LoginActivity.this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
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