package com.eyemonitor.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 录音实时声波条：6 根圆角竖条，高度由 setAmplitude(0..1) 驱动。
 * 纯绘制组件，不持录音资源；取消态颜色由 setColor() 切换。
 */
public class LiveWaveView extends View {

    private static final int BAR_COUNT = 6;
    private static final float MIN_BAR_DP = 8f;
    private static final float MAX_BAR_DP = 40f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float[] amps = new float[BAR_COUNT];
    private int barColor = 0xFF6B6B;

    public LiveWaveView(Context context) {
        this(context, null);
    }

    public LiveWaveView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LiveWaveView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        int fallback = 0xFF6B6B;
        if (attrs != null) {
            TypedArray ta = context.obtainStyledAttributes(attrs,
                    new int[]{android.R.attr.color});
            fallback = ta.getColor(0, fallback);
            ta.recycle();
        }
        setColor(fallback);
    }

    /** 设置条颜色（取消态传 status_error 红） */
    public void setColor(int color) {
        barColor = color;
        paint.setColor(barColor);   // ← 关键修复：应用颜色到画笔
        invalidate();
    }

    /** 更新实时振幅（0..1），带轻平滑避免闪烁；外部每 50ms 调用 */
    public void setAmplitude(float v) {
        if (v < 0) v = 0;
        if (v > 1) v = 1;
        System.arraycopy(amps, 1, amps, 0, amps.length - 1);
        amps[amps.length - 1] = v;
        invalidate();
    }

    /** 冻结当前波形（上滑取消时不再跳动） */
    public void freeze() {
        invalidate();
    }

    /** 复位归零（录音结束/面板隐藏时） */
    public void reset() {
        for (int i = 0; i < amps.length; i++) amps[i] = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float barW = 4f * density;
        float gap = 4f * density;
        float total = BAR_COUNT * barW + (BAR_COUNT - 1) * gap;
        float x = (w - total) / 2f;
        float centerY = h / 2f;
        float minBar = MIN_BAR_DP * density;
        float maxBar = MAX_BAR_DP * density;
        RectF rect = new RectF();
        for (int i = 0; i < BAR_COUNT; i++) {
            float amp = amps[i];
            float barH = minBar + (maxBar - minBar) * amp;
            float top = centerY - barH / 2f;
            rect.set(x, top, x + barW, top + barH);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
            x += barW + gap;
        }
    }
}
