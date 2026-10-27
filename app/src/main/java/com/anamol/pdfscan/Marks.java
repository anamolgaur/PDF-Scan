package com.anamol.pdfscan;

import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.nio.ByteBuffer;
import java.util.List;

/** Draws mark-up and eraser strokes. Coordinates are normalised to a w×h page. */
final class Marks {
    static final int HIGHLIGHT_YELLOW = 0xFFF2D04A;

    private Marks() {
    }

    private static Paint strokePaint(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        p.setStrokeWidth(Math.max(1f, width));
        return p;
    }

    private static Path polyline(float[] pts, float w, float h) {
        Path path = new Path();
        if (pts.length < 2) return path;
        path.moveTo(pts[0] * w, pts[1] * h);
        if (pts.length == 2) path.lineTo(pts[0] * w + 0.1f, pts[1] * h);
        for (int i = 2; i + 1 < pts.length; i += 2) path.lineTo(pts[i] * w, pts[i + 1] * h);
        return path;
    }

    static void draw(Canvas c, List<PageMeta.Mark> marks, float w, float h) {
        for (PageMeta.Mark m : marks) draw(c, m, w, h);
    }

    static void draw(Canvas c, PageMeta.Mark m, float w, float h) {
        float sw = m.width * w;
        switch (m.type) {
            case PageMeta.Mark.HIGHLIGHT: {
                Paint p = strokePaint(m.color, sw);
                p.setStrokeCap(Paint.Cap.SQUARE);
                p.setBlendMode(BlendMode.MULTIPLY);
                p.setAlpha(150);
                c.drawPath(polyline(m.pts, w, h), p);
                break;
            }
            case PageMeta.Mark.RECT:
            case PageMeta.Mark.OVAL: {
                if (m.pts.length < 4) break;
                RectF r = new RectF(Math.min(m.pts[0], m.pts[2]) * w, Math.min(m.pts[1], m.pts[3]) * h,
                        Math.max(m.pts[0], m.pts[2]) * w, Math.max(m.pts[1], m.pts[3]) * h);
                Paint p = strokePaint(m.color, sw);
                if (PageMeta.Mark.RECT.equals(m.type)) c.drawRect(r, p);
                else c.drawOval(r, p);
                break;
            }
            case PageMeta.Mark.ARROW: {
                if (m.pts.length < 4) break;
                float x0 = m.pts[0] * w, y0 = m.pts[1] * h, x1 = m.pts[2] * w, y1 = m.pts[3] * h;
                Paint p = strokePaint(m.color, sw);
                c.drawLine(x0, y0, x1, y1, p);
                double a = Math.atan2(y1 - y0, x1 - x0);
                float head = Math.max(sw * 4f, w * 0.03f);
                Path tip = new Path();
                tip.moveTo((float) (x1 - head * Math.cos(a - 0.45)), (float) (y1 - head * Math.sin(a - 0.45)));
                tip.lineTo(x1, y1);
                tip.lineTo((float) (x1 - head * Math.cos(a + 0.45)), (float) (y1 - head * Math.sin(a + 0.45)));
                c.drawPath(tip, p);
                break;
            }
            case PageMeta.Mark.SIGNATURE: {
                RectF box = signatureBox(m, w, h);
                Paint p = strokePaint(m.color, Math.max(1.5f, box.width() * 0.012f));
                for (float[] s : m.sig) {
                    Path path = new Path();
                    if (s.length < 2) continue;
                    path.moveTo(box.left + s[0] * box.width(), box.top + s[1] * box.width());
                    if (s.length == 2) path.lineTo(box.left + s[0] * box.width() + 0.1f, box.top + s[1] * box.width());
                    for (int i = 2; i + 1 < s.length; i += 2) {
                        path.lineTo(box.left + s[i] * box.width(), box.top + s[i + 1] * box.width());
                    }
                    c.drawPath(path, p);
                }
                break;
            }
            default:
                c.drawPath(polyline(m.pts, w, h), strokePaint(m.color, sw));
                break;
        }
    }

    /** Where a placed signature sits on a w×h page. pts = x, y (top-left) and width, all normalised. */
    static RectF signatureBox(PageMeta.Mark m, float w, float h) {
        float left = m.pts[0] * w;
        float top = m.pts[1] * h;
        float bw = m.pts[2] * w;
        return new RectF(left, top, left + bw, top + bw * m.sigAspect);
    }

    /** Rasterises eraser strokes into a mask the size of the page image. */
    static boolean[] mask(List<PageMeta.Stroke> strokes, int w, int h) {
        Bitmap a = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(a);
        for (PageMeta.Stroke s : strokes) {
            c.drawPath(polyline(s.pts, w, h), strokePaint(Color.BLACK, s.width * w));
        }
        ByteBuffer buf = ByteBuffer.allocate(a.getRowBytes() * h);
        a.copyPixelsToBuffer(buf);
        a.recycle();
        byte[] raw = buf.array();
        int stride = raw.length / h;
        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) mask[y * w + x] = raw[y * stride + x] != 0;
        }
        return mask;
    }
}
