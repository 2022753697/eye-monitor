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

    /** 是否已配对 */
    public boolean isPaired() {
        return getPairCode() != null;
    }

    /** 清除所有配置 */
    public void clearAll() {
        prefs.edit().clear().apply();
    }
}