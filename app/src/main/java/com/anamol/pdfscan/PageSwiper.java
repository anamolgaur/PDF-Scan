package com.anamol.pdfscan;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Full-screen page preview: swipe left and right between pages, pinch or double-tap to zoom,
 * drag to look around a zoomed page. Bitmaps come from a {@link Source} that may load them late.
 */
final class PageSwiper extends View {
    interface Source {
        int count();

        /** The page image, or null while it is still loading (call {@link #invalidate()} when ready). */
        Bitmap page(int index);
    }

    interface Listener {
        void onPageChanged(int index);
    }

    private static final float MAX_SCALE = 5f;

    Source source;
    Listener listener;
    private int index;
    private float dragX;
    private float scale = 1f;
    private float tx;
    private float ty;
    private int flingDir;
    private ValueAnimator anim;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loading = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /**
     * The last picture drawn for each nearby page. If the source has to reload a page (its cache
     * dropped it), the old picture stays on screen until the new one is ready instead of flashing
     * an empty page.
     */
    private final android.util.SparseArray<Bitmap> shown = new android.util.SparseArray<>();
    private final int gap;
    private final int pad;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;

    PageSwiper(Context c) {
        super(c);
        gap = Ui.dp(c, 24);
        pad = Ui.dp(c, 12);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Ui.dp(c, 1));
        frame.setColor(Ui.LINE);
        loading.setColor(0xFFFAF8F2);  // paper: a page still loading looks like a blank page, not a dark flash
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (anim != null) anim.cancel();
                flingDir = 0;
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaler.isInProgress()) return true;
                if (zoomed()) {
                    tx -= dx;
                    ty -= dy;
                    clampPan();
                } else {
                    float d = -dx;
                    boolean atEdge = (index == 0 && dragX + d > 0) || (index >= count() - 1 && dragX + d < 0);
                    dragX += atEdge ? d * 0.3f : d;
                }
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (!zoomed() && Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > Ui.dp(getContext(), 500)) {
                    flingDir = vx < 0 ? 1 : -1;
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (zoomed()) {
                    animateZoom(1f, 0, 0);
                } else {
                    float k = 2.5f;
                    float cx = getWidth() / 2f;
                    float cy = getHeight() / 2f;
                    animateZoom(k, (e.getX() - cx) * (1 - k), (e.getY() - cy) * (1 - k));
                }
                return true;
            }
        });
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float k = d.getScaleFactor();
                float ns = Math.max(1f, Math.min(MAX_SCALE, scale * k));
                k = ns / scale;
                float fx = d.getFocusX() - getWidth() / 2f;
                float fy = d.getFocusY() - getHeight() / 2f;
                tx = fx * (1 - k) + tx * k;
                ty = fy * (1 - k) + ty * k;
                scale = ns;
                dragX = 0;
                clampPan();
                invalidate();
                return true;
            }
        });
    }

    int index() {
        return index;
    }

    /** Pages were added, removed or reordered: drop remembered pictures. */
    void forget() {
        shown.clear();
        invalidate();
    }

    void setIndex(int i) {
        index = Math.max(0, Math.min(Math.max(0, count() - 1), i));
        resetZoom();
        if (listener != null) listener.onPageChanged(index);
    }

    void resetZoom() {
        if (anim != null) anim.cancel();
        scale = 1f;
        tx = 0;
        ty = 0;
        dragX = 0;
        invalidate();
    }

    private int count() {
        return source == null ? 0 : source.count();
    }

    private boolean zoomed() {
        return scale > 1.01f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        int a = e.getActionMasked();
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && !zoomed()) settle();
        if (a == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private void settle() {
        if (scale != 1f) {
            scale = 1f;
            tx = 0;
            ty = 0;
        }
        float w = getWidth() + gap;
        int dir = flingDir;
        if (dir == 0 && Math.abs(dragX) > getWidth() / 4f) dir = dragX < 0 ? 1 : -1;
        int target = index + dir;
        if (target < 0 || target >= count()) dir = 0;
        final int d = dir;
        float end = -d * w;
        animate(dragX, end, v -> dragX = v, () -> {
            if (d != 0) {
                index += d;
                dragX = 0;
                if (listener != null) listener.onPageChanged(index);
            }
            invalidate();
        });
    }

    private interface Setter {
        void set(float v);
    }

    private void animate(float from, float to, final Setter setter, final Runnable end) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(Math.min(260, 80 + (long) (Math.abs(to - from) / Math.max(1, getWidth()) * 260)));
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(va -> {
            setter.set((float) va.getAnimatedValue());
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled && end != null) end.run();
            }
        });
        anim.start();
    }

    private void animateZoom(final float toScale, final float toTx, final float toTy) {
        final float s0 = scale;
        final float x0 = tx;
        final float y0 = ty;
        animate(0f, 1f, v -> {
            scale = s0 + (toScale - s0) * v;
            tx = x0 + (toTx - x0) * v;
            ty = y0 + (toTy - y0) * v;
            clampPan();
        }, null);
    }

    /** The page fitted into the view (before zoom), centred. */
    private boolean fit(Bitmap b, RectF out) {
        if (b == null) {
            float w = getWidth() - 2f * pad;
            float h = Math.min(getHeight() - 2f * pad, w * 1.414f);
            out.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
            return false;
        }
        float aw = getWidth() - 2f * pad;
        float ah = getHeight() - 2f * pad;
        float s = Math.min(aw / b.getWidth(), ah / b.getHeight());
        float w = b.getWidth() * s;
        float h = b.getHeight() * s;
        out.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f, (getHeight() + h) / 2f);
        return true;
    }

    private void clampPan() {
        Bitmap b = source != null && count() > 0 ? source.page(index) : null;
        fit(b, rect);
        float mx = Math.max(0, (rect.width() * scale - getWidth()) / 2f + pad);
        float my = Math.max(0, (rect.height() * scale - getHeight()) / 2f + pad);
        tx = Math.max(-mx, Math.min(mx, tx));
        ty = Math.max(-my, Math.min(my, ty));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = count();
        if (n == 0) return;
        for (int k = shown.size() - 1; k >= 0; k--) {
            if (Math.abs(shown.keyAt(k) - index) > 2) shown.removeAt(k);
        }
        float w = getWidth() + gap;
        for (int i = Math.max(0, index - 1); i <= Math.min(n - 1, index + 1); i++) {
            float off = dragX + (i - index) * w;
            if (off <= -getWidth() || off >= getWidth()) continue;
            Bitmap b = source.page(i);
            if (b != null && !b.isRecycled()) shown.put(i, b);
            else b = shown.get(i);
            if (b != null && b.isRecycled()) b = null;
            boolean has = fit(b, rect);
            canvas.save();
            canvas.translate(off, 0);
            if (i == index && scale != 1f) {
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                canvas.translate(tx, ty);
                canvas.scale(scale, scale, cx, cy);
            }
            if (has) canvas.drawBitmap(b, null, rect, paint);
            else canvas.drawRect(rect, loading);
            canvas.drawRect(rect, frame);
            canvas.restore();
        }
    }
}
