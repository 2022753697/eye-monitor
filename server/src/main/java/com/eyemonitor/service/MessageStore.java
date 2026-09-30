package com.eyemonitor.service;

import com.eyemonitor.entity.ChatMessageEntity;
import com.eyemonitor.entity.LocationPointEntity;
import com.eyemonitor.entity.SosLogEntity;
import com.eyemonitor.repository.ChatMessageRepo;
import com.eyemonitor.repository.LocationPointRepo;
import com.eyemonitor.repository.SosLogRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WS 消息落库（chat / location / sos）。
 * 所有写入包 try/catch，绝不让持久化失败影响 WS 转发链路。
 */
@Component
public class MessageStore {

    private static final Logger log = LoggerFactory.getLogger(MessageStore.class);

    private final ChatMessageRepo chatRepo;
    private final LocationPointRepo locRepo;
    private final SosLogRepo sosRepo;

    public MessageStore(ChatMessageRepo chatRepo, LocationPointRepo locRepo, SosLogRepo sosRepo) {
        this.chatRepo = chatRepo;
        this.locRepo = locRepo;
        this.sosRepo = sosRepo;
    }

    public void saveChat(String pairCode, long fromUser, String text, boolean isSystem, long ts) {
        saveChat(pairCode, fromUser, text, isSystem, ts, "chat");
    }

    public void saveChat(String pairCode, long fromUser, String text, boolean isSystem,
                         long ts, String kind) {
        try {
            if (pairCode == null || text == null) return;
            ChatMessageEntity e = new ChatMessageEntity();
            e.setPairCode(pairCode);
            e.setFromUser(fromUser);
            e.setText(text);
            e.setSystem(isSystem);
            e.setTs(ts > 0 ? ts : System.currentTimeMillis());
            e.setKind(kind);
            chatRepo.save(e);
        } catch (Exception ex) {
            log.error("聊天消息落库失败", ex);
        }
    }

    /** 位置落库限流（R5/R6/R7/R8 判定逻辑在 {@link LocationThrottle}，内存态按 pair） */
    private final LocationThrottle locationThrottle = new LocationThrottle();

    public void saveLocation(String pairCode, long userId, String deviceId,
                             Map<String, Object> payload, long ts) {
        try {
            if (pairCode == null || payload == null) return;
            double lat = num(payload.get("lat"));
            double lng = num(payload.get("lng"));
            float accuracy = (float) num(payload.get("accuracy"));
            long now = ts > 0 ? ts : System.currentTimeMillis();

            LocationThrottle.Reason reason = locationThrottle.decideAndRecord(
                    pairCode, now, lat, lng, accuracy);
            if (reason != LocationThrottle.Reason.ACCEPT) {
                log.info("位置过滤({}) pair={} acc={}", reason, pairCode, accuracy);
                return;
            }

            LocationPointEntity e = new LocationPointEntity();
            e.setPairCode(pairCode);
            e.setUserId(userId);
            e.setDeviceId(deviceId);
            e.setLat(lat);
            e.setLng(lng);
            e.setAccuracy(accuracy);
            e.setTs(now);
            locRepo.save(e);
        } catch (Exception ex) {
            log.error("位置落库失败", ex);
        }
    }

    public void saveSos(String pairCode, long fromUser, Map<String, Object> payload, long ts) {
        try {
            if (pairCode == null || payload == null) return;
            SosLogEntity e = new SosLogEntity();
            e.setPairCode(pairCode);
            e.setFromUser(fromUser);
            Object text = payload.get("text");
            e.setText(text == null ? null : String.valueOf(text));
            Double lat = payload.get("lat") instanceof Number ? ((Number) payload.get("lat")).doubleValue() : null;
            Double lng = payload.get("lng") instanceof Number ? ((Number) payload.get("lng")).doubleValue() : null;
            e.setLat(lat);
            e.setLng(lng);
            e.setTs(ts > 0 ? ts : System.currentTimeMillis());
            sosRepo.save(e);
        } catch (Exception ex) {
            log.error("SOS 落库失败", ex);
        }
    }

    private double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0d;
    }
}