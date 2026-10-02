package com.eyemonitor.service;

import com.eyemonitor.entity.PairEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.repository.PairRepo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配对码管理 + 消息路由（DB 化）。
 * <p>
 * 业务变更：
 * 1) 配对关系真源在 MySQL（eye_pairs），配对绑定 user（由 WS 握手中的 token 解析）而非 device；
 * 2) 启动时从 DB 恢复全部配对身份，服务器重启后配对不丢，设备经 pair_recover 恢复 session；
 * 3) 连接态（session/deviceId）仍保存在内存。
 */
@Service
public class PairService {

    private static final Logger log = LoggerFactory.getLogger(PairService.class);
    private static final int MAX_RETRY = 10;

    private final PairRepo pairRepo;

    private final ConcurrentHashMap<String, PairInfo> pairRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> deviceToPair = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> userToPair = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    // F-01（安全加固 2026-10）：加入尝试限流（10 次/小时失败锁 30 分钟）+ PENDING 30 分钟过期
    private static final int PAIR_MAX_FAIL = 10;
    private static final long PAIR_WINDOW_MS = 3_600_000L;
    private static final long PAIR_LOCK_MS = 30 * 60_000L;
    private static final long PENDING_TTL_MS = 30 * 60_000L;
    private final SlidingWindowLimiter pairAttemptLimiter = new SlidingWindowLimiter(PAIR_MAX_FAIL, PAIR_WINDOW_MS, PAIR_LOCK_MS);

    public PairService(PairRepo pairRepo) {
        this.pairRepo = pairRepo;
    }

    @PostConstruct
    public void loadFromDb() {
        for (PairEntity e : pairRepo.findAll()) {
            PairInfo info = new PairInfo(e.getPairCode());
            info.userA = e.getUserA();
            info.userB = e.getUserB();
            info.complete = PairEntity.STATUS_COMPLETE.equals(e.getStatus());
            info.setCreatedAt(e.getCreatedAt());
            pairRegistry.put(e.getPairCode(), info);
            if (info.userA != null) userToPair.put(info.userA, e.getPairCode());
            if (info.userB != null) userToPair.put(info.userB, e.getPairCode());
            log.info("启动恢复配对: code={}, userA={}, userB={}, complete={}",
                    maskCode(e.getPairCode()), info.userA, info.userB, info.complete);
        }
    }

    // ==================== 配对主流程 ====================

    public synchronized WsMessage handlePairRequest(long userId, String deviceId,
                                                    String pairCode, WebSocketSession session) {
        // F-01：加入尝试限流（防 1e6 暴力枚举）
        String key = "pair:" + userId;
        if (!pairAttemptLimiter.allowed(key)) {
            return WsMessage.createError(deviceId, null, "配对尝试过于频繁，请 30 分钟后再试");
        }
        detachUserFromOldPair(userId);
        if (pairCode != null && !pairCode.isBlank()) {
            PairInfo info = pairRegistry.get(pairCode);
            if (info == null) {
                PairEntity e = pairRepo.findByPairCode(pairCode);
                if (e == null) {
                    log.warn("无效配对码: code={}, user={}", maskCode(pairCode), userId);
                    pairAttemptLimiter.recordFail(key);
                    return WsMessage.createError(deviceId, null, "配对码无效");
                }
                info = restoreFromEntity(e);
            }
            WsMessage result = joinOrRecover(info, userId, deviceId, session);
            if ("error".equals(result.getType())) {
                pairAttemptLimiter.recordFail(key);
            } else {
                pairAttemptLimiter.recordSuccess(key);
            }
            return result;
        }
        return createPair(userId, deviceId, session);
    }

    public synchronized WsMessage handlePairRecover(long userId, String deviceId,
                                                    String pairCode, WebSocketSession session) {
        PairInfo info = pairRegistry.get(pairCode);
        if (info == null) {
            PairEntity e = pairRepo.findByPairCode(pairCode);
            if (e == null) {
                log.warn("配对已失效（服务器可能重启且库中无记录）: user={}, code={}", userId, maskCode(pairCode));
                return WsMessage.createError(deviceId, null, "配对已失效，请重新配对");
            }
            info = restoreFromEntity(e);
        }
        if (!info.isMember(userId)) {
            log.warn("恢复配对但用户非该配对成员: user={}, code={}", userId, maskCode(pairCode));
            return WsMessage.createError(deviceId, null, "配对已失效，请重新配对");
        }
        return joinOrRecover(info, userId, deviceId, session);
    }

    private WsMessage createPair(long userId, String deviceId, WebSocketSession session) {
        String code = generateUniqueCode();
        PairInfo info = new PairInfo(code);
        info.userA = userId;
        info.deviceAId = deviceId;
        info.sessionA = session;
        info.complete = false;
        pairRegistry.put(code, info);
        bind(userId, deviceId, code);
        persistPair(info);
        log.info("新配对创建: code={}, userA={}", maskCode(code), userId);
        return WsMessage.createPairConfirm(deviceId, code, false);
    }

    private WsMessage joinOrRecover(PairInfo info, long userId, String deviceId, WebSocketSession session) {
        String pairCode = info.pairCode;
        if (info.isMember(userId)) {
            // 已有成员重新加入/重连：原位替换
            info.setMemberSession(userId, deviceId, session);
            info.complete = info.getPeerUser(userId) != null;
            bind(userId, deviceId, pairCode);
            persistPair(info);
            if (info.complete) {
                notifyPeerOnline(info, userId);
            }
            log.info("成员恢复会话: code={}, user={}, complete={}", maskCode(pairCode), userId, info.complete);
            return WsMessage.createPairConfirm(deviceId, pairCode, info.complete);
        }
        if (info.userA == null) {
            // F-01：PENDING 超时后禁止再被认领（需要重新创建）
            if (info.isExpiredPending()) {
                log.warn("配对码已过期: code={}", maskCode(pairCode));
                dropExpiredPair(info);
                return WsMessage.createError(deviceId, null, "配对码已过期，请重新创建");
            }
            info.userA = userId;
            info.deviceAId = deviceId;
            info.sessionA = session;
        } else if (info.userB == null) {
            // F-01：PENDING 超时后禁止被第二人认领
            if (info.isExpiredPending()) {
                log.warn("配对码已过期: code={}", maskCode(pairCode));
                dropExpiredPair(info);
                return WsMessage.createError(deviceId, null, "配对码已过期，请重新创建");
            }
            info.userB = userId;
            info.deviceBId = deviceId;
            info.sessionB = session;
        } else {
            log.warn("配对已满: code={}, user={}", maskCode(pairCode), userId);
            return WsMessage.createError(deviceId, null, "该配对码已有两台设备");
        }
        info.complete = info.userA != null && info.userB != null;
        bind(userId, deviceId, pairCode);
        persistPair(info);
        if (info.complete) {
            notifyPeerOnline(info, userId);
        }
        log.info("配对完成: code={}, userA={}, userB={}, complete={}", maskCode(pairCode), info.userA, info.userB, info.complete);
        return WsMessage.createPairConfirm(deviceId, pairCode, info.complete);
    }

    /** P1-5：配对码日志脱敏（保留前 3 位 + ***） */
    private static String maskCode(String code) {
        if (code == null || code.length() <= 3) return "***";
        return code.substring(0, 3) + "***";
    }

    /** 用户重新配对前，摘除其在旧配对中的身份（保持原“重新配对”语义） */
    private void detachUserFromOldPair(long userId) {
        String oldCode = userToPair.get(userId);
        if (oldCode == null) return;
        PairInfo info = pairRegistry.get(oldCode);
        if (info != null && info.isMember(userId)) {
            Long peerUser = info.getPeerUser(userId);
            WebSocketSession peerSession = info.getPeerSessionByUser(userId);
            if (peerUser != null && peerSession != null && peerSession.isOpen()) {
                sendMessage(peerSession,
                        WsMessage.createPairConfirm(info.getPeerDeviceIdByUser(userId), oldCode, false));
            }
            String oldDevice = info.getDeviceIdOf(userId);
            if (oldDevice != null) deviceToPair.remove(oldDevice);
            info.removeUser(userId);
            if (info.isEmptyUser()) {
                pairRegistry.remove(oldCode);
                removePairRow(oldCode);
            } else {
                persistPair(info);
            }
        } else if (info != null) {
            pairRegistry.remove(oldCode);
        }
        userToPair.remove(userId);
    }

    private PairInfo restoreFromEntity(PairEntity e) {
        PairInfo info = new PairInfo(e.getPairCode());
        info.userA = e.getUserA();
        info.userB = e.getUserB();
        info.complete = PairEntity.STATUS_COMPLETE.equals(e.getStatus());
        info.setCreatedAt(e.getCreatedAt());
        pairRegistry.put(e.getPairCode(), info);
        if (info.userA != null) userToPair.put(info.userA, e.getPairCode());
        if (info.userB != null) userToPair.put(info.userB, e.getPairCode());
        return info;
    }

    private void persistPair(PairInfo info) {
        try {
            PairEntity e = pairRepo.findByPairCode(info.pairCode);
            if (e == null) {
                e = new PairEntity();
                e.setPairCode(info.pairCode);
                e.setCreatedAt(System.currentTimeMillis());
            }
            e.setUserA(info.userA);
            e.setUserB(info.userB);
            e.setStatus(info.complete ? PairEntity.STATUS_COMPLETE : PairEntity.STATUS_PENDING);
            pairRepo.save(e);
        } catch (Exception ex) {
            log.error("持久化配对失败: code={}", maskCode(info.pairCode), ex);
        }
    }

    /** M-3（修复）：过期 PENDING 清理（registry + DB 行），防内存/表行累积 */
    private void dropExpiredPair(PairInfo info) {
        String code = info.getPairCode();
        pairRegistry.remove(code);
        userToPair.remove(info.userA);
        removePairRow(code);
    }

    private void removePairRow(String pairCode) {
        try {
            PairEntity e = pairRepo.findByPairCode(pairCode);
            if (e != null) pairRepo.delete(e);
        } catch (Exception ex) {
            log.error("删除配对行失败: code={}", maskCode(pairCode), ex);
        }
    }

    private void bind(long userId, String deviceId, String pairCode) {
        userToPair.put(userId, pairCode);
        if (deviceId != null) deviceToPair.put(deviceId, pairCode);
    }

    private String generateUniqueCode() {
        for (int i = 0; i < MAX_RETRY; i++) {
            String code = String.format("%06d", random.nextInt(1_000_000));
            if (!pairRegistry.containsKey(code) && pairRepo.findByPairCode(code) == null) {
                return code;
            }
        }
        return String.format("%06d", System.currentTimeMillis() % 1_000_000);
    }

    // ==================== 转发 ====================

    /**
     * F-02（安全加固 2026-10）：按 deviceId 转发给配对对端（客户端高频路径）。
     * 发送者归属校验：发送方 userId 必须属于该 deviceId 所在配对，否则拒绝转发（防跨配对注入/身份伪装）。
     */
    public void forwardToPeer(long senderUserId, String deviceId, WsMessage message) {
        String pairCode = deviceToPair.get(deviceId);
        if (pairCode == null) {
            log.warn("设备未配对，无法转发: device={}, type={}", deviceId, message.getType());
            return;
        }
        // F-02：发送者-配对绑定校验
        if (senderUserId <= 0 || !belongsToPair(senderUserId, pairCode)) {
            log.warn("拒绝转发：发送者不属于该配对 device={}, sender={}, type={}",
                    deviceId, senderUserId, message.getType());
            return;
        }
        PairInfo info = pairRegistry.get(pairCode);
        if (info == null) {
            log.warn("配对不存在，无法转发: code={}, type={}", pairCode, message.getType());
            return;
        }
        WebSocketSession peerSession = info.getPeerSessionByDeviceId(deviceId);
        if (peerSession == null || !peerSession.isOpen()) {
            log.debug("对端不在线，跳过转发: type={}, device={}", message.getType(), deviceId);
            return;
        }
        sendMessage(peerSession, message);
        log.debug("消息转发成功: type={}, from={}", message.getType(), deviceId);
    }

    /** 按用户转发给自己配对的对端（user_profile / media 等） */
    public void forwardToPeerByUser(long userId, WsMessage message) {
        PairInfo info = getPairOfUser(userId);
        if (info == null) return;
        WebSocketSession peerSession = info.getPeerSessionByUser(userId);
        if (peerSession == null || !peerSession.isOpen()) return;
        sendMessage(peerSession, message);
    }

    /** 广播给配对双方（anniversary_sync / fence_sync 等需要双端同步的消息） */
    public void broadcastToPair(String pairCode, WsMessage message) {
        PairInfo info = pairRegistry.get(pairCode);
        if (info == null) return;
        sendMessage(info.sessionA, message);
        sendMessage(info.sessionB, message);
    }

    /** 向配对双方广播；无配对则不发 */
    public void broadcastToPairByUser(long userId, WsMessage message) {
        PairInfo info = getPairOfUser(userId);
        if (info == null) return;
        sendMessage(info.sessionA, message);
        sendMessage(info.sessionB, message);
    }

    // ==================== 查询辅助（供 Controller） ====================

    public PairInfo getPairOfUser(long userId) {
        String code = userToPair.get(userId);
        return code == null ? null : pairRegistry.get(code);
    }

    public String getPairCodeOfUser(long userId) {
        return userToPair.get(userId);
    }

    /** 判断用户是否属于某配对（DB 兜底：内存缺失时从 DB 恢复） */
    public boolean belongsToPair(long userId, String pairCode) {
        if (pairCode == null) return false;
        PairInfo info = pairRegistry.get(pairCode);
        if (info == null) {
            PairEntity e = pairRepo.findByPairCode(pairCode);
            if (e != null) info = restoreFromEntity(e);
        }
        return info != null && info.isMember(userId);
    }

    // ==================== 解除配对 ====================

    /**
     * 解除配对：清内存 + 删 DB 行（业务接口 DELETE /api/pairs/{pairCode} 触发）。
     * 业务数据（聊天/轨迹/纪念日/围栏/媒体）的清理由调用方负责。
     */
    public synchronized void unpair(String pairCode) {
        PairInfo info = pairRegistry.get(pairCode);
        if (info != null) {
            if (info.sessionA != null && info.sessionA.isOpen()) {
                sendMessage(info.sessionA, WsMessage.createError(info.deviceAId, null, "配对已解除"));
            }
            if (info.sessionB != null && info.sessionB.isOpen()) {
                sendMessage(info.sessionB, WsMessage.createError(info.deviceBId, null, "配对已解除"));
            }
            closeQuietly(info.sessionA);
            closeQuietly(info.sessionB);
            if (info.userA != null) userToPair.remove(info.userA);
            if (info.userB != null) userToPair.remove(info.userB);
            if (info.deviceAId != null) deviceToPair.remove(info.deviceAId);
            if (info.deviceBId != null) deviceToPair.remove(info.deviceBId);
            pairRegistry.remove(pairCode);
        }
        removePairRow(pairCode);
        log.info("解除配对: code={}", maskCode(pairCode));
    }

    // ==================== 连接生命周期 ====================

    public synchronized void onDisconnect(WebSocketSession session) {
        String deviceId = findDeviceIdBySession(session);
        if (deviceId == null) {
            log.warn("onDisconnect: 无法找到设备ID, sessionId={}", session.getId());
            return;
        }
        String pairCode = deviceToPair.get(deviceId);
        if (pairCode == null) return;
        PairInfo info = pairRegistry.get(pairCode);
        if (info == null) return;

        info.clearSession(deviceId);

        WebSocketSession peerSession = info.getPeerSessionByDeviceId(deviceId);
        if (peerSession != null && peerSession.isOpen()) {
            sendMessage(peerSession,
                    WsMessage.createPairConfirm(info.getPeerDeviceIdByDeviceId(deviceId), pairCode, false));
        }
        log.info("设备离线（保留配对关系）: device={}, code={}", deviceId, pairCode);
    }

    private String findDeviceIdBySession(WebSocketSession session) {
        for (Map.Entry<String, String> entry : deviceToPair.entrySet()) {
            PairInfo info = pairRegistry.get(entry.getValue());
            if (info != null && info.getSessionByDeviceId(entry.getKey()) == session) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void sendMessage(WebSocketSession session, WsMessage message) {
        if (session == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new org.springframework.web.socket.TextMessage(message.toJson()));
            }
        } catch (IOException e) {
            log.error("发送消息失败: session={}, type={}", session.getId(), message.getType(), e);
        }
    }

    private void closeQuietly(WebSocketSession session) {
        if (session == null) return;
        try {
            if (session.isOpen()) session.close();
        } catch (Exception ignored) {}
    }

    private void notifyPeerOnline(PairInfo info, long joinedUserId) {
        WebSocketSession peerSession = info.getPeerSessionByUser(joinedUserId);
        if (peerSession != null && peerSession.isOpen()) {
            sendMessage(peerSession,
                    WsMessage.createPairConfirm(info.getPeerDeviceIdByUser(joinedUserId), info.pairCode, true));
        }
    }

    // ==================== PairInfo 内部类 ====================

    public static class PairInfo {
        private final String pairCode;
        private Long userA;
        private Long userB;
        private String deviceAId;
        private String deviceBId;
        private WebSocketSession sessionA;
        private WebSocketSession sessionB;
        private boolean complete;
        /** F-01：创建时间（PENDING 30 分钟过期判定） */
        private long createdAt = System.currentTimeMillis();
        /** M-3（修复）：DB 持久化创建时间回填（重启后过期语义不复活） */
        void setCreatedAt(long ts) { if (ts > 0) this.createdAt = ts; }

        public PairInfo(String pairCode) {
            this.pairCode = pairCode;
        }

        public boolean isExpiredPending() {
            // 未完成的配对（无 userB）超时作废
            return userB == null && System.currentTimeMillis() - createdAt > PENDING_TTL_MS;
        }

        public String getPairCode() { return pairCode; }

        public boolean isMember(Long userId) {
            return userId != null && (userId.equals(userA) || userId.equals(userB));
        }

        public boolean isEmptyUser() {
            return userA == null && userB == null;
        }

        public boolean isComplete() { return complete; }

        public Long getPeerUser(long myId) {
            if (userA != null && myId == userA) return userB;
            if (userB != null && myId == userB) return userA;
            return null;
        }

        public String getDeviceIdOf(long userId) {
            if (userA != null && userId == userA) return deviceAId;
            if (userB != null && userId == userB) return deviceBId;
            return null;
        }

        public WebSocketSession getSessionByUserId(long userId) {
            if (userA != null && userId == userA) return sessionA;
            if (userB != null && userId == userB) return sessionB;
            return null;
        }

        public WebSocketSession getSessionByDeviceId(String deviceId) {
            if (deviceId != null && deviceId.equals(deviceAId)) return sessionA;
            if (deviceId != null && deviceId.equals(deviceBId)) return sessionB;
            return null;
        }

        public WebSocketSession getPeerSessionByUser(long myId) {
            if (userA != null && myId == userA) return sessionB;
            if (userB != null && myId == userB) return sessionA;
            return null;
        }

        public String getPeerDeviceIdByUser(long myId) {
            if (userA != null && myId == userA) return deviceBId;
            if (userB != null && myId == userB) return deviceAId;
            return null;
        }

        public WebSocketSession getPeerSessionByDeviceId(String deviceId) {
            if (deviceId != null && deviceId.equals(deviceAId)) return sessionB;
            if (deviceId != null && deviceId.equals(deviceBId)) return sessionA;
            return null;
        }

        public String getPeerDeviceIdByDeviceId(String deviceId) {
            if (deviceId != null && deviceId.equals(deviceAId)) return deviceBId;
            if (deviceId != null && deviceId.equals(deviceBId)) return deviceAId;
            return null;
        }

        public void setMemberSession(long userId, String deviceId, WebSocketSession session) {
            if (userA != null && userId == userA) {
                deviceAId = deviceId;
                sessionA = session;
            } else if (userB != null && userId == userB) {
                deviceBId = deviceId;
                sessionB = session;
            }
        }

        public void removeUser(long userId) {
            if (userA != null && userId == userA) {
                userA = null;
                deviceAId = null;
                sessionA = null;
            } else if (userB != null && userId == userB) {
                userB = null;
                deviceBId = null;
                sessionB = null;
            }
            complete = false;
        }

        public void clearSession(String deviceId) {
            if (deviceId != null && deviceId.equals(deviceAId)) sessionA = null;
            if (deviceId != null && deviceId.equals(deviceBId)) sessionB = null;
        }
    }
}