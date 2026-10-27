package com.anamol.pdfscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** A cream card to sign on with a finger. Keeps strokes as raw pixel coordinates. */
final class SignaturePad extends View {
    private final List<float[]> strokes = new ArrayList<>();
    private final List<Float> current = new ArrayList<>();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint();

    SignaturePad(Context c) {
        super(c);
        setBackground(Ui.box(c, Ui.CREAM, Ui.LINE, 8));
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setStrokeWidth(Ui.dp(c, 3));
        ink.setColor(0xFF1A1A1A);
        guide.setColor(0x55000000);
        guide.setStrokeWidth(Ui.dp(c, 1));
    }

    List<float[]> strokes() {
        return strokes;
    }

    void clear() {
        strokes.clear();
        current.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float base = getHeight() * 0.72f;
        c.drawLine(getWidth() * 0.08f, base, getWidth() * 0.92f, base, guide);
        for (float[] s : strokes) c.drawPath(path(s), ink);
        if (current.size() >= 2) {
            float[] a = new float[current.size()];
            for (int i = 0; i < a.length; i++) a[i] = current.get(i);
            c.drawPath(path(a), ink);
        }
    }

    private static Path path(float[] s) {
        Path p = new Path();
        p.moveTo(s[0], s[1]);
        if (s.length == 2) p.lineTo(s[0] + 0.1f, s[1]);
        for (int i = 2; i + 1 < s.length; i += 2) p.lineTo(s[i], s[i + 1]);
        return p;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                current.clear();
                current.add(e.getX());
                current.add(e.getY());
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getHistorySize(); i++) {
                    current.add(e.getHistoricalX(i));
                    current.add(e.getHistoricalY(i));
                }
                current.add(e.getX());
                current.add(e.getY());
                break;
            case MotionEvent.ACTION_UP:
                float[] a = new float[current.size()];
                for (int i = 0; i < a.length; i++) a[i] = current.get(i);
                if (a.length >= 2) strokes.add(a);
                current.clear();
                performClick();
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
