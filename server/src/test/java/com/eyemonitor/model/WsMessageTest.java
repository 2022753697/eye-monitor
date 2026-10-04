package com.eyemonitor.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 服务端 WsMessage Jackson 序列化/反序列化往返测试。
 * 协议契约两端一致性的其中一半（客户端 Gson 侧在 android 模块测）。
 */
class WsMessageTest {

    @Test
    void chatRoundTrip() {
        WsMessage m = new WsMessage("chat", "dev-1", "123456",
                Map.of("text", "你好世界", "from", "WW"), 1234567890123L);
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
    void mediaRoundTrip() {
        WsMessage m = WsMessage.createMedia("dev-1", "123456",
                "file-9", "a.jpg", "image/jpeg", 1234L, null, "WW", null);
        WsMessage r = WsMessage.fromJson(m.toJson());
        assertNotNull(r);
        assertEquals("media", r.getType());
        assertEquals("file-9", r.getPayload().get("fileId"));
        assertEquals("WW", r.getPayload().get("from"));
    }

    @Test
    void sosRoundTrip() {
        WsMessage m = WsMessage.createSos("dev-1", "123456", "救救我", 30.0, 120.0);
        WsMessage r = WsMessage.fromJson(m.toJson());
        assertNotNull(r);
        assertEquals("sos", r.getType());
        assertEquals("救救我", r.getPayload().get("text"));
        assertEquals(30.0, (double) r.getPayload().get("lat"), 1e-6);
    }

    @Test
    void invalidJsonReturnsNull() {
        assertNull(WsMessage.fromJson("{broken json"));
    }

    @Test
    void affectionSyncRoundTrip() {
        WsMessage m = WsMessage.createAffectionSync("123456", 620L, 3, 0.05, "热恋");
        WsMessage r = WsMessage.fromJson(m.toJson());
        assertNotNull(r);
        assertEquals("affection_sync", r.getType());
        assertEquals("123456", r.getPairCode());
        assertEquals(620L, ((Number) r.getPayload().get("points")).longValue());
        assertEquals(3, ((Number) r.getPayload().get("level")).intValue());
        assertEquals(0.05, ((Number) r.getPayload().get("progress")).doubleValue(), 1e-6);
        assertEquals("热恋", r.getPayload().get("title"));
    }
}