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

    /** 位置落库限流状态（按 pair）：[lastTs, lastLat, lastLng, day, count] */
    private static final double LOC_DEDUPE_DIST_M = 20.0;
    private static final long LOC_DEDUPE_WINDOW_MS = 5 * 60_000L;
    private static final long LOC_MIN_INTERVAL_MS = 60_000L;
    private static final float LOC_ACCURACY_MAX_M = 80.0f;
    private static final int LOC_DAILY_CAP = 1500;
    private final java.util.concurrent.ConcurrentHashMap<String, Object[]> locState =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void saveLocation(String pairCode, long userId, String deviceId,
                             Map<String, Object> payload, long ts) {
        try {
            if (pairCode == null || payload == null) return;
            double lat = num(payload.get("lat"));
            double lng = num(payload.get("lng"));
            float accuracy = (float) num(payload.get("accuracy"));
            long now = ts > 0 ? ts : System.currentTimeMillis();

            // R7 精度过滤：>80m 的点不可靠，不落库
            if (accuracy > LOC_ACCURACY_MAX_M) {
                log.info("位置过滤(精度>{}m) pair={} acc={}", LOC_ACCURACY_MAX_M, pairCode, accuracy);
                return;
            }
            // R5/R6/R8：静止去重 + 最小时距 + 每日上限（内存态，按 pair）
            Object[] st = locState.computeIfAbsent(pairCode, k -> new Object[]{0L, 0d, 0d, -1, 0L});
            synchronized (st) {
                long lastTs = (Long) st[0];
                double lastLat = (Double) st[1];
                double lastLng = (Double) st[2];
                int day = (int) (now / 86_400_000L);
                int lastDay = (Integer) st[3];
                long count = (Long) st[4];
                if (lastDay != day) {
                    lastDay = day;
                    count = 0;
                }
                if (count >= LOC_DAILY_CAP) {
                    log.warn("位置落库超每日上限({}) pair={}", LOC_DAILY_CAP, pairCode);
                    return;
                }
                if (lastTs > 0) {
                    double dist = haversine(lastLat, lastLng, lat, lng);
                    long since = now - lastTs;
                    if (dist < LOC_DEDUPE_DIST_M && since < LOC_DEDUPE_WINDOW_MS) {
                        log.debug("位置去重(静止<{}m,<5min) pair={}", LOC_DEDUPE_DIST_M, pairCode);
                        return;
                    }
                    if (since < LOC_MIN_INTERVAL_MS) {
                        log.debug("位置限频(<{}s) pair={}", LOC_MIN_INTERVAL_MS / 1000, pairCode);
                        return;
                    }
                }
                st[0] = now;
                st[1] = lat;
                st[2] = lng;
                st[3] = lastDay;
                st[4] = count + 1;
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

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
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