package com.eyemonitor.model;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一 WebSocket 消息协议（Android 端）
 * 与服务器端 WsMessage 结构一致
 */
public class WsMessage {

    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private String type;

    @SerializedName("deviceId")
    private String deviceId;

    @SerializedName("pairCode")
    private String pairCode;

    @SerializedName("payload")
    private Map<String, Object> payload;

    @SerializedName("timestamp")
    private long timestamp;

    public WsMessage() {}

    public WsMessage(String type, String deviceId, String pairCode, Map<String, Object> payload, long timestamp) {
        this.type = type;
        this.deviceId = deviceId;
        this.pairCode = pairCode;
        this.payload = payload;
        this.timestamp = timestamp;
    }

    // --- 工厂方法 ---

    public static WsMessage createPairRequest(String deviceId, String pairCode) {
        return new WsMessage("pair_request", deviceId, pairCode, Map.of(), System.currentTimeMillis());
    }

    public static WsMessage createPairRecover(String deviceId, String pairCode) {
        return new WsMessage("pair_recover", deviceId, pairCode, Map.of(), System.currentTimeMillis());
    }

    public static WsMessage createAppSwitch(String deviceId, String pairCode,
                                             String packageName, String appName, String action) {
        return new WsMessage("app_switch", deviceId, pairCode,
                Map.of("packageName", packageName, "appName", appName, "action", action),
                System.currentTimeMillis());
    }

    public static WsMessage createLocation(String deviceId, String pairCode,
                                            double lat, double lng, float accuracy) {
        return new WsMessage("location", deviceId, pairCode,
                Map.of("lat", lat, "lng", lng, "accuracy", accuracy),
                System.currentTimeMillis());
    }

    public static WsMessage createPing(String deviceId) {
        return new WsMessage("ping", deviceId, null, Map.of(), System.currentTimeMillis());
    }

    /** 请求对方立即发送位置 */
    public static WsMessage createRequestPeerLocation(String deviceId) {
        return new WsMessage("request_peer_location", deviceId, null, Map.of(), System.currentTimeMillis());
    }

    /** 聊天消息：payload 携带 text（内容）与 from（发送者昵称） */
    public static WsMessage createChat(String deviceId, String pairCode, String text, String from) {
        return createChat(deviceId, pairCode, text, from, 0, null);
    }

    /** 聊天消息（引用扩展）：refMsgId=被引用消息时间戳（两端一致），refText=摘要 */
    public static WsMessage createChat(String deviceId, String pairCode, String text, String from,
                                       long refMsgId, String refText) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", text);
        payload.put("from", from);
        if (refMsgId > 0) payload.put("refMsgId", refMsgId);
        if (refText != null && !refText.isEmpty()) payload.put("refText", refText);
        return new WsMessage("chat", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    /** 输入中（ephemeral，不落库；对方在线时 3s 内提示） */
    public static WsMessage createTyping(String deviceId, String pairCode) {
        return new WsMessage("typing", deviceId, pairCode, Map.of(), System.currentTimeMillis());
    }

    /** 已读回执：upToTs=已读到的对方消息时间戳（含更早） */
    public static WsMessage createChatRead(String deviceId, String pairCode, long upToTs) {
        return new WsMessage("chat_read", deviceId, pairCode,
                Map.of("upToTs", upToTs), System.currentTimeMillis());
    }

    /** 撤回指令：msgTs=被撤回消息时间戳（2 分钟窗口内） */
    public static WsMessage createChatRecall(String deviceId, String pairCode, long msgTs) {
        return new WsMessage("chat_recall", deviceId, pairCode,
                Map.of("msgTs", msgTs), System.currentTimeMillis());
    }

    // --- 账户体系扩展消息类型（契约 v1，与 server 侧同步） ---

    /** 资料变更广播（R29：昵称/头像/性别/生日/签名同步给对方） */
    public static WsMessage createUserProfile(String deviceId, String pairCode,
                                              String nickname, String avatar,
                                              String gender, String birthday, String bio) {
        Map<String, Object> payload = new HashMap<>();
        if (nickname != null) payload.put("nickname", nickname);
        if (avatar != null) payload.put("avatar", avatar);
        if (gender != null) payload.put("gender", gender);
        if (birthday != null) payload.put("birthday", birthday);
        if (bio != null) payload.put("bio", bio);
        return new WsMessage("user_profile", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    /** SOS 紧急求助：位置可选（无定位时不带 lat/lng） */
    public static WsMessage createSos(String deviceId, String pairCode, String text,
                                      Double lat, Double lng) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", text);
        if (lat != null) payload.put("lat", lat);
        if (lng != null) payload.put("lng", lng);
        return new WsMessage("sos", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    /** SOS 回执（我没事）：回到发起方 */
    public static WsMessage createSosAck(String deviceId, String pairCode, String from) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", from);
        return new WsMessage("sos_ack", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    /** 设备状态（五件套：电量/充电/网络/在线/蓝牙） */
    public static WsMessage createDeviceStatus(String deviceId, String pairCode,
                                               int battery, boolean charging, String network,
                                               boolean online, boolean bluetooth) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("battery", battery);
        payload.put("charging", charging);
        payload.put("network", network);
        payload.put("online", online);
        payload.put("bluetooth", bluetooth);
        return new WsMessage("device_status", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    /** 纪念日变更广播 */
    public static WsMessage createAnniversarySync(String deviceId, String pairCode, String action,
                                                  long id, String name, String date,
                                                  boolean repeat, long updatedAt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        payload.put("id", id);
        if (name != null) payload.put("name", name);
        if (date != null) payload.put("date", date);
        payload.put("repeat", repeat);
        payload.put("updatedAt", updatedAt);
        return new WsMessage("anniversary_sync", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    /** 围栏变更广播 */
    public static WsMessage createFenceSync(String deviceId, String pairCode, String action,
                                            long id, String name, Double lat, Double lng,
                                            Double radius, Boolean enabled) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        payload.put("id", id);
        if (name != null) payload.put("name", name);
        if (lat != null) payload.put("lat", lat);
        if (lng != null) payload.put("lng", lng);
        if (radius != null) payload.put("radius", radius);
        if (enabled != null) payload.put("enabled", enabled);
        return new WsMessage("fence_sync", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    /** 媒体元数据广播（文件已上传服务器，WS 只传元数据） */
    public static WsMessage createMediaMeta(String deviceId, String pairCode, String fileId,
                                            String fileName, String mime, long size,
                                            Double duration, String from) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fileId", fileId);
        payload.put("fileName", fileName);
        payload.put("mime", mime);
        payload.put("size", size);
        if (duration != null) payload.put("duration", duration);
        payload.put("from", from);
        return new WsMessage("media", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    /** 媒体删除广播（双向同步删除） */
    public static WsMessage createMediaDeleted(String deviceId, String pairCode, String fileId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fileId", fileId);
        return new WsMessage("media_deleted", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    // --- 情侣任务消息类型（契约 v1，与 server 侧同步） ---

    /** 发布任务：taskId=客户端生成的幂等键；mediaFileIds=逗号分隔的配图 fileId 列表（可空=纯文字任务）；from=发布方昵称 */
    public static WsMessage createTaskPublish(String deviceId, String pairCode, String taskId,
                                              String content, String mediaFileIds,
                                              String rewardType, String rewardText, String from) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("content", content);
        if (mediaFileIds != null && !mediaFileIds.isEmpty()) {
            payload.put("mediaFileIds", mediaFileIds);
            payload.put("mediaFileId", mediaFileIds.split(",")[0]);
        }
        if (rewardType != null && !rewardType.isEmpty()) payload.put("rewardType", rewardType);
        if (rewardText != null && !rewardText.isEmpty()) payload.put("rewardText", rewardText);
        if (from != null && !from.isEmpty()) payload.put("from", from);
        return new WsMessage("task_publish", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    /** 响应任务：action=accept|reject；拒绝必填 reason */
    public static WsMessage createTaskRespond(String deviceId, String pairCode, String taskId,
                                              String action, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("action", action);
        if (reason != null && !reason.isEmpty()) payload.put("reason", reason);
        return new WsMessage("task_respond", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    /** 发布方确认任务完成（已完成❤） */
    public static WsMessage createTaskComplete(String deviceId, String pairCode, String taskId) {
        return new WsMessage("task_complete", deviceId, pairCode,
                Map.of("taskId", taskId), System.currentTimeMillis());
    }

    /** 接收方确认奖励兑现（已兑现❤） */
    public static WsMessage createTaskReward(String deviceId, String pairCode, String taskId) {
        return new WsMessage("task_reward", deviceId, pairCode,
                Map.of("taskId", taskId), System.currentTimeMillis());
    }

    // --- 好感度/等级系统消息类型（契约 v1，与 server 侧同步） ---

    /** 打卡上报：window=morning（早安 5-11 点）| evening（晚安 19-24 点）；积分由服务器记账，两端共享 */
    public static WsMessage createCheckIn(String deviceId, String pairCode, String window) {
        return new WsMessage("check_in", deviceId, pairCode,
                Map.of("window", window), System.currentTimeMillis());
    }

    // --- affection_sync 载荷读取辅助（服务器→客户端快照：points/level/progress/title/updatedAt） ---

    /** 共享积分（pair 级唯一值，双端永远相同） */
    public int affectionPoints() {
        return numPayload("points", 0);
    }

    /** 共享等级（1-6，升级是共同事件） */
    public int affectionLevel() {
        return numPayload("level", 0);
    }

    /** 当前等级进度（0~1，服务器按门槛表计算推送） */
    public double affectionProgress() {
        Object v = payload != null ? payload.get("progress") : null;
        return v instanceof Number ? ((Number) v).doubleValue() : 0d;
    }

    /** 等级称号（如 心动/热恋/情深） */
    public String affectionTitle() {
        Object v = payload != null ? payload.get("title") : null;
        return v instanceof String ? (String) v : "";
    }

    /** 快照更新时间（epoch ms） */
    public long affectionUpdatedAt() {
        return numPayload("updatedAt", 0L);
    }

    private int numPayload(String key, int def) {
        Object v = payload != null ? payload.get(key) : null;
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private long numPayload(String key, long def) {
        Object v = payload != null ? payload.get(key) : null;
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    // --- JSON 序列化/反序列化 ---

    public String toJson() {
        return GSON.toJson(this);
    }

    public static WsMessage fromJson(String json) {
        try {
            return GSON.fromJson(json, WsMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Getters ---

    public String getType() { return type; }
    public String getDeviceId() { return deviceId; }
    public String getPairCode() { return pairCode; }
    public Map<String, Object> getPayload() { return payload; }
    public long getTimestamp() { return timestamp; }

    public void setType(String type) { this.type = type; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
