package com.anamol.pdfscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Shows an image with four draggable corner handles marking the page edges. */
final class CropView extends View {
    private Bitmap bitmap;
    private final float[] corners = ScanMath.FULL.clone();
    private final RectF frame = new RectF();
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float handleRadius;
    private final float touchRadius;
    private final float inset;
    private int active = -1;

    CropView(Context c) {
        super(c);
        dimPaint.setColor(0x99000000);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Ui.dp(c, 2));
        linePaint.setColor(Ui.CREAM);
        handleFill.setColor(Ui.CREAM);
        handleRing.setStyle(Paint.Style.STROKE);
        handleRing.setStrokeWidth(Ui.dp(c, 3));
        handleRing.setColor(Ui.MAROON);
        handleRadius = Ui.dp(c, 11);
        touchRadius = Ui.dp(c, 36);  // 72dp touch target
        inset = Ui.dp(c, 20);
    }

    void setImage(Bitmap b, float[] c) {
        bitmap = b;
        setCorners(c);
    }

    void setCorners(float[] c) {
        System.arraycopy(c == null ? ScanMath.FULL : c, 0, corners, 0, 8);
        invalidate();
    }

    float[] getCorners() {
        return corners.clone();
    }

    private void layoutFrame() {
        float aw = getWidth() - 2 * inset;
        float ah = getHeight() - 2 * inset;
        if (bitmap == null || aw <= 0 || ah <= 0) {
            frame.setEmpty();
            return;
        }
        float s = Math.min(aw / bitmap.getWidth(), ah / bitmap.getHeight());
        float w = bitmap.getWidth() * s;
        float h = bitmap.getHeight() * s;
        float l = (getWidth() - w) / 2f;
        float t = (getHeight() - h) / 2f;
        frame.set(l, t, l + w, t + h);
    }

    private float vx(int i) {
        return frame.left + corners[2 * i] * frame.width();
    }

    private float vy(int i) {
        return frame.top + corners[2 * i + 1] * frame.height();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        layoutFrame();
        if (bitmap == null || frame.isEmpty()) return;
        canvas.drawBitmap(bitmap, null, frame, imagePaint);

        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(frame, Path.Direction.CW);
        path.moveTo(vx(0), vy(0));
        for (int i = 1; i < 4; i++) path.lineTo(vx(i), vy(i));
        path.close();
        canvas.drawPath(path, dimPaint);

        path.reset();
        path.moveTo(vx(0), vy(0));
        for (int i = 1; i < 4; i++) path.lineTo(vx(i), vy(i));
        path.close();
        canvas.drawPath(path, linePaint);

        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(vx(i), vy(i), handleRadius, handleFill);
            canvas.drawCircle(vx(i), vy(i), handleRadius, handleRing);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (frame.isEmpty()) return false;
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                active = -1;
                float best = touchRadius;
                for (int i = 0; i < 4; i++) {
                    float d = (float) Math.hypot(x - vx(i), y - vy(i));
                    if (d <= best) {
                        best = d;
                        active = i;
                    }
                }
                if (active >= 0 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return active >= 0;
            case MotionEvent.ACTION_MOVE:
                if (active < 0) return false;
                corners[2 * active] = ScanMath.clampf((x - frame.left) / frame.width(), 0f, 1f);
                corners[2 * active + 1] = ScanMath.clampf((y - frame.top) / frame.height(), 0f, 1f);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (active >= 0) performClick();
                active = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                active = -1;
                return true;
            default:
                return active >= 0;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
