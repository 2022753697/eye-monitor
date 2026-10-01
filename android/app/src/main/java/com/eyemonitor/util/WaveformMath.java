package com.eyemonitor.util;

/** 波形包络纯数学（可单测）：RMS 序列 → 定长分段 → 归一化 → CSV 序列化 */
public final class WaveformMath {

    /** 波形条数（微信式视觉密度） */
    public static final int SEGMENTS = 40;

    private WaveformMath() {}

    /** 把任意长度的 RMS 序列缩放到 target 段（每段取均值）。空输入返回 null */
    public static float[] bin(float[] rms, int target) {
        if (rms == null || rms.length == 0 || target <= 0) return null;
        float[] out = new float[target];
        for (int i = 0; i < target; i++) {
            int start = i * rms.length / target;
            int end = Math.max(start + 1, (i + 1) * rms.length / target);
            double sum = 0;
            for (int j = start; j < end; j++) sum += rms[j];
            out[i] = (float) (sum / (end - start));
        }
        return out;
    }

    /** 0..1 归一化；>98% 削顶（防离谱尖峰把整条压死）；全 0 原样返回 */
    public static float[] normalize(float[] bins) {
        if (bins == null || bins.length == 0) return bins;
        float max = 0;
        for (float f : bins) max = Math.max(max, f);
        if (max <= 0) return bins;
        float[] out = new float[bins.length];
        for (int i = 0; i < bins.length; i++) {
            float v = bins[i] / max;
            if (v > 0.98f) v = 0.98f;
            out[i] = v;
        }
        return out;
    }

    /** 序列化为 CSV（Float.toString 保留精度） */
    public static String toCsv(float[] vals) {
        if (vals == null || vals.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vals[i]);
        }
        return sb.toString();
    }

    /** 解析 CSV → float[]；非法输入返回 null */
    public static float[] fromCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) return null;
        String[] parts = csv.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }
}