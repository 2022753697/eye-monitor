package com.eyemonitor.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录/配对尝试限流器（F-01/F-05 安全加固，2026-10）。
 * <p>
 * 内存滑动窗口：连续失败 N 次 / 窗口 W，超限锁定 L 毫秒。
 * 单实例够用；多实例部署时换 Redis（见 backend-security-audit.md P2-5）。
 * 线程安全：按 key 分槽 + 槽内同步。
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
    }

    /** 当前 key 是否允许尝试（被锁则 false） */
    public boolean allowed(String key) {
        if (key == null) return true;
        Slot slot = slots.computeIfAbsent(key, k -> new Slot());
        synchronized (slot) {
            long now = System.currentTimeMillis();
            if (now < slot.lockedUntil) return false;
            // 窗口滑掉过期失败
            while (!slot.fails.isEmpty() && now - slot.fails.peekFirst() > windowMs) {
                slot.fails.pollFirst();
            }
            return slot.fails.size() < maxFail;
        }
    }

    /** 记录一次失败（超过阈值则进入锁定期） */
    public void recordFail(String key) {
        if (key == null) return;
        Slot slot = slots.computeIfAbsent(key, k -> new Slot());
        synchronized (slot) {
            long now = System.currentTimeMillis();
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
}
