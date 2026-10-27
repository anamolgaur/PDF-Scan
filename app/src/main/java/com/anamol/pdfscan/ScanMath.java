package com.anamol.pdfscan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java image maths with no Android types, so it can be tested on a desktop JVM.
 * Pixels are ARGB ints, row-major. Corners are normalised (0..1) in the order
 * top-left, top-right, bottom-right, bottom-left as {x0,y0,x1,y1,x2,y2,x3,y3}.
 */
final class ScanMath {
    /** Why the last outline detection gave up (for diagnostics). */
    static volatile String why = "";

    static final float[] FULL = {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};

    private ScanMath() {
    }

    static int luma(int c) {
        return (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------ scan filter

    /**
     * In place: greyscale, divide out the lighting (removes shadows and uneven light),
     * then stretch contrast so paper is white and ink is black.
     */
    static void scanFilter(int[] px, int w, int h) {
        final int block = 32;
        int gw = (w + block - 1) / block;
        int gh = (h + block - 1) / block;
        float[] bg = new float[gw * gh];
        int[] cnt = new int[gw * gh];
        for (int y = 0; y < h; y++) {
            int row = (y / block) * gw;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int g = luma(px[i]);
                px[i] = g;
                int k = row + x / block;
                bg[k] += g;
                cnt[k]++;
            }
        }
        for (int k = 0; k < bg.length; k++) bg[k] /= Math.max(1, cnt[k]);
        // Max filter removes dark text from the lighting estimate; blur smooths block edges.
        bg = dilate(bg, gw, gh);
        bg = dilate(bg, gw, gh);
        bg = blur(bg, gw, gh);

        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) / block - 0.5f;
            int y0 = clamp((int) Math.floor(fy), 0, gh - 1);
            int y1 = Math.min(y0 + 1, gh - 1);
            float ty = clampf(fy - y0, 0f, 1f);
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) / block - 0.5f;
                int x0 = clamp((int) Math.floor(fx), 0, gw - 1);
                int x1 = Math.min(x0 + 1, gw - 1);
                float tx = clampf(fx - x0, 0f, 1f);
                float a = bg[y0 * gw + x0] + (bg[y0 * gw + x1] - bg[y0 * gw + x0]) * tx;
                float b = bg[y1 * gw + x0] + (bg[y1 * gw + x1] - bg[y1 * gw + x0]) * tx;
                float light = a + (b - a) * ty;
                int i = y * w + x;
                float v = px[i] * 255f / Math.max(light, 16f);
                int o = clamp(Math.round((v - 70f) * (255f / 170f)), 0, 255);
                px[i] = 0xFF000000 | (o << 16) | (o << 8) | o;
            }
        }
    }

    private static float[] dilate(float[] src, int w, int h) {
        float[] out = new float[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float m = 0f;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = clamp(y + dy, 0, h - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = clamp(x + dx, 0, w - 1);
                        m = Math.max(m, src[yy * w + xx]);
                    }
                }
                out[y * w + x] = m;
            }
        }
        return out;
    }

    private static float[] blur(float[] src, int w, int h) {
        float[] out = new float[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0f;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = clamp(y + dy, 0, h - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = clamp(x + dx, 0, w - 1);
                        s += src[yy * w + xx];
                    }
                }
                out[y * w + x] = s / 9f;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ page detection

    /**
     * Finds a sheet of paper that is lighter than its background. Returns normalised
     * corners, or null when no clear page is found (caller then uses the full frame).
     * Intended for a small preview image (about 300 px on the long side).
     */
    static float[] detectCorners(int[] px, int w, int h) {
        return detect(px, w, h, false);
    }

    /**
     * Stricter detection for live auto-capture: returns corners only when the frame really shows
     * a sheet of paper. The bright region must fill its outline (a page is a solid shape), be a
     * roughly rectangular four-sided shape with sensible corner angles and proportions, not run
     * off the frame on several sides, and stand out clearly from what it is lying on.
     */
    static float[] detectPage(int[] px, int w, int h) {
        return detect(px, w, h, true);
    }

    /**
     * Finds the page first by brightness (a light sheet on a darker surface), then, if that fails,
     * by its outline (works for a white page on a white or patterned surface, or uneven light).
     */
    private static float[] detect(int[] px, int w, int h, boolean strict) {
        float[] c = detectBright(px, w, h, strict);
        return c != null ? c : detectByEdges(px, w, h, strict);
    }

    /** Shape checks shared by both detectors for live auto-capture. */
    private static boolean shapeOk(float[] c, int regionSize, int n, int w, int h) {
        float area = quadArea(c);
        if (area > 0.9f) return false;
        // a sheet fills its outline; scattered clutter or round things do not
        if (regionSize < area * n * 0.88f || regionSize > area * n * 1.08f) return false;
        // at most one corner may sit on the frame edge
        int onEdge = 0;
        for (int i = 0; i < 4; i++) {
            float x = c[i * 2];
            float y = c[i * 2 + 1];
            if (x < 0.015f || x > 0.985f || y < 0.015f || y > 0.985f) onEdge++;
        }
        return onEdge <= 1 && rectangular(c, w, h);
    }

    private static float[] detectBright(int[] px, int w, int h, boolean strict) {
        int n = w * h;
        if (w < 8 || h < 8) return null;
        int[] g = new int[n];
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) {
            g[i] = luma(px[i]);
            hist[g[i]]++;
        }
        int thr = otsu(hist, n);
        boolean[] mask = new boolean[n];
        for (int i = 0; i < n; i++) mask[i] = g[i] > thr;

        int[] label = new int[n];
        int[] stack = new int[n];
        int best = 0;
        int bestSize = 0;
        int cur = 0;
        for (int s = 0; s < n; s++) {
            if (!mask[s] || label[s] != 0) continue;
            cur++;
            int size = 0;
            int sp = 0;
            stack[sp++] = s;
            label[s] = cur;
            while (sp > 0) {
                int p = stack[--sp];
                size++;
                int x = p % w;
                int y = p / w;
                if (x > 0 && mask[p - 1] && label[p - 1] == 0) {
                    label[p - 1] = cur;
                    stack[sp++] = p - 1;
                }
                if (x < w - 1 && mask[p + 1] && label[p + 1] == 0) {
                    label[p + 1] = cur;
                    stack[sp++] = p + 1;
                }
                if (y > 0 && mask[p - w] && label[p - w] == 0) {
                    label[p - w] = cur;
                    stack[sp++] = p - w;
                }
                if (y < h - 1 && mask[p + w] && label[p + w] == 0) {
                    label[p + w] = cur;
                    stack[sp++] = p + w;
                }
            }
            if (size > bestSize) {
                bestSize = size;
                best = cur;
            }
        }
        if (best == 0 || bestSize < n * 0.15f) return null;

        int tl = -1, tr = -1, br = -1, bl = -1;
        int minS = Integer.MAX_VALUE, maxS = Integer.MIN_VALUE;
        int minD = Integer.MAX_VALUE, maxD = Integer.MIN_VALUE;
        for (int p = 0; p < n; p++) {
            if (label[p] != best) continue;
            int x = p % w;
            int y = p / w;
            int sum = x + y;
            int diff = x - y;
            if (sum < minS) { minS = sum; tl = p; }
            if (sum > maxS) { maxS = sum; br = p; }
            if (diff > maxD) { maxD = diff; tr = p; }
            if (diff < minD) { minD = diff; bl = p; }
        }
        float fw = w - 1f;
        float fh = h - 1f;
        float[] c = {
                (tl % w) / fw, (tl / w) / fh,
                (tr % w) / fw, (tr / w) / fh,
                (br % w) / fw, (br / w) / fh,
                (bl % w) / fw, (bl / w) / fh};
        float area = quadArea(c);
        if (area < 0.15f || area > 0.96f) return null;
        if (!strict) return c;

        if (!shapeOk(c, bestSize, n, w, h)) return null;
        // clear contrast between the page and what is around it
        long in = 0, out = 0;
        int nIn = 0, nOut = 0;
        for (int p = 0; p < n; p += 2) {
            int v = luma(px[p]);
            if (label[p] == best) {
                in += v;
                nIn++;
            } else {
                out += v;
                nOut++;
            }
        }
        if (nIn == 0 || nOut == 0) return null;
        if (in / (float) nIn - out / (float) nOut < 35f) return null;
        return c;
    }

    /**
     * Outline detector: strong edges (Sobel) form walls; everything that cannot be reached from
     * the frame border without crossing a wall is enclosed. The largest enclosed region whose
     * outline really runs along edges is the page.
     */
    private static float[] detectByEdges(int[] px, int w, int h, boolean strict) {
        int n = w * h;
        if (w < 16 || h < 16) { why = "e1"; return null; }
        int[] g = new int[n];
        for (int i = 0; i < n; i++) g[i] = luma(px[i]);
        // 3x3 box blur against sensor noise and paper texture
        int[] b = new int[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int s = 0, k = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        s += g[yy * w + xx];
                        k++;
                    }
                }
                b[y * w + x] = s / k;
            }
        }
        int[] mag = new int[n];
        int[] hist = new int[1024];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int p = y * w + x;
                int gx = b[p - w + 1] + 2 * b[p + 1] + b[p + w + 1] - b[p - w - 1] - 2 * b[p - 1] - b[p + w - 1];
                int gy = b[p + w - 1] + 2 * b[p + w] + b[p + w + 1] - b[p - w - 1] - 2 * b[p - w] - b[p - w + 1];
                int m = Math.min(1023, Math.abs(gx) + Math.abs(gy));
                mag[p] = m;
                hist[m]++;
            }
        }
        // threshold well above the noise floor (median gradient), not tied to how much text there is
        int target = n / 2;
        int acc = 0;
        int median = 0;
        for (int i = 0; i < 1024; i++) {
            acc += hist[i];
            if (acc >= target) {
                median = i;
                break;
            }
        }
        int thr = median * 3;
        thr = Math.max(36, thr);
        // thicken edges so small gaps in the outline (where page and surface match) close up
        final int R = Math.max(1, Math.round(Math.min(w, h) / 100f));
        boolean[] wall = new boolean[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (mag[y * w + x] <= thr) continue;
                for (int dy = -R; dy <= R; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -R; dx <= R; dx++) {
                        int xx = x + dx;
                        if (xx >= 0 && xx < w) wall[yy * w + xx] = true;
                    }
                }
            }
        }
        // outline candidates: the biggest connected edge pieces. A page outline is one piece even
        // when a stretch of it is missing (where page and surface happen to match).
        int[] label = new int[n];
        int[] stack = new int[n];
        List<int[]> comps = new ArrayList<>();  // {label, size}
        int cur = 0;
        for (int s = 0; s < n; s++) {
            if (!wall[s] || label[s] != 0) continue;
            cur++;
            int size = 0;
            int sp = 0;
            stack[sp++] = s;
            label[s] = cur;
            while (sp > 0) {
                int p = stack[--sp];
                size++;
                int x = p % w;
                int y = p / w;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int q = yy * w + xx;
                        if (wall[q] && label[q] == 0) {
                            label[q] = cur;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            comps.add(new int[]{cur, size});
        }
        if (comps.isEmpty()) { why = "e2"; return null; }
        Collections.sort(comps, (a, b2) -> Integer.compare(b2[1], a[1]));
        float fw = w - 1f;
        float fh = h - 1f;
        float[] best = null;
        float bestArea = 0f;
        for (int k = 0; k < Math.min(5, comps.size()); k++) {
            int id = comps.get(k)[0];
            int tl = -1, tr = -1, br = -1, bl = -1;
            int minS = Integer.MAX_VALUE, maxS = Integer.MIN_VALUE;
            int minD = Integer.MAX_VALUE, maxD = Integer.MIN_VALUE;
            for (int p = 0; p < n; p++) {
                if (label[p] != id) continue;
                int x = p % w;
                int y = p / w;
                int sum = x + y;
                int diff = x - y;
                if (sum < minS) { minS = sum; tl = p; }
                if (sum > maxS) { maxS = sum; br = p; }
                if (diff > maxD) { maxD = diff; tr = p; }
                if (diff < minD) { minD = diff; bl = p; }
            }
            float[] c = {
                    (tl % w) / fw, (tl / w) / fh,
                    (tr % w) / fw, (tr / w) / fh,
                    (br % w) / fw, (br / w) / fh,
                    (bl % w) / fw, (bl / w) / fh};
            float area = quadArea(c);
            if (area < 0.15f || area > (strict ? 0.9f : 0.96f)) { why = "e3"; continue; }
            if (!rectangular(c, w, h)) { why = "e4"; continue; }
            // the outline must actually follow edges most of the way round
            if (edgeSupport(c, wall, w, h) < (strict ? 0.75f : 0.6f)) { why = "e5"; continue; }
            if (strict) {
                int onEdge = 0;
                for (int i = 0; i < 4; i++) {
                    float x = c[i * 2];
                    float y = c[i * 2 + 1];
                    if (x < 0.015f || x > 0.985f || y < 0.015f || y > 0.985f) onEdge++;
                }
                if (onEdge > 1) { why = "e8"; continue; }
            }
            if (area > bestArea) {
                bestArea = area;
                best = c;
            }
        }
        if (best != null && R > 0) {
            // walls are thickened: pull the corners back onto the middle of the edge
            float cx = (best[0] + best[2] + best[4] + best[6]) / 4f;
            float cy = (best[1] + best[3] + best[5] + best[7]) / 4f;
            for (int i = 0; i < 4; i++) {
                float dx = cx - best[i * 2], dy = cy - best[i * 2 + 1];
                float len = (float) Math.hypot(dx * fw, dy * fh);
                if (len > 0) {
                    best[i * 2] += dx / len * R;
                    best[i * 2 + 1] += dy / len * R;
                }
            }
        }
        return best;
    }

    /**
     * Flattens the bend of a book page near its spine. Text lines on a page photographed in an
     * open book bow up or down as they approach the binding; this shifts each column back by a
     * curve that is zero at the outer edge and largest at the spine.
     * amount: -1..1 (sign = bend direction); spineLeft: which side the binding is on.
     */
    static int[] flattenCurve(int[] px, int w, int h, float amount, boolean spineLeft) {
        int[] out = new int[w * h];
        if (amount == 0f) {
            System.arraycopy(px, 0, out, 0, out.length);
            return out;
        }
        float maxShift = amount * 0.08f * h;
        for (int x = 0; x < w; x++) {
            float t = w > 1 ? x / (float) (w - 1) : 0f;
            float s = spineLeft ? (1f - t) : t;
            float shift = maxShift * s * s;
            for (int y = 0; y < h; y++) {
                float sy = y + shift;
                int y0 = (int) Math.floor(sy);
                float f = sy - y0;
                int a = sample(px, w, h, x, y0);
                int b = sample(px, w, h, x, y0 + 1);
                out[y * w + x] = lerp(a, b, f);
            }
        }
        return out;
    }

    private static int sample(int[] px, int w, int h, int x, int y) {
        if (y < 0 || y >= h) return 0xFFFFFFFF;  // outside the page: paper white
        return px[y * w + x];
    }

    private static int lerp(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * Where the spine of an open book runs in a two-page photo: the darkest vertical band
     * (the shadow in the fold) in the middle part of the image. Returns its position as a
     * fraction of the width, or 0.5 when there is no clear fold.
     */
    static float gutter(int[] px, int w, int h) {
        if (w < 20 || h < 4) return 0.5f;
        double[] col = new double[w];
        int y0 = h / 10, y1 = h - h / 10;
        for (int x = 0; x < w; x++) {
            long s = 0;
            for (int y = y0; y < y1; y++) s += luma(px[y * w + x]);
            col[x] = s / (double) Math.max(1, y1 - y0);
        }
        int rad = Math.max(1, w / 80);
        double[] sm = new double[w];
        for (int x = 0; x < w; x++) {
            double s = 0;
            int k = 0;
            for (int d = -rad; d <= rad; d++) {
                int xx = x + d;
                if (xx < 0 || xx >= w) continue;
                s += col[xx];
                k++;
            }
            sm[x] = s / k;
        }
        int lo = Math.round(w * 0.3f), hi = Math.round(w * 0.7f);
        int best = -1;
        double bestV = Double.MAX_VALUE, mean = 0;
        for (int x = lo; x < hi; x++) {
            mean += sm[x];
            if (sm[x] < bestV) {
                bestV = sm[x];
                best = x;
            }
        }
        mean /= Math.max(1, hi - lo);
        if (best < 0 || mean - bestV < 6) return 0.5f;
        return best / (float) (w - 1);
    }

    /** Splits a page outline at a fraction of its width into left and right halves. */
    static float[][] splitQuad(float[] c, float f) {
        float[] q = c == null ? FULL : c;
        float tx = q[0] + (q[2] - q[0]) * f, ty = q[1] + (q[3] - q[1]) * f;
        float bx = q[6] + (q[4] - q[6]) * f, by = q[7] + (q[5] - q[7]) * f;
        float[] left = {q[0], q[1], tx, ty, bx, by, q[6], q[7]};
        float[] right = {tx, ty, q[2], q[3], q[4], q[5], bx, by};
        return new float[][]{left, right};
    }

    static boolean inQuad(float[] c, float x, float y) {
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            float ax = c[i * 2], ay = c[i * 2 + 1];
            float bx = c[((i + 1) % 4) * 2], by = c[((i + 1) % 4) * 2 + 1];
            float cr = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
            int s = cr > 0 ? 1 : (cr < 0 ? -1 : 0);
            if (s == 0) continue;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return true;
    }

    /** Fraction of points along the quad's four sides that lie on (or next to) an edge. */
    private static float edgeSupport(float[] c, boolean[] wall, int w, int h) {
        int hits = 0, total = 0;
        for (int side = 0; side < 4; side++) {
            float x0 = c[side * 2] * (w - 1), y0 = c[side * 2 + 1] * (h - 1);
            float x1 = c[((side + 1) % 4) * 2] * (w - 1), y1 = c[((side + 1) % 4) * 2 + 1] * (h - 1);
            for (int i = 2; i < 38; i++) {
                float t = i / 40f;
                int x = Math.round(x0 + (x1 - x0) * t);
                int y = Math.round(y0 + (y1 - y0) * t);
                total++;
                boolean hit = false;
                for (int dy = -2; dy <= 2 && !hit; dy++) {
                    for (int dx = -2; dx <= 2 && !hit; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < w && yy < h && wall[yy * w + xx]) hit = true;
                    }
                }
                if (hit) hits++;
            }
        }
        return total == 0 ? 0f : hits / (float) total;
    }

    /** Convex, corner angles between 60 and 120 degrees, opposite sides alike, sane proportions. */
    static boolean rectangular(float[] c, int w, int h) {
        double[] x = new double[4];
        double[] y = new double[4];
        for (int i = 0; i < 4; i++) {
            x[i] = c[i * 2] * w;
            y[i] = c[i * 2 + 1] * h;
        }
        double[] len = new double[4];
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int a = (i + 3) % 4, b = (i + 1) % 4;
            double ax = x[a] - x[i], ay = y[a] - y[i];
            double bx = x[b] - x[i], by = y[b] - y[i];
            double la = Math.hypot(ax, ay), lb = Math.hypot(bx, by);
            if (la < 1e-6 || lb < 1e-6) return false;
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ax * bx + ay * by) / (la * lb)))));
            if (angle < 60 || angle > 120) return false;
            double cross = ax * by - ay * bx;
            int s = cross > 0 ? 1 : -1;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
            len[i] = lb;  // side from corner i to i+1
        }
        double r1 = Math.min(len[0], len[2]) / Math.max(len[0], len[2]);
        double r2 = Math.min(len[1], len[3]) / Math.max(len[1], len[3]);
        if (r1 < 0.6 || r2 < 0.6) return false;
        double aspect = (len[0] + len[2]) / (len[1] + len[3]);
        return aspect > 0.35 && aspect < 2.9;
    }

    static int otsu(int[] hist, int total) {
        double sum = 0;
        for (int i = 0; i < 256; i++) sum += i * (double) hist[i];
        double sumB = 0;
        int wB = 0;
        double bestVar = -1;
        int thr = 127;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;
            sumB += t * (double) hist[t];
            double mB = sumB / wB;
            double mF = (sum - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > bestVar) {
                bestVar = between;
                thr = t;
            }
        }
        return thr;
    }

    /** Shoelace area of a normalised quad (1.0 = whole frame). */
    static float quadArea(float[] c) {
        float a = 0f;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            a += c[2 * i] * c[2 * j + 1] - c[2 * j] * c[2 * i + 1];
        }
        return Math.abs(a) / 2f;
    }

    static boolean isFull(float[] c) {
        if (c == null) return true;
        for (int i = 0; i < 8; i++) {
            if (Math.abs(c[i] - FULL[i]) > 0.002f) return false;
        }
        return true;
    }

    /** Corners after the image is rotated 90 degrees clockwise: (x, y) becomes (1 - y, x). */
    static float[] rotateCornersCw(float[] c) {
        if (c == null) return null;
        float[] r = new float[8];
        // old bottom-left becomes new top-left, old top-left becomes new top-right, and so on.
        int[] from = {3, 0, 1, 2};
        for (int k = 0; k < 4; k++) {
            float x = c[2 * from[k]];
            float y = c[2 * from[k] + 1];
            r[2 * k] = 1f - y;
            r[2 * k + 1] = x;
        }
        return r;
    }
}
