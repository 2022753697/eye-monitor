package com.eyemonitor.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 极简缩放图片（微信式预览用）：双指缩放（1x-5x）、双击放大/还原、缩放后单指拖动。
 * 初始居中适应视图（fitCenter 等价）；reset() 还原。
 */
public class ZoomImageView extends AppCompatImageView {

    private static final float MAX_SCALE = 5f;

    /** 未缩放时水平滑动回调（预览翻页用） */
    public interface SwipeListener {
        void onSwipe(boolean next);
    }

    private SwipeListener swipeListener;
    private float swipeDx;
    private float swipeDy;

    public void setSwipeListener(SwipeListener listener) {
        this.swipeListener = listener;
    }

    private final Matrix matrix = new Matrix();
    private float scale = 1f;              // 相对中心适应态的相对缩放
    private float baseScale = 1f;          // 中心适应时的原始缩放
    private final PointF down = new PointF();
    private boolean dragging = false;

    private final ScaleGestureDetector scaleDetector = new ScaleGestureDetector(getContext(),
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scale = Math.max(1f, Math.min(MAX_SCALE, scale * detector.getScaleFactor()));
                    matrix.postScale(detector.getScaleFactor(), detector.getScaleFactor(),
                            detector.getFocusX(), detector.getFocusY());
                    clamp();
                    apply();
                    return true;
                }
            });

    private final GestureDetector tapDetector = new GestureDetector(getContext(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (scale > 1.01f) {
                        resetZoom();
                    } else {
                        scale = 2.5f;
                        matrix.setScale(baseScale * scale, baseScale * scale, e.getX(), e.getY());
                        clamp();
                        apply();
                    }
                    return true;
                }
            });

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
    }

    /** 中心适应当前图像（获取尺寸后调用；Glide 加载完成后 post 调用） */
    public void centerFit() {
        android.graphics.drawable.Drawable d = getDrawable();
        int vw = getWidth();
        int vh = getHeight();
        if (d == null || vw == 0 || vh == 0) {
            return;
        }
        float dw = d.getIntrinsicWidth();
        float dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) {
            matrix.reset();
            apply();
            return;
        }
        baseScale = Math.min(1f, Math.min(vw / dw, vh / dh));
        resetZoom();
    }

    public void resetZoom() {
        scale = 1f;
        if (getDrawable() != null) {
            float dw = getDrawable().getIntrinsicWidth();
            float dh = getDrawable().getIntrinsicHeight();
            matrix.reset();
            matrix.postScale(baseScale, baseScale);
            matrix.postTranslate((getWidth() - dw * baseScale) / 2f,
                    (getHeight() - dh * baseScale) / 2f);
        } else {
            matrix.reset();
        }
        apply();
    }

    /** 重置为初始（预览关闭时调用） */
    public void reset() {
        scale = 1f;
        baseScale = 1f;
        matrix.reset();
        apply();
    }

    /** 平移钳制：缩放后图片边缘不脱离视图太远（粗略版） */
    private void clamp() {
        if (scale <= 1f) {
            return;
        }
        float[] v = new float[9];
        matrix.getValues(v);
        float sx = v[Matrix.MSCALE_X];
        float tx = v[Matrix.MTRANS_X];
        float ty = v[Matrix.MTRANS_Y];
        float dw = getWidth() * (sx / baseScale); // 当前绘制宽度（约）
        float dh = getHeight() * (sx / baseScale);
        float maxTx = Math.max(0, (dw - getWidth()) / 2f);
        float maxTy = Math.max(0, (dh - getHeight()) / 2f);
        v[Matrix.MTRANS_X] = Math.max(-maxTx, Math.min(maxTx, tx));
        v[Matrix.MTRANS_Y] = Math.max(-maxTy, Math.min(maxTy, ty));
        matrix.setValues(v);
    }

    private void apply() {
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down.set(event.getX(), event.getY());
                swipeDx = 0;
                swipeDy = 0;
                dragging = scale > 1.01f && event.getPointerCount() == 1;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scale > 1.01f && event.getPointerCount() == 1 && dragging) {
                    matrix.postTranslate(event.getX() - down.x, event.getY() - down.y);
                    clamp();
                    apply();
                    down.set(event.getX(), event.getY());
                } else if (scale <= 1.01f && event.getPointerCount() == 1) {
                    // 未缩放：累计水平位移，松手时判定左右翻页
                    swipeDx += event.getX() - down.x;
                    swipeDy += event.getY() - down.y;
                    down.set(event.getX(), event.getY());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (scale <= 1.01f && event.getPointerCount() == 1
                        && Math.abs(swipeDx) > 60 && Math.abs(swipeDx) > Math.abs(swipeDy) * 2
                        && swipeListener != null) {
                    swipeListener.onSwipe(swipeDx < 0); // 左滑=下一张
                }
                dragging = false;
                return true;
            default:
                return true;
        }
    }
}