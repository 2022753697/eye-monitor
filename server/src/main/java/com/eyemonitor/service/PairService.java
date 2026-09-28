package com.eyemonitor.service;

import com.eyemonitor.model.WsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 配对码管理 + 消息路由。
 * <p>
 * 全部数据在内存中维护，Demo 阶段无需持久化。
 * 重启后所有配对关系将丢失，需重新配对。
 */
@Service
public class PairService {

    private static final Logger log = LoggerFactory.getLogger(PairService.class);
    private static final int CODE_LENGTH = 6;
    private static final int MAX_RETRY = 10;   // 生成不重复码的最大尝试次数
    private static final long PAIR_DISCONNECT_DELAY_MS = 5000L; // 配对完成后断开延迟清理（ms）

    /** 配对码 -> 配对信息 */
    private final ConcurrentHashMap<String, PairInfo> pairRegistry = new ConcurrentHashMap<>();

    /** deviceId -> pairCode（反向查找） */
    private final ConcurrentHashMap<String, String> deviceToPair = new ConcurrentHashMap<>();

    private final Random random = new Random();

    /** 延迟清理调度器 */
    private final ScheduledExecutorService delayScheduler = Executors.newScheduledThreadPool(2);

    /** 待延迟清理的记录：deviceId -> delay task */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingCleanup = new ConcurrentHashMap<>();

    /**
     * 处理配对请求。
     * <p>
     * 三种情况：
     * 1. 不带 pairCode → 生成新码，创建新配对
     * 2. 带有效 pairCode → 加入已有配对
     * 3. 带无效/已满的 pairCode → 返回错误
     */
    public synchronized WsMessage handlePairRequest(String deviceId, String pairCode,
                                                     WebSocketSession session) {
        // 如果设备已配对，先清理旧记录
        String oldCode = deviceToPair.get(deviceId);
        if (oldCode != null) {
            log.info("设备 {} 已有配对码 {}, 先清理 (pairRegistry keys={}, deviceToPair size={})",
                    deviceId, oldCode, pairRegistry.keySet(), deviceToPair.size());
            removeDeviceFromPair(deviceId, oldCode);
        }

        // 如果传入的 pairCode 有效（配对已完成），直接恢复配对
        if (pairCode != null && !pairCode.isBlank()) {
            PairInfo existingInfo = pairRegistry.get(pairCode);
            log.info("尝试恢复配对: device={}, pairCode={}, info={}, isComplete={}",
                    deviceId, pairCode, existingInfo != null, existingInfo != null && existingInfo.isComplete());
            if (existingInfo != null && existingInfo.isComplete()) {
                // 恢复配对 - 新 session 替代旧 session
                String peerId = existingInfo.getPeerDeviceId(deviceId);
                log.info("设备重连恢复配对: device={}, pairCode={}, peerDevice={}, peerSessionOpen={}",
                        deviceId, pairCode, peerId,
                        peerId != null && existingInfo.getPeerSession(deviceId) != null
                                && existingInfo.getPeerSession(deviceId).isOpen());
                if (deviceId.equals(existingInfo.getDeviceAId())) {
                    existingInfo.sessionA = session;
                } else {
                    existingInfo.sessionB = session;
                }
                deviceToPair.put(deviceId, pairCode);
                // 通知对端设备在线
                notifyPeerOnline(existingInfo, deviceId);
                return WsMessage.createPairConfirm(deviceId, pairCode, true);
            } else {
                log.info("设备重连但配对未完成或不存在: device={}, pairCode={}, isComplete={}",
                        deviceId, pairCode, existingInfo != null && existingInfo.isComplete());
            }
        }

        // 情况 1: 没有 pairCode → 创建新配对
        if (pairCode == null || pairCode.isBlank()) {
            String newCode = generateUniqueCode();
            PairInfo info = new PairInfo(newCode);
            info.setDeviceA(deviceId, session);
            pairRegistry.put(newCode, info);
            deviceToPair.put(deviceId, newCode);
            log.info("新配对创建: code={}, deviceA={}", newCode, deviceId);
            return WsMessage.createPairConfirm(deviceId, newCode, false);
        }

        // 情况 2: 有 pairCode → 尝试加入
        PairInfo info = pairRegistry.get(pairCode);
        log.info("加入配对请求: code={}, device={}, pairRegistry contains code={}, info={}, isComplete={}",
                pairCode, deviceId, pairRegistry.containsKey(pairCode), info != null, info != null && info.isComplete());
        if (info == null) {
            log.warn("无效配对码: code={}, device={}, pairRegistry keys={}", pairCode, deviceId, pairRegistry.keySet());
            return WsMessage.createError(deviceId, "配对码无效");
        }

        if (info.isFull()) {
            log.warn("配对已满: code={}, device={}, deviceA={}, deviceB={}",
                    pairCode, deviceId, info.getDeviceAId(), info.getDeviceBId());
            return WsMessage.createError(deviceId, "该配对码已有两台设备");
        }

        // 加入配对
        if (info.getSessionA() == null) {
            info.setDeviceA(deviceId, session);
            log.info("设置 deviceA: {}", deviceId);
        } else {
            info.setDeviceB(deviceId, session);
            log.info("设置 deviceB: {}", deviceId);
        }
        deviceToPair.put(deviceId, pairCode);
        info.setComplete(true);
        log.info("配对完成: code={}, deviceA={}, deviceB={}, deviceToPair size={}", pairCode,
                info.getDeviceAId(), info.getDeviceBId(), deviceToPair.size());

        // 通知双方配对成功
        notifyPeerOnline(info, deviceId);
        return WsMessage.createPairConfirm(deviceId, pairCode, true);
    }

    /**
     * 处理已配对设备的重连恢复请求。
     * 只更新 session，不重复加入配对。
     */
    public synchronized WsMessage handlePairRecover(String deviceId, String pairCode,
                                                     WebSocketSession session) {
        // 先尝试从 deviceToPair 查找
        String existingCode = deviceToPair.get(deviceId);
        log.info("handlePairRecover: device={}, pairCode={}, existingCode={}, deviceToPair={}",
                deviceId, pairCode, existingCode, deviceToPair);

        // 如果找不到，尝试从传入的 pairCode 查找
        if (existingCode == null && pairCode != null && !pairCode.isBlank()) {
            PairInfo info = pairRegistry.get(pairCode);
            log.info("handlePairRecover: 查找pairCode={}, info={}, isComplete={}",
                    pairCode, info != null, info != null && info.isComplete());
            if (info == null) {
                log.info("handlePairRecover: 配对已失效（服务器可能重启），拒绝恢复: device={}, pairCode={}",
                        deviceId, pairCode);
                // 服务器重启后内存配对丢失：不 fallback 创建新配对（两端会各自生成不同新码导致配对彻底断开），
                // 而是明确报错，由客户端清空本地配对并引导用户重新配对。
                return WsMessage.createError(deviceId, "配对已失效，请重新配对");
            }
            // 码存在：恢复/占位 session（不检查 complete，不修改 complete）
            if (deviceId.equals(info.getDeviceAId())) {
                info.sessionA = session;
                log.info("handlePairRecover: 更新deviceA session");
            } else if (deviceId.equals(info.getDeviceBId())) {
                info.sessionB = session;
                log.info("handlePairRecover: 更新deviceB session");
            } else if (info.getDeviceAId() == null) {
                info.setDeviceA(deviceId, session);
                log.info("handlePairRecover: deviceA 位空，设为 deviceA: {}", deviceId);
            } else if (info.getDeviceBId() == null) {
                info.setDeviceB(deviceId, session);
                log.info("handlePairRecover: deviceB 位空，设为 deviceB: {}", deviceId);
            } else {
                log.warn("handlePairRecover: 设备无法匹配且配对已满, device={}, pairCode={}", deviceId, pairCode);
                return WsMessage.createError(deviceId, "配对错误");
            }
            deviceToPair.put(deviceId, pairCode);
            // 通知对端设备在线
            notifyPeerOnline(info, deviceId);
            log.info("设备重连恢复配对: device={}, pairCode={}, complete={}", deviceId, pairCode, info.isComplete());
            return WsMessage.createPairConfirm(deviceId, pairCode, info.isComplete());
        }

        if (existingCode == null) {
            log.warn("设备未配对，无法恢复: device={}", deviceId);
            return WsMessage.createError(deviceId, "未配对，请先配对");
        }

        PairInfo info = pairRegistry.get(existingCode);
        log.info("handlePairRecover: 使用existingCode={}, info={}, isComplete={}",
                existingCode, info != null, info != null && info.isComplete());
        if (info == null) {
            log.warn("配对不存在，拒绝恢复: code={}, device={}", existingCode, deviceId);
            return WsMessage.createError(deviceId, "配对已失效，请重新配对");
        }

        // 只更新 session，不重复加入
        if (deviceId.equals(info.getDeviceAId())) {
            info.sessionA = session;
            log.info("handlePairRecover: 更新deviceA session (existingCode)");
        } else if (deviceId.equals(info.getDeviceBId())) {
            info.sessionB = session;
            log.info("handlePairRecover: 更新deviceB session (existingCode)");
        } else if (info.getDeviceAId() == null) {
            // 设备A 位置为空，将此设备设为 deviceA
            info.setDeviceA(deviceId, session);
            log.info("handlePairRecover: deviceA 位空，设为 deviceA: {}", deviceId);
        } else if (info.getDeviceBId() == null) {
            // 设备B 位置为空，将此设备设为 deviceB
            info.setDeviceB(deviceId, session);
            log.info("handlePairRecover: deviceB 位空，设为 deviceB: {}", deviceId);
        } else {
            log.warn("未知设备尝试恢复配对: device={}, pairCode={}", deviceId, existingCode);
            return WsMessage.createError(deviceId, "配对错误");
        }

        boolean recoverComplete = info.isComplete();
        if (recoverComplete) {
            // 通知对端设备在线
            notifyPeerOnline(info, deviceId);
            log.info("配对恢复成功: device={}, pairCode={}", deviceId, existingCode);
        } else {
            log.info("配对未完成（等待对方加入），保持配对状态: device={}, pairCode={}", deviceId, existingCode);
        }
        return WsMessage.createPairConfirm(deviceId, existingCode, recoverComplete);
    }

    /**
     * 将消息转发给配对的另一台设备。
     */
    public void forwardToPeer(String deviceId, WsMessage message) {
        String pairCode = deviceToPair.get(deviceId);
        log.info("forwardToPeer: device={}, type={}, pairCode={}, deviceToPair.size={}, pairRegistry.size={}",
                deviceId, message.getType(), pairCode, deviceToPair.size(), pairRegistry.size());

        if (pairCode == null) {
            log.warn("设备未配对，无法转发: device={}, type={}", deviceId, message.getType());
            return;
        }

        PairInfo info = pairRegistry.get(pairCode);
        log.info("forwardToPeer: code={}, info={}, isComplete={}, deviceA={}, deviceB={}, sessionA={}, sessionB={}",
                pairCode, info != null, info != null && info.isComplete(),
                info != null ? info.getDeviceAId() : null,
                info != null ? info.getDeviceBId() : null,
                info != null ? info.getSessionA() : null,
                info != null ? info.getSessionB() : null);

        if (info == null || !info.isComplete()) {
            log.warn("配对未完成，无法转发: code={}, type={}", pairCode, message.getType());
            return;
        }

        WebSocketSession peerSession = info.getPeerSession(deviceId);
        String peerId = info.getPeerDeviceId(deviceId);
        log.info("转发消息: type={}, from={}, to={}, peerSession={}, sessionOpen={}",
                message.getType(), deviceId, peerId, peerSession, peerSession != null && peerSession.isOpen());

        if (peerSession == null || !peerSession.isOpen()) {
            log.warn("对端不在线，无法转发: type={}, peerId={}, peerSession.isOpen={}",
                    message.getType(), peerId, peerSession != null && peerSession.isOpen());
            return;
        }

        sendMessage(peerSession, message);
        log.info("消息转发成功: type={}, to={}", message.getType(), peerId);
    }

    /**
     * 设备断开连接：仅清除 session 引用，保留配对身份（A/B）、complete 与 deviceToPair。
     * 配对关系在服务器运行期不因离线失效；设备重连时通过 pair_recover 恢复 session。
     */
    public synchronized void onDisconnect(WebSocketSession session) {
        String deviceId = findDeviceIdBySession(session);
        if (deviceId == null) {
            log.warn("onDisconnect: 无法找到设备ID, sessionId={}", session.getId());
            return;
        }

        String pairCode = deviceToPair.get(deviceId);
        log.info("onDisconnect: device={}, pairCode={}, deviceToPair size={}", deviceId, pairCode, deviceToPair.size());
        if (pairCode == null) return;

        PairInfo info = pairRegistry.get(pairCode);
        if (info == null) {
            log.warn("onDisconnect: 配对信息不存在, pairCode={}", pairCode);
            return;
        }

        // 仅清除该设备的 session 引用，不销毁 A/B 身份与 complete 状态
        info.clearSession(deviceId);

        // 通知对端对方可能离线
        WebSocketSession peerSession = info.getPeerSession(deviceId);
        if (peerSession != null && peerSession.isOpen()) {
            sendMessage(peerSession,
                    WsMessage.createPairConfirm(info.getPeerDeviceId(deviceId), pairCode, false));
        }
        log.info("onDisconnect: 设备离线（保留配对关系）: device={}, pairCode={}, complete={}, peerOpen={}",
                deviceId, pairCode, info.isComplete(), peerSession != null && peerSession.isOpen());
    }

    /**
     * 延迟后的最终清理。如果对方也未连接，彻底删除配对记录。
     * 注意：不清除 pairRegistry（保留配对码供 MonitorService 重连恢复），
     * 只清理 deviceToPair 中的设备映射。
     * <p>
     * 关键：如果设备在延迟窗口内已经重连（deviceToPair 已有新映射），
     * 说明配对由新连接接管，此时不要重复移除。
     */
    private synchronized void doFinalCleanup(String deviceId, String pairCode) {
        log.info("延迟清理到期: device={}, pairCode={}", deviceId, pairCode);
        PairInfo info = pairRegistry.get(pairCode);
        if (info == null) {
            pendingCleanup.remove(deviceId);
            return;
        }

        // 设备已重连（deviceToPair 里已有该设备的最新映射，可能仍是原 pairCode），跳过清理
        String currentCode = deviceToPair.get(deviceId);
        if (currentCode != null) {
            log.info("设备 {} 已重连（deviceToPair 指向 {}），取消延迟清理", deviceId, currentCode);
            pendingCleanup.remove(deviceId);
            return;
        }

        // 只清理 deviceToPair，不清除 pairRegistry 和 PairInfo
        // 这样 MonitorService 重连时 pair_recover 仍能正常恢复配对
        deviceToPair.remove(deviceId);
        pendingCleanup.remove(deviceId);

        // 如果配对已完成但当前设备不在了，通知对端设备离线
        if (info.isComplete()) {
            String peerId = info.getPeerDeviceId(deviceId);
            if (peerId != null) {
                WebSocketSession peerSession = info.getPeerSession(deviceId);
                if (peerSession != null && peerSession.isOpen()) {
                    sendMessage(peerSession,
                            WsMessage.createPairConfirm(peerId, pairCode, false));
                }
            }
        }

        // 如果设备不在了，更新 complete 状态（仅当双方都不在线时为 false）
        if (info.isComplete() && info.getSessionA() == null && info.getSessionB() == null) {
            info.setComplete(false);
        }

        log.info("最终清理完成: code={}, isEmpty={}, isComplete={}", pairCode, info.isEmpty(), info.isComplete());
    }

    /**
     * 通过 session 查找 deviceId。
     */
    private String findDeviceIdBySession(WebSocketSession session) {
        for (Map.Entry<String, String> entry : deviceToPair.entrySet()) {
            String devId = entry.getKey();
            String code = entry.getValue();
            PairInfo info = pairRegistry.get(code);
            if (info != null && info.hasSession(devId, session)) {
                return devId;
            }
        }
        return null;
    }

    /**
     * 发送消息到指定 session，捕获异常。
     */
    public void sendMessage(WebSocketSession session, WsMessage message) {
        if (session == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new org.springframework.web.socket.TextMessage(
                        message.toJson()));
            }
        } catch (IOException e) {
            log.error("发送消息失败: session={}, type={}", session.getId(), message.getType(), e);
        }
    }

    // --- 内部辅助方法 ---

    private String generateUniqueCode() {
        for (int i = 0; i < MAX_RETRY; i++) {
            String code = String.format("%06d", random.nextInt(1_000_000));
            if (!pairRegistry.containsKey(code)) {
                return code;
            }
        }
        // 极端情况：所有码都被占（几乎不可能）
        String code = String.format("%06d", System.currentTimeMillis() % 1_000_000);
        pairRegistry.remove(code); // 踢掉最旧的
        return code;
    }

    private void removeDeviceFromPair(String deviceId, String pairCode) {
        PairInfo info = pairRegistry.get(pairCode);
        log.debug("removeDeviceFromPair: deviceId={}, pairCode={}, info={}",
                deviceId, pairCode, info != null);
        if (info != null) {
            info.removeDevice(deviceId);
            log.debug("removeDeviceFromPair 后: sessionA={}, sessionB={}",
                    info.getSessionA(), info.getSessionB());
            // 注意：不在这里删除配对，由调用方根据情况决定
        }
        deviceToPair.remove(deviceId);
    }

    private void notifyPeerOnline(PairInfo info, String joinedDeviceId) {
        String peerId = info.getPeerDeviceId(joinedDeviceId);
        WebSocketSession peerSession = info.getPeerSession(joinedDeviceId);
        if (peerId != null && peerSession != null && peerSession.isOpen()) {
            sendMessage(peerSession,
                    WsMessage.createPairConfirm(peerId, info.getPairCode(), true));
        }
    }

    private WebSocketSession sessionOf(String deviceId, PairInfo info) {
        return deviceId.equals(info.getDeviceAId()) ? info.getSessionA() : info.getSessionB();
    }

    // --- PairInfo 内部类 ---

    public static class PairInfo {
        private final String pairCode;
        private String deviceAId;
        private WebSocketSession sessionA;
        private String deviceBId;
        private WebSocketSession sessionB;
        private boolean complete;
        private final long createdAt;

        public PairInfo(String pairCode) {
            this.pairCode = pairCode;
            this.createdAt = System.currentTimeMillis();
        }

        public boolean isFull() {
            return sessionA != null && sessionB != null;
        }

        public boolean isEmpty() {
            return sessionA == null && sessionB == null;
        }

        public boolean isComplete() { return complete; }
        public void setComplete(boolean complete) { this.complete = complete; }

        public String getPairCode() { return pairCode; }
        public long getCreatedAt() { return createdAt; }

        public String getDeviceAId() { return deviceAId; }
        public String getDeviceBId() { return deviceBId; }

        public WebSocketSession getSessionA() { return sessionA; }
        public WebSocketSession getSessionB() { return sessionB; }

        public void setDeviceA(String deviceId, WebSocketSession session) {
            this.deviceAId = deviceId;
            this.sessionA = session;
        }

        public void setDeviceB(String deviceId, WebSocketSession session) {
            this.deviceBId = deviceId;
            this.sessionB = session;
        }

        /** 获取对端 session */
        public WebSocketSession getPeerSession(String myDeviceId) {
            if (myDeviceId.equals(deviceAId)) return sessionB;
            if (myDeviceId.equals(deviceBId)) return sessionA;
            return null;
        }

        /** 获取对端 deviceId */
        public String getPeerDeviceId(String myDeviceId) {
            if (myDeviceId.equals(deviceAId)) return deviceBId;
            if (myDeviceId.equals(deviceBId)) return deviceAId;
            return null;
        }

        /** 检查指定 deviceId 是否拥有该 session */
        public boolean hasSession(String deviceId, WebSocketSession session) {
            if (deviceId.equals(deviceAId) && sessionA != null && sessionA.getId().equals(session.getId()))
                return true;
            if (deviceId.equals(deviceBId) && sessionB != null && sessionB.getId().equals(session.getId()))
                return true;
            return false;
        }

        /** 移除设备 */
        public void removeDevice(String deviceId) {
            if (deviceId.equals(deviceAId)) {
                deviceAId = null;
                sessionA = null;
            } else if (deviceId.equals(deviceBId)) {
                deviceBId = null;
                sessionB = null;
            }
            complete = false;
        }

        /** 仅清除 session 引用，保留设备身份与 complete 状态（用于离线重连恢复） */
        public void clearSession(String deviceId) {
            if (deviceId.equals(deviceAId)) {
                sessionA = null;
            } else if (deviceId.equals(deviceBId)) {
                sessionB = null;
            }
        }
    }
}