package com.eyemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一 WebSocket 消息协议（服务端）。
 * <p>
 * 所有客户端和服务器之间的通信都使用此格式。
 * Android 端 WsMessage 结构保持一致（见契约 v1）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {

    /**
     * 消息类型：
     * pair_request, pair_recover, pair_confirm, app_switch, location,
     * request_peer_location, chat, anniversary_sync, fence_sync,
     * user_profile, sos, sos_ack, device_status, media, media_deleted,
     * ping, pong, error
     */
    private String type;

    /** 设备唯一标识（客户端生成的 UUID） */
    private String deviceId;

    /** 6 位配对码 */
    private String pairCode;

    /** 消息负载，根据 type 不同结构不同 */
    private Map<String, Object> payload;

    /** 消息时间戳 (毫秒) */
    private long timestamp;

    public WsMessage() {}

    public WsMessage(String type, String deviceId, String pairCode, Map<String, Object> payload, long timestamp) {
        this.type = type;
        this.deviceId = deviceId;
        this.pairCode = pairCode;
        this.payload = payload;
        this.timestamp = timestamp;
    }

    // --- 工厂方法：快速创建常见消息 ---

    public static WsMessage createPairConfirm(String deviceId, String pairCode, boolean peerOnline) {
        return new WsMessage("pair_confirm", deviceId, pairCode,
                Map.of("pairCode", pairCode, "peerOnline", peerOnline), System.currentTimeMillis());
    }

    public static WsMessage createError(String deviceId, String code, String message) {
        Map<String, Object> payload = new HashMap<>();
        if (code != null) payload.put("code", code);
        payload.put("message", message);
        return new WsMessage("error", deviceId, null, payload, System.currentTimeMillis());
    }

    public static WsMessage createPong(String deviceId) {
        return new WsMessage("pong", deviceId, null, Map.of(), System.currentTimeMillis());
    }

    public static WsMessage createRequestPeerLocation(String deviceId) {
        return new WsMessage("request_peer_location", deviceId, null, Map.of(), System.currentTimeMillis());
    }

    public static WsMessage createAnniversarySync(String deviceId, String pairCode,
                                                  String action, long id, String name,
                                                  String date, boolean repeat, long updatedAt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        payload.put("id", id);
        if (name != null) payload.put("name", name);
        if (date != null) payload.put("date", date);
        payload.put("repeat", repeat);
        payload.put("updatedAt", updatedAt);
        return new WsMessage("anniversary_sync", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    public static WsMessage createFenceSync(String deviceId, String pairCode,
                                            String action, long id, String name,
                                            double lat, double lng, double radius, boolean enabled) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        payload.put("id", id);
        if (name != null) payload.put("name", name);
        payload.put("lat", lat);
        payload.put("lng", lng);
        payload.put("radius", radius);
        payload.put("enabled", enabled);
        return new WsMessage("fence_sync", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    public static WsMessage createUserProfile(String deviceId, String pairCode, Map<String, Object> profile) {
        return new WsMessage("user_profile", deviceId, pairCode, profile, System.currentTimeMillis());
    }

    public static WsMessage createSos(String deviceId, String pairCode, String text, Double lat, Double lng) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", text);
        if (lat != null) payload.put("lat", lat);
        if (lng != null) payload.put("lng", lng);
        return new WsMessage("sos", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    public static WsMessage createSosAck(String deviceId, String pairCode, String from) {
        return new WsMessage("sos_ack", deviceId, pairCode, Map.of("from", from), System.currentTimeMillis());
    }

    public static WsMessage createDeviceStatus(String deviceId, String pairCode, Map<String, Object> status) {
        return new WsMessage("device_status", deviceId, pairCode, status, System.currentTimeMillis());
    }

    public static WsMessage createFolderSync(String deviceId, String pairCode,
                                             String action, Long id, String name) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        payload.put("id", id);
        if (name != null) payload.put("name", name);
        return new WsMessage("folder_sync", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    public static WsMessage createMedia(String deviceId, String pairCode,
                                        String fileId, String fileName, String mime,
                                        long size, Long duration, String from, Long folderId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fileId", fileId);
        payload.put("fileName", fileName);
        payload.put("mime", mime);
        payload.put("size", size);
        if (duration != null) payload.put("duration", duration);
        payload.put("from", from);
        if (folderId != null) payload.put("folderId", folderId);
        return new WsMessage("media", deviceId, pairCode, payload, System.currentTimeMillis());
    }

    public static WsMessage createMediaDeleted(String deviceId, String pairCode, String fileId) {
        return new WsMessage("media_deleted", deviceId, pairCode,
                Map.of("fileId", fileId), System.currentTimeMillis());
    }

    // --- JSON 序列化/反序列化 ---

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化消息失败", e);
        }
    }

    public static WsMessage fromJson(String json) {
        try {
            return MAPPER.readValue(json, WsMessage.class);
        } catch (JsonProcessingException e) {
            return null; // 调用方检查 null
        }
    }

    // --- Getters / Setters ---

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}