package com.eyemonitor.util;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** 语音波形包络纯数学单测 */
public class WaveformMathTest {

    @Test
    public void bin_shortensToTarget() {
        float[] in = {1, 2, 3, 4};
        float[] out = WaveformMath.bin(in, 2);
        assertEquals(2, out.length);
    }

    @Test
    public void bin_averagesBins() {
        // 4 元件 → 2 段：(1+2)/2=1.5, (3+4)/2=3.5
        float[] out = WaveformMath.bin(new float[]{1f, 2f, 3f, 4f}, 2);
        assertArrayEquals(new float[]{1.5f, 3.5f}, out, 1e-6f);
    }

    @Test
    public void bin_emptyReturnsNull() {
        assertNull(WaveformMath.bin(new float[0], 4));
    }

    @Test
    public void normalize_mapsToZeroOne() {
        float[] out = WaveformMath.normalize(new float[]{0f, 2f, 4f});
        assertEquals(0f, out[0], 1e-6f);
        assertEquals(0.5f, out[1], 1e-6f);
        assertEquals(0.98f, out[2], 1e-6f); // 4/4=1 → 削顶 0.98
    }

    @Test
    public void normalize_allZeroKeeps() {
        float[] out = WaveformMath.normalize(new float[]{0f, 0f});
        assertEquals(2, out.length);
        assertEquals(0f, out[0], 1e-6f);
    }

    @Test
    public void csvRoundTrip() {
        float[] in = {0.1f, 0.5f, 1f};
        float[] back = WaveformMath.fromCsv(WaveformMath.toCsv(in));
        assertArrayEquals(in, back, 1e-5f);
    }

    @Test
    public void csvEmptyOrInvalidReturnsNull() {
        assertNull(WaveformMath.toCsv(null));
        assertNull(WaveformMath.fromCsv(null));
        assertNull(WaveformMath.fromCsv("abc"));
        assertNull(WaveformMath.fromCsv(""));
    }

    @Test
    public void constants_speckSegments() {
        assertEquals(40, WaveformMath.SEGMENTS);
    }
}