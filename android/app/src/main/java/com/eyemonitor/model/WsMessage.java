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
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", text);
        payload.put("from", from);
        return new WsMessage("chat", deviceId, pairCode, payload, System.currentTimeMillis());
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
