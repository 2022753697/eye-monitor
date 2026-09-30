package com.eyemonitor.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** P1 省电：轮询档位决策纯逻辑单测 */
public class AppUsageTrackerTest {

    @Test
    public void screenOff_noPollingRegardlessOfAccessibility() {
        assertEquals(-1L, AppUsageTracker.effectiveIntervalMs(false, false));
        assertEquals(-1L, AppUsageTracker.effectiveIntervalMs(false, true));
    }

    @Test
    public void screenOn_noAccessibility_5s() {
        assertEquals(5_000L, AppUsageTracker.effectiveIntervalMs(true, false));
    }

    @Test
    public void screenOn_withAccessibility_60sBackup() {
        assertEquals(60_000L, AppUsageTracker.effectiveIntervalMs(true, true));
    }

    @Test
    public void constantsMatchSpec() {
        assertEquals(5_000L, AppUsageTracker.POLL_INTERVAL_SCREEN_ON_MS);
        assertEquals(60_000L, AppUsageTracker.POLL_INTERVAL_ACCESSIBILITY_MS);
    }
}