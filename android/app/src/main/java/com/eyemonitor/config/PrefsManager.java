package com.eyemonitor.config;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/**
 * SharedPreferences 封装
 * 持久化存储设备标识、配对码等配置
 */
public class PrefsManager {

    private static final String PREF_NAME = "eye_monitor_prefs";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_PAIR_CODE = "pair_code";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_NICKNAME = "nickname";
    private static final String KEY_PEER_NICKNAME = "peer_nickname";
    private static final String KEY_PERMISSION_PROMPTED = "permission_prompted";
    private static final String KEY_GENDER = "gender";
    // 账户体系（R29：登录 token / 资料缓存）
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_AVATAR = "avatar";
    private static final String KEY_BIRTHDAY = "birthday";
    private static final String KEY_BIO = "bio";
    private static final String KEY_PEER_AVATAR = "peer_avatar";
    private static final String KEY_PEER_GENDER = "peer_gender";
    private static final String KEY_PEER_BIRTHDAY = "peer_birthday";
    private static final String KEY_PEER_BIO = "peer_bio";
    // 对方设备状态缓存（Wave2：来自对方 device_status 上报）
    private static final String KEY_PEER_BATTERY = "peer_battery";
    private static final String KEY_PEER_CHARGING = "peer_charging";
    private static final String KEY_PEER_NETWORK = "peer_network";
    private static final String KEY_PEER_BLUETOOTH = "peer_bluetooth";
    private static final String KEY_PEER_ONLINE = "peer_online";
    private final SharedPreferences prefs;

    public PrefsManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 获取设备唯一标识。
     * 首次调用时自动生成 UUID 并持久化。
     */
    public String getDeviceId() {
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    /** 获取已保存的配对码 */
    public String getPairCode() {
        return prefs.getString(KEY_PAIR_CODE, null);
    }

    /** 保存配对码 */
    public void setPairCode(String pairCode) {
        prefs.edit().putString(KEY_PAIR_CODE, pairCode).apply();
    }

    /** 清除配对码（解除配对） */
    public void clearPairCode() {
        prefs.edit().remove(KEY_PAIR_CODE).apply();
    }

    /** 获取服务器地址 */
    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, "ws://192.168.1.84:8080/ws/eye");
    }

    /** 设置服务器地址 */
    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    /** 由 ws 地址推导 HTTP API 基址（ws://host:port/ws/eye -> http://host:port） */
    public String getApiBaseUrl() {
        String api = getServerUrl().replaceFirst("^wss?://", "http://");
        int slash = api.indexOf('/', "http://".length());
        if (slash > 0) {
            api = api.substring(0, slash);
        }
        return api;
    }

    /** WebSocket 带 token 的连接地址（多参数追加时避免重复 ?） */
    public String getAuthServerUrl() {
        String url = getServerUrl();
        String token = getAccessToken();
        if (token == null || token.isEmpty()) return url;
        return url + (url.contains("?") ? "&token=" : "?token=") + token;
    }

    // --- 账户 token / 登录态 ---

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, null);
    }

    public void setAccessToken(String token) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply();
    }

    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }

    public void setRefreshToken(String token) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, null);
    }

    public void setUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    /** 清除登录态（保留配对码；KICKED/登出/refresh 失效时调用） */
    public void clearAuth() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USERNAME)
                .apply();
    }

    /** 是否已登录（有 access token 即视为登录态） */
    public boolean isLoggedIn() {
        return getAccessToken() != null;
    }

    // --- 我的资料缓存（登录后同步自服务器 / 服务端 user_profile 广播） ---

    public String getAvatar() {
        return prefs.getString(KEY_AVATAR, null);
    }

    public void setAvatar(String avatar) {
        prefs.edit().putString(KEY_AVATAR, avatar).apply();
    }

    public String getBirthday() {
        return prefs.getString(KEY_BIRTHDAY, null);
    }

    public void setBirthday(String birthday) {
        prefs.edit().putString(KEY_BIRTHDAY, birthday).apply();
    }

    public String getBio() {
        return prefs.getString(KEY_BIO, null);
    }

    public void setBio(String bio) {
        prefs.edit().putString(KEY_BIO, bio).apply();
    }

    // --- 对方资料缓存（来自服务端 user_profile 广播 / chat.from 学习兜底） ---

    public String getPeerAvatar() {
        return prefs.getString(KEY_PEER_AVATAR, null);
    }

    public void setPeerAvatar(String avatar) {
        if (avatar != null) {
            prefs.edit().putString(KEY_PEER_AVATAR, avatar).apply();
        }
    }

    public String getPeerGender() {
        return prefs.getString(KEY_PEER_GENDER, null);
    }

    public void setPeerGender(String gender) {
        if (gender != null) {
            prefs.edit().putString(KEY_PEER_GENDER, gender).apply();
        }
    }

    public String getPeerBirthday() {
        return prefs.getString(KEY_PEER_BIRTHDAY, null);
    }

    public void setPeerBirthday(String birthday) {
        if (birthday != null) {
            prefs.edit().putString(KEY_PEER_BIRTHDAY, birthday).apply();
        }
    }

    public String getPeerBio() {
        return prefs.getString(KEY_PEER_BIO, null);
    }

    public void setPeerBio(String bio) {
        if (bio != null) {
            prefs.edit().putString(KEY_PEER_BIO, bio).apply();
        }
    }

    /** 获取我的昵称（未设置返回 null） */
    public String getNickname() {
        return prefs.getString(KEY_NICKNAME, null);
    }

    /** 设置我的昵称 */
    public void setNickname(String nickname) {
        prefs.edit().putString(KEY_NICKNAME, nickname).apply();
    }

    /** 获取对方昵称（从未知来源更新，未设置返回 null） */
    public String getPeerNickname() {
        return prefs.getString(KEY_PEER_NICKNAME, null);
    }

    /** 记录对方昵称（从收到的聊天消息中学习） */
    public void setPeerNickname(String nickname) {
        if (nickname != null && !nickname.isEmpty()) {
            prefs.edit().putString(KEY_PEER_NICKNAME, nickname).apply();
        }
    }

    /** 是否已提示过监控权限（避免每次冷启动都弹） */
    public boolean isPermissionPrompted() {
        return prefs.getBoolean(KEY_PERMISSION_PROMPTED, false);
    }

    public void setPermissionPrompted(boolean prompted) {
        prefs.edit().putBoolean(KEY_PERMISSION_PROMPTED, prompted).apply();
    }

    /** 获取性别：female | male，未设置返回 null（默认按粉色） */
    public String getGender() {
        return prefs.getString(KEY_GENDER, null);
    }

    /** 设置性别：female（粉）| male（蓝） */
    public void setGender(String gender) {
        if ("female".equals(gender) || "male".equals(gender)) {
            prefs.edit().putString(KEY_GENDER, gender).apply();
        }
    }

    /** 是否女性（未设置时默认按女性粉色显示） */
    public boolean isFemale() {
        return !"male".equals(getGender());
    }

    // --- 对方设备状态（来自 device_status / pair_confirm） ---

    /** 对方电量（0-100，未知为 -1） */
    public int getPeerBattery() {
        return prefs.getInt(KEY_PEER_BATTERY, -1);
    }

    public void setPeerBattery(int battery) {
        prefs.edit().putInt(KEY_PEER_BATTERY, battery).apply();
    }

    /** 对方是否充电中 */
    public boolean getPeerCharging() {
        return prefs.getBoolean(KEY_PEER_CHARGING, false);
    }

    public void setPeerCharging(boolean charging) {
        prefs.edit().putBoolean(KEY_PEER_CHARGING, charging).apply();
    }

    /** 对方网络类型：wifi | mobile | none */
    public String getPeerNetwork() {
        return prefs.getString(KEY_PEER_NETWORK, null);
    }

    public void setPeerNetwork(String network) {
        if (network != null) {
            prefs.edit().putString(KEY_PEER_NETWORK, network).apply();
        }
    }

    /** 对方蓝牙是否开启 */
    public boolean getPeerBluetooth() {
        return prefs.getBoolean(KEY_PEER_BLUETOOTH, false);
    }

    public void setPeerBluetooth(boolean bluetooth) {
        prefs.edit().putBoolean(KEY_PEER_BLUETOOTH, bluetooth).apply();
    }

    /** 对方是否在线（默认在线；pair_confirm(peerOnline=false) / 本地 WS 断开时置灰） */
    public boolean getPeerOnline() {
        return prefs.getBoolean(KEY_PEER_ONLINE, true);
    }

    public void setPeerOnline(boolean online) {
        prefs.edit().putBoolean(KEY_PEER_ONLINE, online).apply();
    }

    /** 是否已配对 */
    public boolean isPaired() {
        return getPairCode() != null;
    }

    /** 清除所有配置 */
    public void clearAll() {
        prefs.edit().clear().apply();
    }
}