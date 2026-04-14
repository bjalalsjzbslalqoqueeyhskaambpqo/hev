package com.blacktunnel;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class GamingBorderView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float offset = 0f;
    private ValueAnimator animator;

    public GamingBorderView(Context context) { super(context); init(); }
    public GamingBorderView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public GamingBorderView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void ensureAnimator() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            offset = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    public void start() {
        ensureAnimator();
        if (ValueAnimator.areAnimatorsEnabled() && animator != null && !animator.isStarted()) {
            animator.start();
        }
    }

    public void stop() {
        if (animator != null) animator.cancel();
        offset = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float shift = w * offset;
        LinearGradient gradient = new LinearGradient(
                -w + shift, 0, shift, h,
                new int[]{
                        0x00000000,
                        getResources().getColor(R.color.color_gaming, null),
                        getResources().getColor(R.color.color_gaming_light, null),
                        getResources().getColor(R.color.color_gaming, null),
                        0x00000000
                },
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f},
                Shader.TileMode.CLAMP
        );
        paint.setShader(gradient);
        rect.set(dp(1), dp(1), w - dp(1), h - dp(1));
        canvas.drawRoundRect(rect, dp(16), dp(16), paint);
    }
}
