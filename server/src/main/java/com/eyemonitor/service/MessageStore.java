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
 * chat 落库后挂好感度钩子（回合交替 +1 / 首字自动打卡 +3，AffectionService 内部同样容错）。
 */
@Component
public class MessageStore {

    private static final Logger log = LoggerFactory.getLogger(MessageStore.class);

    private final ChatMessageRepo chatRepo;
    private final LocationPointRepo locRepo;
    private final SosLogRepo sosRepo;
    private final AffectionService affectionService;

    public MessageStore(ChatMessageRepo chatRepo, LocationPointRepo locRepo, SosLogRepo sosRepo,
                        AffectionService affectionService) {
        this.chatRepo = chatRepo;
        this.locRepo = locRepo;
        this.sosRepo = sosRepo;
        this.affectionService = affectionService;
    }

    /** 撤回允许窗口（毫秒）：与规格「撤回限 2 分钟」一致 */
    public static final long RECALL_WINDOW_MS = 2 * 60_000L;

    /** 幂等检查：同配对同 ts 是否已存在（autoResend 补发/重连重投时防重复落库+重复转发） */
    public boolean chatExists(String pairCode, long ts) {
        if (pairCode == null || ts <= 0) return false;
        try {
            return chatRepo.findFirstByPairCodeAndTs(pairCode, ts) != null;
        } catch (Exception ex) {
            log.error("聊天消息幂等检查失败", ex);
            return false;
        }
    }

    public void saveChat(String pairCode, long fromUser, String text, boolean isSystem, long ts) {
        saveChat(pairCode, fromUser, text, isSystem, ts, "chat", null, null);
    }

    public void saveChat(String pairCode, long fromUser, String text, boolean isSystem,
                         long ts, String kind) {
        saveChat(pairCode, fromUser, text, isSystem, ts, kind, null, null);
    }

    public void saveChat(String pairCode, long fromUser, String text, boolean isSystem,
                         long ts, String kind, Long refMsgId, String refText) {
        try {
            if (pairCode == null || text == null) return;
            // 幂等：同配对同 ts 已存在则跳过（客户端 autoResend 补发/重连重投时防重复落库+重复转发）
            ChatMessageEntity exist = chatRepo.findFirstByPairCodeAndTs(pairCode, ts);
            if (exist != null) {
                log.debug("聊天消息幂等跳过: pair={} ts={} text={}", pairCode, ts, text);
                return;
            }
            ChatMessageEntity e = new ChatMessageEntity();
            e.setPairCode(pairCode);
            e.setFromUser(fromUser);
            e.setText(text);
            e.setSystem(isSystem);
            e.setTs(ts > 0 ? ts : System.currentTimeMillis());
            e.setKind(kind);
            e.setRefMsgId(refMsgId != null && refMsgId > 0 ? refMsgId : null);
            e.setRefText(refText);
            chatRepo.save(e);
            // 好感度钩子：回合交替 +1 + 首字自动打卡 +3（独立 try/catch，绝不影响落库/转发链路）
            try {
                affectionService.onChatMessage(pairCode, fromUser, text, isSystem,
                        kind == null ? "chat" : kind, ts > 0 ? ts : System.currentTimeMillis());
            } catch (Exception ex) {
                log.error("聊天好感度钩子异常", ex);
            }
        } catch (Exception ex) {
            log.error("聊天消息落库失败", ex);
        }
    }

    /**
     * 标记已读（chat_read）：该配对中非 reader 发送、ts &lt;= upToTs 且未读的消息置 read_ts。
     *
     * @return 本次新标记条数
     */
    public int markChatRead(String pairCode, long readerUserId, long upToTs) {
        try {
            if (pairCode == null) return 0;
            return chatRepo.markRead(pairCode, readerUserId, upToTs, System.currentTimeMillis());
        } catch (Exception ex) {
            log.error("标记已读失败", ex);
            return 0;
        }
    }

    /**
     * 撤回（chat_recall）：按 时间戳 定位该配对消息，2 分钟窗口内置 deleted。
     * <p>F-07（安全加固）：只能撤回自己发送的消息（fromUser == operatorUserId），防越权删除对方消息。
     *
     * @return true=撤回成功（窗口内、归属本人且找到消息）；false=超时/未找到/非本人（不入库不转发）
     */
    public boolean recallChat(String pairCode, long msgTs, long operatorUserId) {
        try {
            if (pairCode == null) return false;
            ChatMessageEntity e = chatRepo.findFirstByPairCodeAndTs(pairCode, msgTs);
            if (e == null) {
                log.warn("撤回失败：消息不存在 pair={} ts={}", pairCode, msgTs);
                return false;
            }
            // F-07：只能撤回自己的消息
            if (e.getFromUser() == null || e.getFromUser() != operatorUserId) {
                log.warn("撤回失败：只能撤回自己的消息 pair={} ts={} op={}",
                        pairCode, msgTs, operatorUserId);
                return false;
            }
            long now = System.currentTimeMillis();
            if (now - e.getTs() > RECALL_WINDOW_MS) {
                log.warn("撤回失败：超出 2 分钟窗口 pair={} ts={}", pairCode, msgTs);
                return false;
            }
            e.setDeleted(true);
            chatRepo.save(e);
            return true;
        } catch (Exception ex) {
            log.error("撤回失败", ex);
            return false;
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