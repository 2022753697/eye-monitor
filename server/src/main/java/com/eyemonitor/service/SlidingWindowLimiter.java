package com.eyemonitor.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 登录/配对尝试限流器（F-01/F-05 安全加固，2026-10）。
 * <p>
 * 内存滑动窗口：连续失败 N 次 / 窗口 W，超限锁定 L 毫秒。
 * 单实例够用；多实例部署时换 Redis（见 backend-security-audit.md P2-5）。
 * 线程安全：按 key 分槽 + 槽内同步。
 * <p>
 * M-2/M-7（修复）：锁到期时清空失败记录（保证锁定时长与文案一致）；
 * 槽记录 lastAccess 并按概率惰性淘汰（防攻击者可控 key 无界增长）。
 */
public class SlidingWindowLimiter {

    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final int maxFail;
    private final long windowMs;
    private final long lockMs;

    public SlidingWindowLimiter(int maxFail, long windowMs, long lockMs) {
        this.maxFail = maxFail;
        this.windowMs = windowMs;
        this.lockMs = lockMs;
    }

    private static final class Slot {
        final Deque<Long> fails = new ArrayDeque<>();
        long lockedUntil = 0;
        long lastAccess = System.currentTimeMillis();
        long lastSweep = 0;
    }

    /** 当前 key 是否允许尝试（被锁则 false） */
    public boolean allowed(String key) {
        if (key == null) return true;
        Slot slot = slots.computeIfAbsent(key, k -> new Slot());
        synchronized (slot) {
            long now = System.currentTimeMillis();
            slot.lastAccess = now;
            if (now >= slot.lockedUntil) {
                if (slot.lockedUntil > 0) {
                    // M-2：锁到期 → 清空失败记录，保证"锁 30 分钟"即 30 分钟（而非窗口时长）
                    slot.fails.clear();
                    slot.lockedUntil = 0;
                }
                // 窗口滑掉过期失败
                while (!slot.fails.isEmpty() && now - slot.fails.peekFirst() > windowMs) {
                    slot.fails.pollFirst();
                }
                return slot.fails.size() < maxFail;
            }
            return false;
        }
    }

    /** 记录一次失败（超过阈值则进入锁定期） */
    public void recordFail(String key) {
        if (key == null) return;
        Slot slot = slots.computeIfAbsent(key, k -> new Slot());
        synchronized (slot) {
            long now = System.currentTimeMillis();
            slot.lastAccess = now;
            slot.fails.addLast(now);
            if (slot.fails.size() >= maxFail) {
                slot.lockedUntil = now + lockMs;
            }
        }
    }

    /** 成功时清空该 key 的失败记录 */
    public void recordSuccess(String key) {
        if (key == null) return;
        Slot slot = slots.get(key);
        if (slot != null) {
            synchronized (slot) {
                slot.fails.clear();
                slot.lockedUntil = 0;
            }
        }
    }

    /**
     * M-7：惰性淘汰——概率性清扫超过 24h 未访问的空槽（限制内存无界增长）。
     * 调用方无需显式触发；内部按 ~1/256 概率扫描全表，成本可控。
     */
    public void sweepIfNeeded() {
        if (slots.isEmpty() || ThreadLocalRandom.current().nextInt(256) != 0) return;
        long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
        for (Map.Entry<String, Slot> e : slots.entrySet()) {
            Slot s = e.getValue();
            synchronized (s) {
                if (s.lastAccess < cutoff && s.fails.isEmpty() && s.lockedUntil == 0) {
                    slots.remove(e.getKey(), s);
                }
            }
        }
    }
}