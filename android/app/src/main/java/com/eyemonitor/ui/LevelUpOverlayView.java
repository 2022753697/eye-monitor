package com.eyemonitor.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.eyemonitor.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 升级全屏仪式动画（好感度/等级系统）：自绘 Canvas 爱心/爪印粒子飘落，零第三方依赖。
 * <p>
 * ~3 秒自动结束（点击任意处立即跳过）；配色随主题（默认=珊瑚粉/暖橙，狗狗乐园=爪印粉系）。
 */
public class LevelUpOverlayView extends View {

    private static final long DURATION_MS = 3000L;
    private static final int PARTICLE_COUNT = 36;

    private final Random random = new Random();
    private final List<Particle> particles = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heartPath = new Path();
    private final Path pawPath = new Path();
    private final boolean pawMode;
    private final int[] colors;
    private final String titleLine;
    private final String subLine;
    private ValueAnimator animator;
    private boolean finished;

    private static class Particle {
        float x;
        float y;
        float size;
        float speed;
        float swayAmp;
        float swayFreq;
        float phase;
        float rotation;
        float rotSpeed;
        int color;
        boolean paw;
    }

    public LevelUpOverlayView(Context context, int level, String title, int[] colors,
                              boolean pawMode) {
        this(context, null, level, title, colors, pawMode);
    }

    public LevelUpOverlayView(Context context, AttributeSet attrs, int level, String title,
                              int[] colors, boolean pawMode) {
        super(context, attrs);
        this.colors = colors != null && colors.length > 0 ? colors : new int[]{0xFFFF6B6B};
        this.pawMode = pawMode;
        this.titleLine = context.getString(R.string.affection_level_up_chat, level);
        this.subLine = (title != null && !title.isEmpty())
                ? context.getString(R.string.affection_level_up_notify_text, title)
                : context.getString(R.string.affection_level_up_overlay_hint);
        buildHeartPath();
        buildPawPath();
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> skip());
    }

    /** 启动动画（3s，可点跳过；结束后自移除） */
    public void startAnimation(final Runnable onFinished) {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            if (finished) return;
            float t = (float) a.getAnimatedValue();
            // 粒子随时间渐进生成（前 60% 内陆续出现）
            int target = Math.min(PARTICLE_COUNT, (int) (t * PARTICLE_COUNT * 1.6f));
            while (particles.size() < target) {
                particles.add(spawn());
            }
            float dt = 16f / 1000f;
            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle p = particles.get(i);
                p.y += p.speed * dt * 60f;
                p.phase += p.swayFreq * dt;
                p.rotation += p.rotSpeed * dt;
                if (p.y - p.size > getHeight() + 40) particles.remove(i);
            }
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                finish(onFinished);
            }
        });
        animator.start();
    }

    private Particle spawn() {
        Particle p = new Particle();
        p.x = random.nextInt(Math.max(1, getWidth()));
        p.y = -20 - random.nextInt(120);
        p.size = 14 + random.nextFloat() * 26;
        p.speed = 90 + random.nextFloat() * 130;
        p.swayAmp = 24 + random.nextFloat() * 40;
        p.swayFreq = 1.2f + random.nextFloat() * 1.6f;
        p.phase = random.nextFloat() * 6.28f;
        p.rotation = random.nextFloat() * 30 - 15;
        p.rotSpeed = 60 + random.nextFloat() * 120;
        p.color = colors[random.nextInt(colors.length)];
        p.paw = pawMode && random.nextInt(100) < 40;
        return p;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        skip();
    }

    private void skip() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void finish(Runnable onFinished) {
        if (finished) return;
        finished = true;
        if (onFinished != null) onFinished.run();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            skip();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // 半透明暖白底（字可读、底下聊天可见）
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x14FF6B6B);
        canvas.drawRect(0, 0, w, h, paint);

        for (Particle p : particles) {
            float cx = p.x + (float) Math.sin(p.phase) * p.swayAmp;
            float cy = p.y;
            paint.setColor(p.color);
            float alpha = 1f;
            // 出场/入场淡出：出生 0.5s 内淡入，越界前淡出
            if (cy < 40) {
                alpha = Math.max(0f, cy / 40f);
            } else if (cy > h - 40) {
                alpha = Math.max(0f, (h - cy) / 40f);
            }
            paint.setAlpha((int) (255 * alpha));

            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(p.rotation);
            float s = p.size / 24f;
            canvas.scale(s, s);
            if (p.paw) {
                canvas.drawPath(pawPath, paint);
            } else {
                canvas.drawPath(heartPath, paint);
            }
            canvas.restore();
        }

        // 中央文案：等级 + 称号
        drawCenteredText(canvas, titleLine,
                h * 0.32f, 30 * getResources().getDisplayMetrics().density,
                0xFFD13F47, true);
        drawCenteredText(canvas, subLine,
                h * 0.32f + 38 * getResources().getDisplayMetrics().density,
                15 * getResources().getDisplayMetrics().density,
                0xFFC97A3D, false);
    }

    private void drawCenteredText(Canvas canvas, String text, float y, float textSizePx,
                                  int color, boolean bold) {
        if (text == null || text.isEmpty()) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(textSizePx);
        paint.setFakeBoldText(bold);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, getWidth() / 2f, y, paint);
    }

    /** 爱心路径（24x24 视口） */
    private void buildHeartPath() {
        heartPath.reset();
        heartPath.moveTo(12, 21.35f);
        heartPath.cubicTo(11.08f, 20.53f, 9.9f, 19.66f, 8.6f, 18.7f);
        heartPath.cubicTo(5.4f, 16.28f, 2f, 12.9f, 2f, 8.5f);
        heartPath.cubicTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f);
        heartPath.cubicTo(9.24f, 3f, 10.91f, 3.81f, 12f, 5.09f);
        heartPath.cubicTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f);
        heartPath.cubicTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f);
        heartPath.cubicTo(22f, 12.9f, 18.6f, 16.28f, 15.4f, 18.7f);
        heartPath.cubicTo(14.1f, 19.66f, 12.92f, 20.53f, 12f, 21.35f);
        heartPath.close();
    }

    /** 爪印路径（24x24 视口，与 ic_paw 同构） */
    private void buildPawPath() {
        pawPath.reset();
        pawPath.moveTo(4, 10);
        pawPath.addCircle(6.5f, 10f, 2.5f, Path.Direction.CW);
        pawPath.addCircle(12f, 7.5f, 3f, Path.Direction.CW);
        pawPath.addCircle(17.5f, 10f, 2.5f, Path.Direction.CW);
        pawPath.addCircle(8.5f, 15.5f, 2.2f, Path.Direction.CW);
        pawPath.moveTo(8.9f, 16.5f);
        pawPath.addOval(8.9f, 12.3f, 18.1f, 20.7f, Path.Direction.CW);
    }
}