package com.eyemonitor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.eyemonitor.R;

/**
 * 语音波形条（微信式）：一溜竖条包络；播放时已播段亮色/未播段淡色，随进度推进。
 */
public class WaveformView extends View {

    private float[] values;       // 0..1 包络（WaveformAnalyzer 输出）
    private float playedFraction; // 0..1 播放进度

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setData(float[] values) {
        this.values = values;
        this.playedFraction = 0f;
        invalidate();
    }

    public void setPlayedFraction(float f) {
        if (f < 0) f = 0f;
        if (f > 1) f = 1f;
        if (Math.abs(f - playedFraction) < 0.002f) return;
        playedFraction = f;
        postInvalidateOnAnimation();
    }

    public void reset() {
        playedFraction = 0f;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (values == null || values.length == 0) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        int n = values.length;
        float gap = 2f * density;
        float barW = Math.max(1.5f * density, (w - gap * (n - 1)) / (float) n);
        float minBarH = 4f * density;
        float maxBarH = Math.max(8f * density, h - 4f * density);

        int playedColor = ContextCompat.getColor(getContext(), R.color.primary);
        int dimColor = (playedColor & 0x00FFFFFF) | (0x38 << 24); // 主色 22% 透明 = 未播

        for (int i = 0; i < n; i++) {
            float v = values[i];
            if (v < 0.08f) v = 0.08f;
            float barH = Math.max(minBarH, maxBarH * v);
            float left = i * (barW + gap);
            float top = (h - barH) / 2f;
            paint.setColor(i / (float) n <= playedFraction ? playedColor : dimColor);
            canvas.drawRoundRect(left, top, left + barW, top + barH,
                    barW / 2f, barW / 2f, paint);
        }
    }
}