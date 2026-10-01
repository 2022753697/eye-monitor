package com.eyemonitor.util;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 语音波形分析：m4a/音频文件 → MediaCodec 解码 PCM → 逐缓冲 RMS → 40 段包络 CSV。
 * <p>
 * 仅视觉参考（微信同理非精准振幅）；解码/分析失败返回 null（UI 回退 wifi 图标）。
 * 调用方放在后台线程（dbExecutor），不卡 UI。
 */
public final class WaveformAnalyzer {

    private static final String TAG = "WaveformAnalyzer";

    private WaveformAnalyzer() {}

    /** 分析音频文件，返回 40 段包络 CSV；失败返回 null */
    public static String analyze(File audioFile) {
        try {
            float[] rms = decodeToRms(audioFile);
            if (rms == null || rms.length == 0) return null;
            float[] bins = WaveformMath.bin(rms, WaveformMath.SEGMENTS);
            float[] norm = WaveformMath.normalize(bins);
            return WaveformMath.toCsv(norm);
        } catch (Exception e) {
            Log.w(TAG, "波形分析失败: " + e.getMessage());
            return null;
        }
    }

    /** 解码音频 → 每输出缓冲一个 RMS 值（视觉包络即可，无需精确定时） */
    private static float[] decodeToRms(File file) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            String mime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String m = extractor.getTrackFormat(i).
                        getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    track = i;
                    mime = m;
                    break;
                }
            }
            if (track < 0) return null;
            extractor.selectTrack(track);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(extractor.getTrackFormat(track), null, null, 0);
            codec.start();

            List<Float> rms = new ArrayList<>();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIdx);
                        int size = extractor.readSampleData(in, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIdx);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        rms.add(rmsOf16BitPcm(out));
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                    }
                }
            }
            float[] arr = new float[rms.size()];
            for (int i = 0; i < rms.size(); i++) arr[i] = rms.get(i);
            return arr;
        } catch (Exception e) {
            Log.w(TAG, "解码失败: " + e.getMessage());
            return null;
        } finally {
            try { if (codec != null) codec.release(); } catch (Exception ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
        }
    }

    /** 16-bit PCM RMS */
    private static float rmsOf16BitPcm(ByteBuffer pcm) {
        long sum = 0;
        int n = pcm.remaining() / 2;
        for (int i = 0; i < n; i++) {
            short s = pcm.getShort();
            sum += (long) s * s;
        }
        return n > 0 ? (float) Math.sqrt((double) sum / n) : 0f;
    }
}