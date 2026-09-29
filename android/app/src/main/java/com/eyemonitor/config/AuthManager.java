package com.eyemonitor.config;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.eyemonitor.R;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 认证与 API 访问管理器（若依式轻量 JWT 接入）。
 * <p>
 * - 登录/注册/刷新走 /api/auth/**，成功后 token 与资料缓存写入 PrefsManager
 * - 受保护请求带 Authorization: Bearer accessToken
 * - 无感刷新：收到 HTTP 401/403 时自动用 refreshToken 换新 access token 并重试一次；
 *   refresh 也失效则清空登录态（由调用方引导回登录页）
 * <p>
 * 注意：所有回调都在 OkHttp 后台线程执行，UI 调用方需自行 runOnUiThread。
 */
public class AuthManager {

    private static final String TAG = "AuthManager";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");

    private static volatile AuthManager INSTANCE;

    private final OkHttpClient http;

    /** 回调：data 为响应包中 data 字段（JsonObject） */
    public interface Callback {
        void onSuccess(JsonObject data);
        void onError(int code, String msg);
    }

    /** 二进制下载回调（GET /api/media/{fileId} 等非 JSON 接口） */
    public interface DownloadCallback {
        void onSuccess(File file);
        void onError(int code, String msg);
    }

    public static AuthManager i(Context context) {
        if (INSTANCE == null) {
            synchronized (AuthManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AuthManager(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    private AuthManager(Context context) {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    // --- 认证 ---

    public void register(Context ctx, String username, String password, String nickname,
                         String gender, String birthday, String bio, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        body.addProperty("nickname", nickname);
        if (gender != null) body.addProperty("gender", gender);
        if (birthday != null) body.addProperty("birthday", birthday);
        if (bio != null) body.addProperty("bio", bio);
        postPublic(ctx, "/api/auth/register", body.toString(), new Callback() {
            @Override
            public void onSuccess(JsonObject data) {
                applyAuthSession(ctx, data);
                cb.onSuccess(data);
            }

            @Override
            public void onError(int code, String msg) {
                cb.onError(code, msg);
            }
        }, cb);
    }

    public void login(Context ctx, String username, String password, Callback cb) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        postPublic(ctx, "/api/auth/login", body.toString(), new Callback() {
            @Override
            public void onSuccess(JsonObject data) {
                applyAuthSession(ctx, data);
                cb.onSuccess(data);
            }

            @Override
            public void onError(int code, String msg) {
                cb.onError(code, msg);
            }
        }, cb);
    }

    /** 无感刷新：refresh 成功则更新本地 token；失败清空登录态并回调错误 */
    public void refresh(Context ctx, Callback cb) {
        PrefsManager prefs = new PrefsManager(ctx);
        String refreshToken = prefs.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            prefs.clearAuth();
            cb.onError(401, ctx.getString(R.string.auth_error_expired));
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("refreshToken", refreshToken);
        postPublic(ctx, "/api/auth/refresh", body.toString(), new Callback() {
            @Override
            public void onSuccess(JsonObject data) {
                if (data.has("accessToken")) {
                    prefs.setAccessToken(data.get("accessToken").getAsString());
                }
                if (data.has("refreshToken")) {
                    prefs.setRefreshToken(data.get("refreshToken").getAsString());
                }
                Log.i(TAG, "token 已无感刷新");
                cb.onSuccess(data);
            }

            @Override
            public void onError(int code, String msg) {
                cb.onError(code, msg);
            }
        }, cb);
    }

    /** 本地登出：清除登录态（session 型 JWT，无需服务器注销） */
    public void logout(Context ctx) {
        new PrefsManager(ctx).clearAuth();
    }

    // --- 受保护请求（自动带 token + 401 无感刷新重试一次） ---

    public void get(Context ctx, String apiPath, Callback cb) {
        execAuthed(ctx, "GET", apiPath, null, cb);
    }

    public void postJson(Context ctx, String apiPath, String jsonBody, Callback cb) {
        execAuthed(ctx, "POST", apiPath, RequestBody.create(jsonBody, JSON), cb);
    }

    public void putJson(Context ctx, String apiPath, String jsonBody, Callback cb) {
        execAuthed(ctx, "PUT", apiPath, RequestBody.create(jsonBody, JSON), cb);
    }

    public void delete(Context ctx, String apiPath, Callback cb) {
        execAuthed(ctx, "DELETE", apiPath, null, cb);
    }

    /** 头像上传（multipart，字段名 file） */
    public void uploadAvatar(Context ctx, File file, Callback cb) {
        RequestBody fileBody = RequestBody.create(file, OCTET);
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(), fileBody)
                .build();
        execAuthed(ctx, "PUT", "/api/user/avatar", body, cb);
    }

    /** 上传媒体（聊天气泡/共享图库），multipart: file + pairCode */
    public void uploadMedia(Context ctx, File file, String pairCode, Callback cb) {
        RequestBody fileBody = RequestBody.create(file, OCTET);
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(), fileBody)
                .addFormDataPart("pairCode", pairCode)
                .build();
        execAuthed(ctx, "POST", "/api/media/upload", body, cb);
    }

    /** 下载媒体到本地缓存文件（GET /api/media/{fileId}，Range 由 OkHttp 透明处理） */
    public void downloadMedia(Context ctx, String fileId, File target, DownloadCallback cb) {
        execDownload(ctx, "/api/media/" + fileId, target, cb, new AtomicBoolean(false));
    }

    // --- 内部实现 ---

    private void postPublic(Context ctx, String path, String jsonBody,
                            Callback success, Callback cb) {
        Request request = new Request.Builder()
                .url(new PrefsManager(ctx).getApiBaseUrl() + path)
                .post(RequestBody.create(jsonBody, JSON))
                .build();
        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                cb.onError(-1, e.getMessage() != null ? e.getMessage() : ctx.getString(R.string.auth_error_network));
            }

            @Override
            public void onResponse(Call c, Response resp) throws IOException {
                deliver(ctx, resp, cb);
            }
        });
    }

    private void execAuthed(Context ctx, String method, String apiPath, RequestBody body, Callback cb) {
        PrefsManager prefs = new PrefsManager(ctx);
        Request.Builder rb = new Request.Builder()
                .url(prefs.getApiBaseUrl() + apiPath);
        if (prefs.getAccessToken() != null) {
            rb.header("Authorization", "Bearer " + prefs.getAccessToken());
        }
        rb.method(method, body == null && !"GET".equals(method) && !"DELETE".equals(method)
                ? RequestBody.create(new byte[0], JSON) : body);

        AtomicBoolean refreshedOnce = new AtomicBoolean(false);
        http.newCall(rb.build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                cb.onError(-1, e.getMessage() != null ? e.getMessage() : ctx.getString(R.string.auth_error_network));
            }

            @Override
            public void onResponse(Call c, Response resp) throws IOException {
                if ((resp.code() == 401 || resp.code() == 403) && !refreshedOnce.getAndSet(true)) {
                    // 无感刷新后重试一次
                    refresh(ctx, new Callback() {
                        @Override
                        public void onSuccess(JsonObject data) {
                            retry();
                        }

                        @Override
                        public void onError(int code, String msg) {
                            new PrefsManager(ctx).clearAuth();
                            cb.onError(code, msg != null ? msg : ctx.getString(R.string.auth_error_expired));
                        }
                    });
                    return;
                }
                deliver(ctx, resp, cb);
            }

            private void retry() {
                PrefsManager p = new PrefsManager(ctx);
                Request.Builder rb2 = new Request.Builder()
                        .url(p.getApiBaseUrl() + apiPath)
                        .header("Authorization", "Bearer " + p.getAccessToken());
                rb2.method(method, body == null && !"GET".equals(method) && !"DELETE".equals(method)
                        ? RequestBody.create(new byte[0], JSON) : body);
                http.newCall(rb2.build()).enqueue(new okhttp3.Callback() {
                    @Override
                    public void onFailure(Call c, IOException e) {
                        cb.onError(-1, e.getMessage() != null ? e.getMessage() : ctx.getString(R.string.auth_error_network));
                    }

                    @Override
                    public void onResponse(Call c, Response resp) throws IOException {
                        deliver(ctx, resp, cb);
                    }
                });
            }
        });
    }

    /**
     * 二进制下载：先写 .part 临时文件，成功后原子改名 target；
     * 401/403 无感刷新后重试一次（与 execAuthed 语义一致）。
     */
    private void execDownload(Context ctx, String apiPath, File target,
                              DownloadCallback cb, AtomicBoolean refreshedOnce) {
        PrefsManager prefs = new PrefsManager(ctx);
        Request.Builder rb = new Request.Builder()
                .url(prefs.getApiBaseUrl() + apiPath);
        if (prefs.getAccessToken() != null) {
            rb.header("Authorization", "Bearer " + prefs.getAccessToken());
        }
        http.newCall(rb.build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                cb.onError(-1, e.getMessage() != null ? e.getMessage()
                        : ctx.getString(R.string.auth_error_network));
            }

            @Override
            public void onResponse(Call c, Response resp) throws IOException {
                int code = resp.code();
                if ((code == 401 || code == 403) && !refreshedOnce.getAndSet(true)) {
                    resp.close();
                    refresh(ctx, new Callback() {
                        @Override
                        public void onSuccess(JsonObject data) {
                            execDownload(ctx, apiPath, target, cb, refreshedOnce);
                        }

                        @Override
                        public void onError(int c2, String msg) {
                            new PrefsManager(ctx).clearAuth();
                            cb.onError(c2, msg != null ? msg
                                    : ctx.getString(R.string.auth_error_expired));
                        }
                    });
                    return;
                }
                if (!resp.isSuccessful()) {
                    resp.close();
                    cb.onError(code, ctx.getString(R.string.auth_error_response));
                    return;
                }
                File tmp = new File(target.getParentFile(), target.getName() + ".part");
                try (java.io.InputStream in = resp.body().byteStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    long written = 0;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        written += n;
                    }
                    out.flush();
                    if (written == 0) {
                        cb.onError(code, ctx.getString(R.string.media_download_empty));
                        return;
                    }
                    if (tmp.renameTo(target)) {
                        cb.onSuccess(target);
                    } else {
                        cb.onSuccess(tmp);
                    }
                } catch (IOException e) {
                    cb.onError(-1, e.getMessage() != null ? e.getMessage()
                            : ctx.getString(R.string.media_download_failed));
                } finally {
                    resp.close();
                }
            }
        });
    }

    /** 统一解包 {code,data,msg}，回调成功/失败 */
    private void deliver(Context ctx, Response resp, Callback cb) throws IOException {
        String raw = resp.body() != null ? resp.body().string() : "";
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            int code = obj.has("code") ? obj.get("code").getAsInt() : -1;
            String msg = obj.has("msg") && !obj.get("msg").isJsonNull()
                    ? obj.get("msg").getAsString() : "";
            if (code == 0 && obj.has("data") && !obj.get("data").isJsonNull()) {
                cb.onSuccess(obj.getAsJsonObject("data"));
            } else {
                cb.onError(code, msg != null && !msg.isEmpty() ? msg : raw);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析响应失败: " + raw, e);
            cb.onError(resp.code(), ctx.getString(R.string.auth_error_response));
        }
    }

    /** 登录/注册成功后写入本机会话与资料缓存（含无 id 的 profile 字段，均为可空） */
    private void applyAuthSession(Context ctx, JsonObject data) {
        PrefsManager prefs = new PrefsManager(ctx);
        if (data.has("accessToken")) prefs.setAccessToken(data.get("accessToken").getAsString());
        if (data.has("refreshToken")) prefs.setRefreshToken(data.get("refreshToken").getAsString());
        if (data.has("profile")) {
            applyProfile(ctx, data.getAsJsonObject("profile"));
        }
    }

    /** 将服务器 profile 字段写入本地缓存（自身资料 + 昵称/性别等） */
    public static void applyProfile(Context ctx, JsonObject profile) {
        PrefsManager prefs = new PrefsManager(ctx);
        if (profile == null) return;
        String username = optStr(profile, "username");
        if (username != null) prefs.setUsername(username);
        String nickname = optStr(profile, "nickname");
        if (nickname != null && !nickname.isEmpty()) prefs.setNickname(nickname);
        String avatar = optStr(profile, "avatar");
        if (avatar != null) prefs.setAvatar(avatar);
        String gender = optStr(profile, "gender");
        if (gender != null) prefs.setGender(gender);
        String birthday = optStr(profile, "birthday");
        if (birthday != null) prefs.setBirthday(birthday);
        String bio = optStr(profile, "bio");
        if (bio != null) prefs.setBio(bio);
    }

    private static String optStr(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) return null;
        return e.getAsString();
    }
}