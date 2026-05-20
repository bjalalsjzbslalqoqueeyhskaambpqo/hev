package com.blacktunnel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

public final class HudDrawables {

    private HudDrawables() {}

    public static Drawable cornerTL(int color) { return new CornerDrawable(color, 0); }
    public static Drawable cornerTR(int color) { return new CornerDrawable(color, 1); }
    public static Drawable cornerBL(int color) { return new CornerDrawable(color, 2); }
    public static Drawable cornerBR(int color) { return new CornerDrawable(color, 3); }

    public static Drawable ringOuter(int color) { return new RingDrawable(color, 6f, true,  20f); }
    public static Drawable ringMid  (int color) { return new RingDrawable(color, 2f, true,  12f); }
    public static Drawable ringInner(int color) { return new RingDrawable(color, 3f, false,  0f); }
    public static Drawable glowRing (int color) { return new GlowRingDrawable(color); }

    public static Drawable btnRingOuter(int color) { return new RingDrawable(color, 4f, true, 18f); }
    public static Drawable btnRingMid  (int color) { return new RingDrawable(color, 2f, true, 10f); }
    public static Drawable btnConnect  (int color, boolean connected) {
        return new BtnCircleDrawable(color, connected);
    }

    public static Drawable statusBadge (int color) { return new StatusBadgeDrawable(color); }
    public static Drawable panelMetrics(int color) { return new PanelDrawable(color, true);  }
    public static Drawable panelData   (int color) { return new PanelDrawable(color, false); }
    public static Drawable panelId     (int color) { return new PanelDrawable(color, false); }
    public static Drawable crossLine   (int color) { return new CrossLineDrawable(color); }

    static final class CornerDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int   variant;
        CornerDrawable(int color, int variant) {
            this.variant = variant;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setStrokeCap(Paint.Cap.SQUARE);
        }
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            float arm = w * 0.55f;
            Path path = new Path();
            switch (variant) {
                case 0: path.moveTo(0,arm); path.lineTo(0,0); path.lineTo(arm,0); break;
                case 1: path.moveTo(w-arm,0); path.lineTo(w,0); path.lineTo(w,arm); break;
                case 2: path.moveTo(0,h-arm); path.lineTo(0,h); path.lineTo(arm,h); break;
                case 3: path.moveTo(w-arm,h); path.lineTo(w,h); path.lineTo(w,h-arm); break;
            }
            c.drawPath(path, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class RingDrawable extends Drawable {
        private final Paint  p    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean dash;
        private final float  dashLen;
        RingDrawable(int color, float strokeW, boolean dash, float dashLen) {
            this.dash    = dash;
            this.dashLen = dashLen;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeW);
            if (dash && dashLen > 0) p.setPathEffect(new DashPathEffect(new float[]{dashLen, dashLen * 0.5f}, 0));
        }
        @Override public void draw(Canvas c) {
            RectF r = new RectF(p.getStrokeWidth(), p.getStrokeWidth(),
                getBounds().width()  - p.getStrokeWidth(),
                getBounds().height() - p.getStrokeWidth());
            c.drawOval(r, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class GlowRingDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int baseColor;
        GlowRingDrawable(int color) {
            baseColor = color;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
        }
        @Override public void draw(Canvas c) {
            float cx = getBounds().width() / 2f, cy = getBounds().height() / 2f;
            float r  = Math.min(cx, cy) - 8f;
            p.setShader(new RadialGradient(cx, cy, r,
                new int[]{ Color.argb(0, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                           Color.argb(120, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                           Color.argb(0, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)) },
                new float[]{ 0.7f, 0.9f, 1f }, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class BtnCircleDrawable extends Drawable {
        private final Paint fillP  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int   color;
        private final boolean connected;
        BtnCircleDrawable(int color, boolean connected) {
            this.color     = color;
            this.connected = connected;
            fillP.setStyle(Paint.Style.FILL);
            strokeP.setStyle(Paint.Style.STROKE);
            strokeP.setStrokeWidth(2f);
        }
        @Override public void draw(Canvas c) {
            float cx = getBounds().width() / 2f, cy = getBounds().height() / 2f;
            float r  = Math.min(cx, cy) - 3f;
            fillP.setColor(Color.argb(200, 8, 12, 18));
            c.drawCircle(cx, cy, r, fillP);
            strokeP.setColor(color);
            c.drawCircle(cx, cy, r, strokeP);
            if (connected) {
                Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
                glow.setStyle(Paint.Style.STROKE);
                glow.setStrokeWidth(8f);
                glow.setColor(Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)));
                c.drawCircle(cx, cy, r - 2f, glow);
            }
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class StatusBadgeDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        StatusBadgeDrawable(int color) {
            this.color = color;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
        }
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            p.setColor(color);
            RectF r = new RectF(1, 1, w-1, h-1);
            c.drawRoundRect(r, h/2f, h/2f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)));
            c.drawRoundRect(r, h/2f, h/2f, p);
            p.setStyle(Paint.Style.STROKE);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class PanelDrawable extends Drawable {
        private final Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillP   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cornerP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int   color;
        private final boolean metric;
        PanelDrawable(int color, boolean metric) {
            this.color  = color;
            this.metric = metric;
            strokeP.setStyle(Paint.Style.STROKE);
            strokeP.setStrokeWidth(1.2f);
            fillP.setStyle(Paint.Style.FILL);
            cornerP.setStyle(Paint.Style.STROKE);
            cornerP.setStrokeWidth(2f);
            cornerP.setStrokeCap(Paint.Cap.SQUARE);
        }
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            float radius = 10f;
            RectF r = new RectF(1, 1, w-1, h-1);

            fillP.setColor(Color.argb(metric ? 25 : 18, Color.red(color), Color.green(color), Color.blue(color)));
            c.drawRoundRect(r, radius, radius, fillP);

            strokeP.setColor(Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)));
            c.drawRoundRect(r, radius, radius, strokeP);

            float arm = 18f;
            cornerP.setColor(color);
            c.drawLine(2, arm, 2, 2, cornerP);        c.drawLine(2, 2, arm, 2, cornerP);
            c.drawLine(w-arm, 2, w-2, 2, cornerP);    c.drawLine(w-2, 2, w-2, arm, cornerP);
            c.drawLine(2, h-arm, 2, h-2, cornerP);    c.drawLine(2, h-2, arm, h-2, cornerP);
            c.drawLine(w-arm, h-2, w-2, h-2, cornerP); c.drawLine(w-2, h-2, w-2, h-arm, cornerP);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class CrossLineDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        CrossLineDrawable(int color) {
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
        }
        @Override public void draw(Canvas c) {
            c.drawRect(getBounds(), p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
