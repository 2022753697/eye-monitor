package com.eyemonitor.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.eyemonitor.R;
import com.eyemonitor.config.AuthManager;
import com.eyemonitor.config.PrefsManager;
import com.eyemonitor.service.MonitorService;
import com.eyemonitor.util.AvatarUtils;

import android.content.BroadcastReceiver;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
import java.util.Locale;
import java.io.InputStream;

/**
 * 我的主页（组件 7 / R29）：编辑头像、昵称、性别、生日、签名。
 * <p>
 * 保存后：PUT /api/user/profile + PUT /api/user/avatar，并向配对对方广播 user_profile。
 */
public class ProfileActivity extends AppCompatActivity {

    private static final int REQ_PICK_IMAGE = 1001;
    private static final long MAX_AVATAR_BYTES = 5 * 1024 * 1024L;

    private ImageView ivAvatar;
    private EditText etNickname;
    private EditText etBirthday;
    private EditText etBio;
    private RadioButton rbFemale;
    private RadioButton rbMale;
    private TextView tvServerUrl;
    private Button btnSave;

    private PrefsManager prefs;

    /** 被顶替下线时关闭本页（登录态失效） */
    private final BroadcastReceiver kickedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        prefs = new PrefsManager(this);
        ivAvatar = findViewById(R.id.iv_avatar);
        etNickname = findViewById(R.id.et_profile_nickname);
        etBirthday = findViewById(R.id.et_profile_birthday);
        etBio = findViewById(R.id.et_profile_bio);
        rbFemale = findViewById(R.id.rb_profile_female);
        rbMale = findViewById(R.id.rb_profile_male);
        btnSave = findViewById(R.id.btn_profile_save);
        tvServerUrl = findViewById(R.id.tv_server_url_value);
        findViewById(R.id.row_server_url).setOnClickListener(v -> showServerUrlDialog());
        Button btnBack = findViewById(R.id.btn_profile_back);
        btnBack.setOnClickListener(v -> finish());
        ivAvatar.setOnClickListener(v -> pickImage());
        btnSave.setOnClickListener(v -> saveProfile());
        setupBirthdayPicker();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(kickedReceiver, new IntentFilter(MonitorService.ACTION_KICKED),
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(kickedReceiver, new IntentFilter(MonitorService.ACTION_KICKED));
        }

        loadCurrentProfile();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(kickedReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    /** 生日：点击弹出 DatePicker（禁止手输），选择后回填 yyyy-MM-dd */
    private void setupBirthdayPicker() {
        etBirthday.setFocusable(false);
        etBirthday.setCursorVisible(false);
        etBirthday.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            DatePickerDialog dialog = new DatePickerDialog(this,
                    (view, y, m, d) -> etBirthday.setText(
                            String.format(Locale.CHINA, "%04d-%02d-%02d", y, m + 1, d)),
                    cal.get(Calendar.YEAR) - 18,
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH));
            dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
            dialog.setTitle(getString(R.string.profile_birthday_hint));
            dialog.show();
        });
    }

    /** 从本地缓存填充表单，并尝试向服务器拉最新资料 */
    private void loadCurrentProfile() {
        String nickname = prefs.getNickname();
        etNickname.setText(nickname != null ? nickname : "");
        String birthday = prefs.getBirthday();
        etBirthday.setText(birthday != null ? birthday : "");
        String bio = prefs.getBio();
        etBio.setText(bio != null ? bio : "");
        boolean female = prefs.isFemale();
        rbFemale.setChecked(female);
        rbMale.setChecked(!female);
        tvServerUrl.setText(prefs.getServerUrl());
        AvatarUtils.loadInto(ivAvatar, prefs.getAvatar(), female);

        // 刷新一次最新资料（无感刷新已内置）
        AuthManager.i(this).get(this, "/api/user/profile", new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                runOnUiThread(() -> {
                    AuthManager.applyProfile(ProfileActivity.this, data);
                    fillFromPrefs();
                });
            }

            @Override
            public void onError(int code, String msg) {
                Log.d("ProfileActivity", "拉取资料失败: " + code + " " + msg);
            }
        });
    }

    private void fillFromPrefs() {
        String nickname = prefs.getNickname();
        if (nickname != null) etNickname.setText(nickname);
        String birthday = prefs.getBirthday();
        if (birthday != null) etBirthday.setText(birthday);
        String bio = prefs.getBio();
        if (bio != null) etBio.setText(bio);
        boolean female = prefs.isFemale();
        rbFemale.setChecked(female);
        rbMale.setChecked(!female);
        tvServerUrl.setText(prefs.getServerUrl());
        AvatarUtils.loadInto(ivAvatar, prefs.getAvatar(), female);
    }

    /** 服务器地址入口（WS3/AC4）：弹窗改存 Prefs，重启监控后生效，免重打包 */
    private void showServerUrlDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.profile_server_url_hint);
        String current = prefs.getServerUrl();
        input.setText(current == null ? "" : current);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_server_url_label)
                .setView(input)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()
                            || (!url.startsWith("ws://") && !url.startsWith("wss://"))) {
                        com.eyemonitor.util.Toasts.showRes(this, R.string.profile_server_url_invalid);
                        return;
                    }
                    prefs.setServerUrl(url);
                    tvServerUrl.setText(url);
                    Toast.makeText(this, R.string.profile_server_url_saved,
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Exception e) {
            Log.e("ProfileActivity", "选择图片失败", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                uploadAvatar(uri);
            }
        }
    }

    /** 拷贝所选图片到缓存文件并上传（≤5MB，压缩到合理尺寸） */
    private void uploadAvatar(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                com.eyemonitor.util.Toasts.showRes(this, R.string.profile_avatar_upload_failed);
                return;
            }
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            in.close();
            if (bitmap == null) {
                com.eyemonitor.util.Toasts.showRes(this, R.string.profile_avatar_upload_failed);
                return;
            }
            // 等比压缩（长边 ≤1024，防超 5MB）
            int maxEdge = 1024;
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int longEdge = Math.max(w, h);
            if (longEdge > maxEdge) {
                float scale = (float) maxEdge / longEdge;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }
            File dir = new File(getCacheDir(), "avatar");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "avatar_tmp.jpg");
            FileOutputStream fos = new FileOutputStream(out);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            fos.flush();
            fos.close();
            bitmap.recycle();

            if (out.length() > MAX_AVATAR_BYTES) {
                com.eyemonitor.util.Toasts.showRes(this, R.string.profile_avatar_upload_failed);
                return;
            }
            doUploadAvatar(out);
        } catch (Exception e) {
            Log.e("ProfileActivity", "头像处理失败", e);
            com.eyemonitor.util.Toasts.showRes(this, R.string.profile_avatar_upload_failed);
        }
    }

    private void doUploadAvatar(File file) {
        btnSave.setEnabled(false);
        AvatarUtils.loadInto(ivAvatar, "file://" + file.getAbsolutePath(), prefs.isFemale());
        AuthManager.i(this).uploadAvatar(this, file, new AuthManager.Callback() {
            @Override
            public void onSuccess(com.google.gson.JsonObject data) {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    String avatar = data.has("avatar") ? data.get("avatar").getAsString() : null;
                    if (avatar != null) {
                        prefs.setAvatar(avatar);
                        AvatarUtils.loadInto(ivAvatar, avatar, prefs.isFemale());
                        // 广播给对方（若已配对）
                        MonitorService.sendProfileUpdate(ProfileActivity.this,
                                prefs.getNickname(), avatar,
                                prefs.getGender(), prefs.getBirthday(), prefs.getBio());
                    }
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    AvatarUtils.loadInto(ivAvatar, prefs.getAvatar(), prefs.isFemale());
                    com.eyemonitor.util.Toasts.showRes(ProfileActivity.this, R.string.profile_avatar_upload_failed);
                });
            }
        });
    }

    private void saveProfile() {
        String nickname = etNickname.getText().toString().trim();
        if (TextUtils.isEmpty(nickname)) {
            com.eyemonitor.util.Toasts.showRes(this, R.string.login_required_tip);
            return;
        }
        String gender = rbFemale.isChecked() ? "female" : "male";
        String birthday = etBirthday.getText().toString().trim();
        String bio = etBio.getText().toString().trim();

        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.addProperty("nickname", nickname);
        body.addProperty("gender", gender);
        if (birthday.isEmpty()) body.add("birthday", com.google.gson.JsonNull.INSTANCE);
        else body.addProperty("birthday", birthday);
        if (bio.isEmpty()) body.add("bio", com.google.gson.JsonNull.INSTANCE);
        else body.addProperty("bio", bio);

        btnSave.setEnabled(false);
        AuthManager.i(this).putJson(this, "/api/user/profile", body.toString(),
                new AuthManager.Callback() {
                    @Override
                    public void onSuccess(com.google.gson.JsonObject data) {
                        runOnUiThread(() -> {
                            btnSave.setEnabled(true);
                            prefs.setNickname(nickname);
                            prefs.setGender(gender);
                            prefs.setBirthday(birthday.isEmpty() ? null : birthday);
                            prefs.setBio(bio.isEmpty() ? null : bio);
                            com.eyemonitor.util.Toasts.showRes(ProfileActivity.this, R.string.profile_saved);
                            // 广播给对方（若已配对）
                            MonitorService.sendProfileUpdate(ProfileActivity.this,
                                    nickname, prefs.getAvatar(), gender,
                                    birthday.isEmpty() ? null : birthday,
                                    bio.isEmpty() ? null : bio);
                            finish();
                        });
                    }

                    @Override
                    public void onError(int code, String msg) {
                        runOnUiThread(() -> {
                            btnSave.setEnabled(true);
                            Toast.makeText(ProfileActivity.this,
                                    getString(R.string.profile_save_failed,
                                            msg != null ? msg : code + ""),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }
}