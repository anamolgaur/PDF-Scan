package com.anamol.pdfscan;

/**
 * Finding the page in a photo, in two layers:
 *  1. the on-device neural network (PageNet), which copes with cluttered desks, white-on-white,
 *     hands, shadows and pages partly out of the frame;
 *  2. as a backup, the classic detector (ScanMath), used whenever the network is unsure or
 *     switched off.
 * Either way the corners are then snapped onto the page's real edges (ScanMath.refineCorners).
 * Pure Java, so it's tested on the desktop.
 */
final class PageFind {
    /** The network must be at least this sure of every corner. */
    static final float MIN_CONFIDENCE = 0.5f;

    private PageFind() {
    }

    /** Corners for a finished photo (about 1000-1400 px on the long side), or null if no page. */
    static float[] corners(PageNet net, int[] px, int w, int h) {
        float[] c = byNetwork(net, px, w, h, MIN_CONFIDENCE);
        if (c == null) {
            int[] wh = new int[2];
            int[] small = shrink(px, w, h, 320, wh);
            c = ScanMath.detectCorners(small, wh[0], wh[1]);
        }
        return c == null ? null : ScanMath.refineCorners(px, w, h, c);
    }

    /** The network's corners when it is sure and they make a sensible page shape; else null. */
    static float[] byNetwork(PageNet net, int[] px, int w, int h, float minConfidence) {
        if (net == null) return null;
        try {
            PageNet.Corners r = net.find(px, w, h);
            if (r.minConfidence() < minConfidence || !plausible(r.corners)) return null;
            return r.corners;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    static boolean plausible(float[] c) {
        if (!ScanMath.convex(c) || ScanMath.quadArea(c) < 0.08f) return false;
        for (int i = 0; i < 4; i++) {
            float dx = c[((i + 1) % 4) * 2] - c[i * 2], dy = c[((i + 1) % 4) * 2 + 1] - c[i * 2 + 1];
            if (Math.hypot(dx, dy) < 0.08) return false;
        }
        return true;
    }

    /** Box-averaged smaller copy; outWH receives the new width and height. */
    static int[] shrink(int[] px, int w, int h, int maxSide, int[] outWH) {
        float s = Math.min(1f, maxSide / (float) Math.max(w, h));
        int sw = Math.max(8, Math.round(w * s)), sh = Math.max(8, Math.round(h * s));
        outWH[0] = sw;
        outWH[1] = sh;
        if (sw == w && sh == h) return px;
        int[] out = new int[sw * sh];
        for (int oy = 0; oy < sh; oy++) {
            int ya = oy * h / sh, yb = Math.max(ya + 1, (oy + 1) * h / sh);
            for (int ox = 0; ox < sw; ox++) {
                int xa = ox * w / sw, xb = Math.max(xa + 1, (ox + 1) * w / sw);
                int r = 0, g = 0, b = 0, n = 0;
                for (int y = ya; y < yb; y++) {
                    for (int x = xa; x < xb; x++) {
                        int c = px[y * w + x];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                out[oy * sw + ox] = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
        return out;
    }
}
