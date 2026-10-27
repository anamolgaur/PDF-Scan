package com.anamol.pdfscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.FrameLayout;

/** Live page outline over the camera preview. Fills in as the page holds steady. */
final class CameraOverlay extends View {
    private float[] quad;
    private float progress;
    private float flash;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint white = new Paint();
    private final Path path = new Path();

    CameraOverlay(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(Ui.dp(c, 3));
        line.setColor(Ui.CREAM);
        fill.setColor(Ui.CREAM);
        dot.setColor(Ui.CREAM);
        white.setColor(0xFFFFFFFF);
    }

    /** quad: normalised display corners (TL, TR, BR, BL) or null; progress 0..1 towards auto-capture. */
    void setQuad(float[] quad, float progress) {
        this.quad = quad;
        this.progress = progress;
        invalidate();
    }

    private float focusX, focusY;
    private long focusAt;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Shows a focus ring where the user tapped (view pixels). */
    void showFocus(float x, float y) {
        focusX = x;
        focusY = y;
        focusAt = android.os.SystemClock.uptimeMillis();
        invalidate();
    }

    void flash() {
        flash = 1f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (quad != null) {
            path.reset();
            path.moveTo(quad[0] * w, quad[1] * h);
            for (int i = 1; i < 4; i++) path.lineTo(quad[2 * i] * w, quad[2 * i + 1] * h);
            path.close();
            fill.setAlpha(Math.round(25 + 70 * progress));
            c.drawPath(path, fill);
            c.drawPath(path, line);
            float r = Ui.dp(getContext(), 5);
            for (int i = 0; i < 4; i++) c.drawCircle(quad[2 * i] * w, quad[2 * i + 1] * h, r, dot);
        }
        long age = android.os.SystemClock.uptimeMillis() - focusAt;
        if (focusAt > 0 && age < 1400) {
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(getContext(), 1.5f));
            ring.setColor(Ui.CREAM);
            ring.setAlpha(age < 1000 ? 255 : Math.round(255 * (1400 - age) / 400f));
            float r = Ui.dp(getContext(), 36) * (age < 180 ? 1.3f - 0.3f * age / 180f : 1f);
            c.drawCircle(focusX, focusY, r, ring);
            postInvalidateOnAnimation();
        }
        if (flash > 0f) {
            white.setAlpha(Math.round(200 * flash));
            c.drawRect(0, 0, w, h, white);
            flash -= 0.15f;
            postInvalidateOnAnimation();
        }
    }

    /** Keeps its child at a fixed width:height ratio, as large as fits, centred. */
    static final class AspectFrame extends FrameLayout {
        private float ratio = 3f / 4f;  // width / height

        AspectFrame(Context c) {
            super(c);
        }

        void setRatio(float widthOverHeight) {
            ratio = widthOverHeight;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int aw = MeasureSpec.getSize(widthSpec);
            int ah = MeasureSpec.getSize(heightSpec);
            int w = aw;
            int h = Math.round(aw / ratio);
            if (h > ah && ah > 0) {
                h = ah;
                w = Math.round(ah * ratio);
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }
}
