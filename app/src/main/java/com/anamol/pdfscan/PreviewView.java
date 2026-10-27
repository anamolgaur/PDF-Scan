package com.anamol.pdfscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the page preview and handles drawing on it: eraser strokes (Clean up) and
 * mark-up (pen, highlighter, shapes, arrows, signatures).
 */
final class PreviewView extends View {
    static final int MODE_VIEW = 0;
    static final int MODE_ERASE = 1;
    static final int MODE_MARK = 2;

    interface Listener {
        void onEraseAdded();

        void onMarksChanged();
    }

    private Bitmap bitmap;
    private final RectF rect = new RectF();
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint erasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float inset;
    private final ScaleGestureDetector scaler;

    int mode = MODE_VIEW;
    String tool = PageMeta.Mark.PEN;
    int color = 0xFF111111;
    float penWidth = 0.004f;      // fraction of page width
    float brush = 0.04f;          // eraser width, fraction of page width
    List<PageMeta.Stroke> erase = new ArrayList<>();
    List<PageMeta.Mark> marks = new ArrayList<>();
    Signatures.Sig pendingSignature;
    Listener listener;

    private final List<Float> current = new ArrayList<>();
    private PageMeta.Mark drawing;
    private int selected = -1;     // index of the selected signature in marks
    private boolean dragging;
    private float dragDx;
    private float dragDy;

    PreviewView(Context c) {
        super(c);
        inset = Ui.dp(c, 8);
        erasePaint.setStyle(Paint.Style.STROKE);
        erasePaint.setStrokeCap(Paint.Cap.ROUND);
        erasePaint.setStrokeJoin(Paint.Join.ROUND);
        erasePaint.setColor(0x99F4EBDD);
        selectPaint.setStyle(Paint.Style.STROKE);
        selectPaint.setStrokeWidth(Ui.dp(c, 1));
        selectPaint.setColor(Ui.MAROON);
        selectPaint.setPathEffect(new DashPathEffect(new float[]{Ui.dp(c, 4), Ui.dp(c, 3)}, 0));
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (selected < 0 || selected >= marks.size()) return false;
                PageMeta.Mark m = marks.get(selected);
                float cx = m.pts[0] + m.pts[2] / 2f;
                float cyPx = m.pts[1] * rect.height() + m.pts[2] * rect.width() * m.sigAspect / 2f;
                float nw = ScanMath.clampf(m.pts[2] * d.getScaleFactor(), 0.08f, 0.95f);
                m.pts[2] = nw;
                m.pts[0] = cx - nw / 2f;
                m.pts[1] = (cyPx - nw * rect.width() * m.sigAspect / 2f) / rect.height();
                invalidate();
                return true;
            }
        });
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        invalidate();
    }

    void clearSelection() {
        selected = -1;
        invalidate();
    }

    private void layoutRect() {
        if (bitmap == null) {
            rect.setEmpty();
            return;
        }
        float aw = getWidth() - 2 * inset;
        float ah = getHeight() - 2 * inset;
        float s = Math.min(aw / bitmap.getWidth(), ah / bitmap.getHeight());
        float w = bitmap.getWidth() * s;
        float h = bitmap.getHeight() * s;
        float l = (getWidth() - w) / 2f;
        float t = (getHeight() - h) / 2f;
        rect.set(l, t, l + w, t + h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        layoutRect();
        if (bitmap == null || rect.isEmpty()) return;
        canvas.drawBitmap(bitmap, null, rect, imagePaint);
        canvas.save();
        canvas.clipRect(rect);
        canvas.translate(rect.left, rect.top);
        float w = rect.width();
        float h = rect.height();
        Marks.draw(canvas, marks, w, h);
        if (drawing != null) Marks.draw(canvas, drawing, w, h);
        if (mode == MODE_ERASE && current.size() >= 2) {
            erasePaint.setStrokeWidth(brush * w);
            Path p = new Path();
            p.moveTo(current.get(0) * w, current.get(1) * h);
            for (int i = 2; i + 1 < current.size(); i += 2) p.lineTo(current.get(i) * w, current.get(i + 1) * h);
            canvas.drawPath(p, erasePaint);
        }
        if (mode == MODE_MARK && selected >= 0 && selected < marks.size()) {
            RectF box = Marks.signatureBox(marks.get(selected), w, h);
            box.inset(-Ui.dp(getContext(), 4), -Ui.dp(getContext(), 4));
            canvas.drawRect(box, selectPaint);
        }
        canvas.restore();
    }

    private float nx(float x) {
        return ScanMath.clampf((x - rect.left) / rect.width(), 0f, 1f);
    }

    private float ny(float y) {
        return ScanMath.clampf((y - rect.top) / rect.height(), 0f, 1f);
    }

    private static float[] toArray(List<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private int signatureAt(float x, float y) {
        for (int i = marks.size() - 1; i >= 0; i--) {
            PageMeta.Mark m = marks.get(i);
            if (!PageMeta.Mark.SIGNATURE.equals(m.type)) continue;
            RectF box = Marks.signatureBox(m, rect.width(), rect.height());
            box.offset(rect.left, rect.top);
            box.inset(-Ui.dp(getContext(), 12), -Ui.dp(getContext(), 12));
            if (box.contains(x, y)) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mode == MODE_VIEW || rect.isEmpty()) return false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        float x = e.getX();
        float y = e.getY();
        if (mode == MODE_MARK && PageMeta.Mark.SIGNATURE.equals(tool)) return signatureTouch(e, x, y);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                current.clear();
                current.add(nx(x));
                current.add(ny(y));
                if (mode == MODE_MARK) {
                    drawing = new PageMeta.Mark();
                    drawing.type = tool;
                    boolean highlight = PageMeta.Mark.HIGHLIGHT.equals(tool);
                    drawing.color = highlight ? Marks.HIGHLIGHT_YELLOW : color;
                    drawing.width = highlight ? Math.max(0.025f, penWidth * 5f) : penWidth;
                    drawing.pts = new float[]{nx(x), ny(y), nx(x), ny(y)};
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == MODE_ERASE || PageMeta.Mark.PEN.equals(tool) || PageMeta.Mark.HIGHLIGHT.equals(tool)) {
                    for (int i = 0; i < e.getHistorySize(); i++) {
                        current.add(nx(e.getHistoricalX(i)));
                        current.add(ny(e.getHistoricalY(i)));
                    }
                    current.add(nx(x));
                    current.add(ny(y));
                    if (drawing != null) drawing.pts = toArray(current);
                } else if (drawing != null) {
                    drawing.pts[2] = nx(x);
                    drawing.pts[3] = ny(y);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (mode == MODE_ERASE) {
                    erase.add(new PageMeta.Stroke(brush, toArray(current)));
                    current.clear();
                    if (listener != null) listener.onEraseAdded();
                } else if (drawing != null) {
                    boolean shape = drawing.pts.length == 4 && !PageMeta.Mark.PEN.equals(tool)
                            && !PageMeta.Mark.HIGHLIGHT.equals(tool);
                    boolean tooSmall = shape && Math.abs(drawing.pts[2] - drawing.pts[0]) < 0.01f
                            && Math.abs(drawing.pts[3] - drawing.pts[1]) < 0.01f;
                    if (!tooSmall) marks.add(drawing);
                    drawing = null;
                    current.clear();
                    if (listener != null) listener.onMarksChanged();
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                current.clear();
                drawing = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private boolean signatureTouch(MotionEvent e, float x, float y) {
        scaler.onTouchEvent(e);
        if (scaler.isInProgress()) {
            dragging = false;
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int hit = signatureAt(x, y);
                if (hit >= 0) {
                    selected = hit;
                } else if (pendingSignature != null) {
                    PageMeta.Mark m = new PageMeta.Mark();
                    m.type = PageMeta.Mark.SIGNATURE;
                    m.color = color;
                    m.width = 0f;
                    m.sigAspect = pendingSignature.aspect;
                    for (float[] s : pendingSignature.strokes) m.sig.add(s.clone());
                    float w = 0.35f;
                    float hNorm = w * rect.width() * m.sigAspect / rect.height();
                    m.pts = new float[]{ScanMath.clampf(nx(x) - w / 2f, 0f, 1f - w),
                            ScanMath.clampf(ny(y) - hNorm / 2f, 0f, Math.max(0f, 1f - hNorm)), w};
                    marks.add(m);
                    selected = marks.size() - 1;
                    pendingSignature = null;
                } else {
                    selected = -1;
                    invalidate();
                    return true;
                }
                PageMeta.Mark m = marks.get(selected);
                dragDx = nx(x) - m.pts[0];
                dragDy = ny(y) - m.pts[1];
                dragging = true;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging && e.getPointerCount() == 1 && selected >= 0 && selected < marks.size()) {
                    PageMeta.Mark m = marks.get(selected);
                    m.pts[0] = nx(x) - dragDx;
                    m.pts[1] = ny(y) - dragDy;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (listener != null) listener.onMarksChanged();
                return true;
            default:
                return true;
        }
    }
}
