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
    public interface Callback extends ErrorSink {
        void onSuccess(JsonObject data);
    }

    /** 回调：data 为响应包中 data 字段（JsonElement，兼容数组型 data，如轨迹点列表） */
    public interface ElementCallback extends ErrorSink {
        void onSuccess(JsonElement data);
    }

    /** 失败回调共用签名（Callback / ElementCallback 均实现） */
    private interface ErrorSink {
        void onError(int code, String msg);
    }

    /** 响应解包钩子（对象型/元素型共用请求/刷新链路） */
    private interface ResponseDeliver {
        void deliver(Response resp) throws IOException;
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

    /** GET 且 data 为任意 JsonElement（数组型接口，如轨迹点列表） */
    public void getElement(Context ctx, String apiPath, ElementCallback cb) {
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
        execAuthedRaw(ctx, method, apiPath, body, cb, resp -> deliver(ctx, resp, cb));
    }

    private void execAuthed(Context ctx, String method, String apiPath, RequestBody body, ElementCallback cb) {
        execAuthedRaw(ctx, method, apiPath, body, cb, resp -> deliverElement(ctx, resp, cb));
    }

    /** 受保护请求共用链路：带 token + 401/403 无感刷新重试一次 */
    private void execAuthedRaw(Context ctx, String method, String apiPath, RequestBody body,
                               ErrorSink cb, ResponseDeliver deliver) {
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
                deliver.deliver(resp);
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
                        deliver.deliver(resp);
                    }
                });
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
            JsonElement data = obj.get("data");
            if (code == 0 && data != null && !data.isJsonNull() && data.isJsonObject()) {
                cb.onSuccess(data.getAsJsonObject());
            } else {
                cb.onError(code, msg != null && !msg.isEmpty() ? msg : raw);
            }
        } catch (Exception e) {
            Log.e(TAG, "解析响应失败: " + raw, e);
            cb.onError(resp.code(), ctx.getString(R.string.auth_error_response));
        }
    }

    /** 解包 {code,data,msg}：data 为任意 JsonElement（数组型接口使用） */
    private void deliverElement(Context ctx, Response resp, ElementCallback cb) throws IOException {
        String raw = resp.body() != null ? resp.body().string() : "";
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            int code = obj.has("code") ? obj.get("code").getAsInt() : -1;
            String msg = obj.has("msg") && !obj.get("msg").isJsonNull()
                    ? obj.get("msg").getAsString() : "";
            if (code == 0 && obj.has("data") && !obj.get("data").isJsonNull()) {
                cb.onSuccess(obj.get("data"));
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