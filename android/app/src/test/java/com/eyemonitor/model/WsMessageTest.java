package com.eyemonitor.model;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * 客户端 WsMessage Gson 序列化/反序列化往返测试。
 * 与服务端 Jackson 往返测试对照，守住协议两端一致性。
 */
public class WsMessageTest {

    @Test
    public void chatRoundTrip() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", "你好世界");
        payload.put("from", "WW");
        WsMessage m = new WsMessage("chat", "dev-1", "123456", payload, 1234567890123L);
        WsMessage r = WsMessage.fromJson(m.toJson());
        assertNotNull(r);
        assertEquals("chat", r.getType());
        assertEquals("dev-1", r.getDeviceId());
        assertEquals("123456", r.getPairCode());
        assertEquals("你好世界", r.getPayload().get("text"));
        assertEquals("WW", r.getPayload().get("from"));
        assertEquals(1234567890123L, r.getTimestamp());
    }

    @Test
    public void locationRoundTrip() {
        WsMessage m = WsMessage.createLocation("dev-1", "123456", 30.0, 120.0, 12.5f);
        WsMessage r = WsMessage.fromJson(m.toJson());
        assertNotNull(r);
        assertEquals("location", r.getType());
        assertEquals(30.0, (double) r.getPayload().get("lat"), 1e-6);
        assertEquals(12.5, (double) r.getPayload().get("accuracy"), 1e-6);
    }

    @Test
    public void invalidJsonReturnsNull() {
        assertNull(WsMessage.fromJson("{broken json"));
    }

    @Test
    public void checkInRoundTrip() {
        WsMessage m = WsMessage.createCheckIn("dev-1", "123456", "morning");
        WsMessage r = WsMessage.fromJson(m.toJson());
        assertNotNull(r);
        assertEquals("check_in", r.getType());
        assertEquals("morning", r.getPayload().get("window"));
    }

    @Test
    public void affectionSyncPayloadReaders() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("points", 621);
        payload.put("level", 3);
        payload.put("progress", 0.5);
        payload.put("title", "热恋");
        payload.put("updatedAt", 1700000000000L);
        WsMessage m = new WsMessage("affection_sync", "dev-1", "123456", payload,
                1700000000000L);
        WsMessage r = WsMessage.fromJson(m.toJson());
        assertNotNull(r);
        assertEquals("affection_sync", r.getType());
        assertEquals(621, r.affectionPoints());
        assertEquals(3, r.affectionLevel());
        assertEquals(0.5, r.affectionProgress(), 1e-6);
        assertEquals("热恋", r.affectionTitle());
        assertEquals(1700000000000L, r.affectionUpdatedAt());
    }
}