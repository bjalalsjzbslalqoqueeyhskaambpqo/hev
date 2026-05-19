package com.blacktunnel;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayDeque;
import java.util.Deque;

public class PingPulseView extends View {

    private static final int   MAX_POINTS     = 30;
    private static final long  SCROLL_DURATION = 2000L;
    private static final float IDLE_AMPLITUDE  = 4f;

    private final Deque<Float> pingHistory = new ArrayDeque<>();

    private final Paint linePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  linePath   = new Path();

    private int   currentPingColor = Color.parseColor("#444444");
    private float scrollOffset     = 0f;
    private float blinkAlpha       = 1f;
    private boolean isActive       = false;

    private ValueAnimator scrollAnimator;
    private ValueAnimator blinkAnimator;

    public PingPulseView(Context context) {
        super(context);
        init();
    }

    public PingPulseView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PingPulseView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(6f);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);

        dotPaint.setStyle(Paint.Style.FILL);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(0.7f);
        gridPaint.setColor(Color.argb(20, 255, 255, 255));

        for (int i = 0; i < MAX_POINTS; i++) pingHistory.addLast(0f);

        startScrollAnimation();
        startBlinkAnimation();
    }

    private void startScrollAnimation() {
        if (scrollAnimator != null) scrollAnimator.cancel();
        scrollAnimator = ValueAnimator.ofFloat(0f, 1f);
        scrollAnimator.setDuration(SCROLL_DURATION);
        scrollAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scrollAnimator.setInterpolator(new LinearInterpolator());
        scrollAnimator.addUpdateListener(a -> {
            scrollOffset = (float) a.getAnimatedValue();
            invalidate();
        });
        scrollAnimator.start();
    }

    private void startBlinkAnimation() {
        if (blinkAnimator != null) blinkAnimator.cancel();
        blinkAnimator = ValueAnimator.ofFloat(1f, 0.2f, 1f);
        blinkAnimator.setDuration(1200);
        blinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        blinkAnimator.setInterpolator(new LinearInterpolator());
        blinkAnimator.addUpdateListener(a -> {
            blinkAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        blinkAnimator.start();
    }

    public void pushPing(int pingMs) {
        if (pingHistory.size() >= MAX_POINTS) pingHistory.pollFirst();
        pingHistory.addLast((float) pingMs);
        isActive = pingMs > 0;
        invalidate();
    }

    public void setLineColor(int color) {
        currentPingColor = color;
        invalidate();
    }

    public void setIdle() {
        isActive = false;
        pingHistory.clear();
        for (int i = 0; i < MAX_POINTS; i++) pingHistory.addLast(0f);
        currentPingColor = Color.parseColor("#444444");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        drawGrid(canvas, w, h);

        float[] points = pingHistory.stream()
            .map(Float::floatValue).collect(java.util.stream.Collectors.toList())
            .stream().mapToDouble(Float::doubleValue).collect(
                () -> new float[MAX_POINTS],
                (arr, val) -> {},
                (a, b) -> {}
            );

        Float[] arr = pingHistory.toArray(new Float[0]);
        float maxVal = 1f;
        for (Float v : arr) if (v > maxVal) maxVal = v;
        maxVal = Math.max(maxVal, 200f);

        float stepX = (float) w / (MAX_POINTS - 1);
        float shiftX = scrollOffset * stepX;

        linePath.reset();
        boolean first = true;
        int totalPadV = (int)(h * 0.15f);
        float drawH = h - totalPadV * 2f;

        for (int i = 0; i < arr.length; i++) {
            float rawVal = arr[i];
            float normalised;
            if (!isActive || rawVal <= 0f) {
                float idlePhase = (float)(i + scrollOffset * MAX_POINTS) / MAX_POINTS;
                normalised = 0.5f + (float)(Math.sin(idlePhase * Math.PI * 2) * IDLE_AMPLITUDE / drawH);
            } else {
                normalised = 1f - (rawVal / maxVal);
                normalised = Math.max(0.05f, Math.min(0.95f, normalised));
            }
            float x = i * stepX - shiftX + stepX;
            float y = totalPadV + normalised * drawH;
            if (first) { linePath.moveTo(x, y); first = false; }
            else        linePath.lineTo(x, y);
        }

        int glowColor = Color.argb(
            (int)(80 * (isActive ? blinkAlpha : 0.3f)),
            Color.red(currentPingColor),
            Color.green(currentPingColor),
            Color.blue(currentPingColor));

        glowPaint.setColor(glowColor);
        canvas.drawPath(linePath, glowPaint);

        int lineAlpha = isActive ? (int)(230 * blinkAlpha) : 60;
        linePaint.setColor(Color.argb(lineAlpha,
            Color.red(currentPingColor),
            Color.green(currentPingColor),
            Color.blue(currentPingColor)));

        LinearGradient grad = new LinearGradient(0, 0, w, 0,
            Color.argb(lineAlpha / 3, Color.red(currentPingColor), Color.green(currentPingColor), Color.blue(currentPingColor)),
            Color.argb(lineAlpha, Color.red(currentPingColor), Color.green(currentPingColor), Color.blue(currentPingColor)),
            Shader.TileMode.CLAMP);
        linePaint.setShader(grad);
        canvas.drawPath(linePath, linePaint);
        linePaint.setShader(null);

        if (isActive && arr.length > 0) {
            Float lastVal = arr[arr.length - 1];
            float normalised;
            if (lastVal <= 0f) {
                normalised = 0.5f;
            } else {
                normalised = 1f - (lastVal / maxVal);
                normalised = Math.max(0.05f, Math.min(0.95f, normalised));
            }
            float dotX = w - shiftX + stepX * 0.5f;
            float dotY = totalPadV + normalised * drawH;
            dotX = Math.min(dotX, w - 4f);

            dotPaint.setColor(Color.argb((int)(255 * blinkAlpha),
                Color.red(currentPingColor),
                Color.green(currentPingColor),
                Color.blue(currentPingColor)));
            dotPaint.setShadowLayer(8f, 0f, 0f, currentPingColor);
            canvas.drawCircle(dotX, dotY, 3.5f, dotPaint);
            dotPaint.setShadowLayer(0f, 0f, 0f, 0);
        }
    }

    private void drawGrid(Canvas canvas, int w, int h) {
        int rows = 3;
        for (int i = 1; i < rows; i++) {
            float y = (float) h / rows * i;
            canvas.drawLine(0, y, w, y, gridPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (scrollAnimator != null) scrollAnimator.cancel();
        if (blinkAnimator  != null) blinkAnimator.cancel();
    }
}
