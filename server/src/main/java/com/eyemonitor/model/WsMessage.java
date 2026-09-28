package com.eyemonitor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * 统一 WebSocket 消息协议
 * <p>
 * 所有客户端和服务器之间的通信都使用此格式。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {

    /** 消息类型: pair_request, pair_confirm, app_switch, location, ping, pong, error */
    private String type;

    /** 设备唯一标识 (客户端生成的 UUID) */
    private String deviceId;

    /** 6位配对码 */
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

    public static WsMessage createPairRecover(String deviceId, String pairCode) {
        return new WsMessage("pair_recover", deviceId, pairCode, Map.of(), System.currentTimeMillis());
    }

    public static WsMessage createError(String deviceId, String message) {
        return new WsMessage("error", deviceId, null,
                Map.of("message", message), System.currentTimeMillis());
    }

    public static WsMessage createPong(String deviceId) {
        return new WsMessage("pong", deviceId, null, Map.of(), System.currentTimeMillis());
    }

    /** 请求对方立即发送位置 */
    public static WsMessage createRequestPeerLocation(String deviceId) {
        return new WsMessage("request_peer_location", deviceId, null, Map.of(), System.currentTimeMillis());
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