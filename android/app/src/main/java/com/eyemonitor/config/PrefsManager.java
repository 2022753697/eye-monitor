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
    /** 本地开发默认地址：Android 模拟器访问宿主机回环 = 10.0.2.2（真机需在 Profile 改为局域网 IP 或云端 wss） */
    private static final String DEFAULT_SERVER_URL = "ws://10.0.2.2:8080/ws/eye";
    private static final String KEY_MONITOR_PROMPT_COUNT = "monitor_prompt_count";
    private static final String KEY_MONITOR_SETTINGS_PENDING = "monitor_settings_pending";
    private static final String KEY_NICKNAME = "nickname";
    private static final String KEY_PEER_NICKNAME = "peer_nickname";
    private static final String KEY_PEER_REMARK = "peer_remark";
    private static final String KEY_REMARK_PENDING_SYNC = "remark_pending_sync";
    private static final String KEY_PERMISSION_PROMPTED = "permission_prompted";
    private static final String KEY_BATTERY_WHITELIST_PROMPTED = "battery_whitelist_prompted";
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
    // 纪念日到期提醒去重（当天只提示一次）
    private static final String KEY_ANNIVERSARY_REMINDER_DATE = "anniversary_reminder_date";
    private static final String KEY_ANNIVERSARY_REMINDER_IDS = "anniversary_reminder_ids";
    // 对方设备状态缓存（Wave2：来自对方 device_status 上报）
    private static final String KEY_PEER_BATTERY = "peer_battery";
    private static final String KEY_PEER_CHARGING = "peer_charging";
    private static final String KEY_PEER_NETWORK = "peer_network";
    private static final String KEY_PEER_BLUETOOTH = "peer_bluetooth";
    private static final String KEY_PEER_ONLINE = "peer_online";
    private static final String KEY_SOS_LAST_TRIGGER = "sos_last_trigger";
    private static final String KEY_PAIR_AWAITING_PEER = "pair_awaiting_peer";
    private static final String KEY_PEER_DEVICE_ID = "peer_device_id";
    // 装扮区主题选择（本地偏好：解锁共同、应用各自）
    private static final String KEY_THEME_ID = "theme_id";
    // 深色模式三态（Phase 4）：follow_system / light / dark；App 启动时 EyeApp 应用 AppCompatDelegate
    private static final String KEY_NIGHT_MODE = "night_mode";
    public static final String NIGHT_FOLLOW_SYSTEM = "follow_system";
    public static final String NIGHT_LIGHT = "light";
    public static final String NIGHT_DARK = "dark";
    // 打卡单窗口单次记录（yyyy-MM-dd；本地偏好 + 服务端 dedupKey 兜底）
    private static final String KEY_CHECKIN_MORNING_DATE = "checkin_morning_date";
    private static final String KEY_CHECKIN_EVENING_DATE = "checkin_evening_date";
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

    /** 获取服务器地址（未设置时默认本地模拟器地址） */
    public String getServerUrl() {
        String url = prefs.getString(KEY_SERVER_URL, "");
        if (url == null || url.trim().isEmpty()) return DEFAULT_SERVER_URL;
        return url;
    }

    /** 设置服务器地址 */
    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    /** 由 ws/wss 地址推导 HTTP(S) API 基址（ws://host:port/ws/eye → http://host:port；wss:// → https://） */
    public String getApiBaseUrl() {
        String url = getServerUrl();
        String api;
        if (url.startsWith("wss://")) {
            api = "https://" + url.substring("wss://".length());
        } else if (url.startsWith("ws://")) {
            api = "http://" + url.substring("ws://".length());
        } else {
            api = url;
        }
        // 去掉尾部路径，仅保留 scheme://host:port
        int schemeEnd = api.indexOf("://") + 3;
        int slash = api.indexOf('/', schemeEnd);
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

    /** 获取对方设备ID（轨迹回放 device 参数；来自对端 location 消息 / /api/pairs/me） */
    public String getPeerDeviceId() {
        return prefs.getString(KEY_PEER_DEVICE_ID, null);
    }

    /** 记录对方设备ID */
    public void setPeerDeviceId(String deviceId) {
        if (deviceId != null && !deviceId.isEmpty()) {
            prefs.edit().putString(KEY_PEER_DEVICE_ID, deviceId).apply();
        }
    }

    /** 早安打卡日期（yyyy-MM-dd；同一天该窗口已打过则不再提醒/发送） */
    public String getCheckInMorningDate() {
        return prefs.getString(KEY_CHECKIN_MORNING_DATE, null);
    }

    public void setCheckInMorningDate(String date) {
        prefs.edit().putString(KEY_CHECKIN_MORNING_DATE, date).apply();
    }

    /** 晚安打卡日期（yyyy-MM-dd） */
    public String getCheckInEveningDate() {
        return prefs.getString(KEY_CHECKIN_EVENING_DATE, null);
    }

    public void setCheckInEveningDate(String date) {
        prefs.edit().putString(KEY_CHECKIN_EVENING_DATE, date).apply();
    }

    /**
     * 获取对方显示名（备注优先；无备注返回自动学习的昵称，均未设置返回 null）。
     * 全 App 显示统一的对方名称，改这一处即全面生效。
     */
    public String getPeerNickname() {
        String remark = getPeerRemark();
        if (remark != null && !remark.isEmpty()) {
            return remark;
        }
        return prefs.getString(KEY_PEER_NICKNAME, null);
    }

    /** 对方显示名（备注优先，微信式；无备注回退昵称；均无返回 null）——气泡/引用/系统行等展示统一用它 */
    public String getPeerDisplayName() {
        String remark = getPeerRemark();
        if (remark != null && !remark.trim().isEmpty()) return remark.trim();
        return prefs.getString(KEY_PEER_NICKNAME, null);
    }

    /** 设置/清除对方备注（空串=清除，恢复显示自动学习的昵称） */
    public void setPeerRemark(String remark) {
        if (remark == null || remark.isEmpty()) {
            prefs.edit().remove(KEY_PEER_REMARK).apply();
        } else {
            prefs.edit().putString(KEY_PEER_REMARK, remark.trim()).apply();
        }
    }

    /** 获取对方备注（未设置返回 null） */
    public String getPeerRemark() {
        return prefs.getString(KEY_PEER_REMARK, null);
    }

    /** 备注待重传标志（本地改了但服务器上传失败，下次启动/重连重传） */
    public boolean isRemarkPendingSync() {
        return prefs.getBoolean(KEY_REMARK_PENDING_SYNC, false);
    }

    public void setRemarkPendingSync(boolean pending) {
        prefs.edit().putBoolean(KEY_REMARK_PENDING_SYNC, pending).apply();
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

    /** 监控权限引导：从设置页返回后仍缺时的再提醒次数（上限 1 次） */
    public int getMonitorPromptCount() {
        return prefs.getInt(KEY_MONITOR_PROMPT_COUNT, 0);
    }

    public void setMonitorPromptCount(int count) {
        prefs.edit().putInt(KEY_MONITOR_PROMPT_COUNT, count).apply();
    }

    /** 刚从监控设置页返回（用于返回后复查是否仍缺） */
    public boolean isMonitorSettingsPending() {
        return prefs.getBoolean(KEY_MONITOR_SETTINGS_PENDING, false);
    }

    public void setMonitorSettingsPending(boolean pending) {
        prefs.edit().putBoolean(KEY_MONITOR_SETTINGS_PENDING, pending).apply();
    }

    /** 省电 P3：电池优化白名单是否已提醒过（拒绝/忽略后不再重复打扰） */
    public boolean isBatteryWhitelistPrompted() {
        return prefs.getBoolean(KEY_BATTERY_WHITELIST_PROMPTED, false);
    }

    public void setBatteryWhitelistPrompted(boolean prompted) {
        prefs.edit().putBoolean(KEY_BATTERY_WHITELIST_PROMPTED, prompted).apply();
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

    /** 纪念日提醒去重：最近一次已提示的日期（yyyy-MM-dd，空串表示从未） */
    public String getAnniversaryReminderDate() {
        return prefs.getString(KEY_ANNIVERSARY_REMINDER_DATE, "");
    }

    public void setAnniversaryReminderDate(String date) {
        prefs.edit().putString(KEY_ANNIVERSARY_REMINDER_DATE, date).apply();
    }

    /** 纪念日提醒去重：当天已提示过的 serverId（逗号分隔） */
    public String getAnniversaryReminderIds() {
        return prefs.getString(KEY_ANNIVERSARY_REMINDER_IDS, "");
    }

    public void setAnniversaryReminderIds(String ids) {
        prefs.edit().putString(KEY_ANNIVERSARY_REMINDER_IDS, ids).apply();
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

    /** 上次 SOS 触发时间（epoch ms，用于 60s 冷却跨重启持久化） */
    public long getSosLastTrigger() {
        return prefs.getLong(KEY_SOS_LAST_TRIGGER, 0L);
    }

    public void setSosLastTrigger(long ts) {
        prefs.edit().putLong(KEY_SOS_LAST_TRIGGER, ts).apply();
    }

    /** 已创建配对码、等待对方加入（内存/持久化，创建后重启仍停留在配对面板显示码） */
    public boolean isPairAwaitingPeer() {
        return prefs.getBoolean(KEY_PAIR_AWAITING_PEER, false);
    }

    public void setPairAwaitingPeer(boolean awaiting) {
        prefs.edit().putBoolean(KEY_PAIR_AWAITING_PEER, awaiting).apply();
    }

    /** 是否已配对 */
    public boolean isPaired() {
        return getPairCode() != null;
    }

    /** 当前主题 id（默认/珊瑚恋语） */
    public String getThemeId() {
        return prefs.getString(KEY_THEME_ID, com.eyemonitor.ui.theme.ChatTheme.ID_DEFAULT);
    }

    /** 保存主题选择（本地偏好，不影响对方） */
    public void setThemeId(String themeId) {
        if (themeId != null) {
            prefs.edit().putString(KEY_THEME_ID, themeId).apply();
        }
    }

    /** 深色模式三态（默认跟随系统） */
    public String getNightMode() {
        return prefs.getString(KEY_NIGHT_MODE, NIGHT_FOLLOW_SYSTEM);
    }

    public void setNightMode(String mode) {
        if (NIGHT_FOLLOW_SYSTEM.equals(mode) || NIGHT_LIGHT.equals(mode) || NIGHT_DARK.equals(mode)) {
            prefs.edit().putString(KEY_NIGHT_MODE, mode).apply();
        }
    }

    /** 清除所有配置 */
    public void clearAll() {
        prefs.edit().clear().apply();
    }
}