package com.anamol.pdfscan;

/**
 * Pure-Java page image operations (no Android types; unit-tested on a desktop JVM).
 * Pixels are opaque ARGB ints, row-major.
 */
final class ImageOps {
    static final String ORIGINAL = "original";
    static final String AUTO = "auto";
    static final String GREY = "grey";
    static final String SCAN = "scan";
    static final String BW = "bw";
    static final String[] FILTERS = {ORIGINAL, AUTO, GREY, SCAN, BW};
    static final String[] FILTER_LABELS = {"Original", "Auto colour", "Greyscale", "Scan", "B&W"};

    /** Look settings for one page. Sliders run from -1 to 1 (sharpen 0 to 1). */
    static final class Adjust {
        String filter = ORIGINAL;
        boolean glare;
        float brightness;
        float contrast;
        float sharpen;

        Adjust copy() {
            Adjust a = new Adjust();
            a.filter = filter;
            a.glare = glare;
            a.brightness = brightness;
            a.contrast = contrast;
            a.sharpen = sharpen;
            return a;
        }
    }

    private ImageOps() {
    }

    static int r(int c) {
        return (c >> 16) & 255;
    }

    static int g(int c) {
        return (c >> 8) & 255;
    }

    static int b(int c) {
        return c & 255;
    }

    static int rgb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    static int luma(int c) {
        return (r(c) * 77 + g(c) * 150 + b(c) * 29) >> 8;
    }

    /** Applies the whole look: filter (with optional glare reduction), brightness/contrast, sharpening. */
    static void apply(int[] px, int w, int h, Adjust a) {
        String f = a.filter == null ? ORIGINAL : a.filter;
        if (a.glare) removeVeil(px, w, h, 32);
        switch (f) {
            case AUTO:
                normalizeColour(px, w, h, 32, 1.15f);
                break;
            case GREY:
                toGrey(px);
                normalizeColour(px, w, h, 32, 1f);
                break;
            case SCAN:
                scan(px, w, h, 32);
                break;
            case BW:
                scan(px, w, h, 32);
                threshold(px, 170);
                break;
            default:
                break;
        }
        if (a.brightness != 0f || a.contrast != 0f) brightnessContrast(px, a.brightness, a.contrast);
        if (a.sharpen > 0f) sharpen(px, w, h, a.sharpen);
    }

    // ------------------------------------------------------------------ lighting estimate

    /** Grid of local paper brightness: block averages, max-filtered twice to drop ink, then smoothed. */
    static float[] lightGrid(int[] luma, int w, int h, int block, boolean[] skip) {
        int gw = (w + block - 1) / block;
        int gh = (h + block - 1) / block;
        float[] grid = new float[gw * gh];
        int[] cnt = new int[gw * gh];
        for (int y = 0; y < h; y++) {
            int row = (y / block) * gw;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (skip != null && skip[i]) continue;
                int k = row + x / block;
                grid[k] += luma[i];
                cnt[k]++;
            }
        }
        for (int k = 0; k < grid.length; k++) grid[k] = cnt[k] == 0 ? -1f : grid[k] / cnt[k];
        fillHoles(grid, gw, gh);
        grid = maxFilter(grid, gw, gh);
        grid = maxFilter(grid, gw, gh);
        return boxBlur(grid, gw, gh);
    }

    /** Blocks with no samples (all skipped) take the brightest neighbouring value. */
    private static void fillHoles(float[] grid, int gw, int gh) {
        boolean any = true;
        for (int pass = 0; pass < gw + gh && any; pass++) {
            any = false;
            float[] copy = grid.clone();
            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    if (copy[y * gw + x] >= 0f) continue;
                    float m = -1f;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            int yy = y + dy;
                            if (xx < 0 || yy < 0 || xx >= gw || yy >= gh) continue;
                            m = Math.max(m, copy[yy * gw + xx]);
                        }
                    }
                    grid[y * gw + x] = m;
                    if (m < 0f) any = true;
                }
            }
        }
        for (int k = 0; k < grid.length; k++) if (grid[k] < 0f) grid[k] = 255f;
    }

    static float sample(float[] grid, int gw, int gh, int block, int x, int y) {
        float fx = (x + 0.5f) / block - 0.5f;
        float fy = (y + 0.5f) / block - 0.5f;
        int x0 = ScanMath.clamp((int) Math.floor(fx), 0, gw - 1);
        int y0 = ScanMath.clamp((int) Math.floor(fy), 0, gh - 1);
        int x1 = Math.min(x0 + 1, gw - 1);
        int y1 = Math.min(y0 + 1, gh - 1);
        float tx = ScanMath.clampf(fx - x0, 0f, 1f);
        float ty = ScanMath.clampf(fy - y0, 0f, 1f);
        float a = grid[y0 * gw + x0] + (grid[y0 * gw + x1] - grid[y0 * gw + x0]) * tx;
        float c = grid[y1 * gw + x0] + (grid[y1 * gw + x1] - grid[y1 * gw + x0]) * tx;
        return a + (c - a) * ty;
    }

    private static float[] maxFilter(float[] src, int w, int h) {
        float[] out = new float[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float m = 0f;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = ScanMath.clamp(y + dy, 0, h - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        m = Math.max(m, src[yy * w + ScanMath.clamp(x + dx, 0, w - 1)]);
                    }
                }
                out[y * w + x] = m;
            }
        }
        return out;
    }

    private static float[] boxBlur(float[] src, int w, int h) {
        float[] out = new float[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0f;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = ScanMath.clamp(y + dy, 0, h - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        s += src[yy * w + ScanMath.clamp(x + dx, 0, w - 1)];
                    }
                }
                out[y * w + x] = s / 9f;
            }
        }
        return out;
    }

    private static int[] lumaOf(int[] px) {
        int[] l = new int[px.length];
        for (int i = 0; i < px.length; i++) l[i] = luma(px[i]);
        return l;
    }

    // ------------------------------------------------------------------ filters

    static void toGrey(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int v = luma(px[i]);
            px[i] = rgb(v, v, v);
        }
    }

    /**
     * Divides out uneven lighting while keeping colour: shadows lift, bright glare patches
     * come down to paper level, so faded text under them regains contrast.
     */
    static void normalizeColour(int[] px, int w, int h, int block, float saturation) {
        int[] l = lumaOf(px);
        int gw = (w + block - 1) / block;
        int gh = (h + block - 1) / block;
        float[] grid = lightGrid(l, w, h, block, null);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float k = 245f / Math.max(sample(grid, gw, gh, block, x, y), 24f);
                int c = px[i];
                float rr = r(c) * k;
                float gg = g(c) * k;
                float bb = b(c) * k;
                if (saturation != 1f) {
                    float m = (rr * 77 + gg * 150 + bb * 29) / 256f;
                    rr = m + (rr - m) * saturation;
                    gg = m + (gg - m) * saturation;
                    bb = m + (bb - m) * saturation;
                }
                // gentle stretch so paper reaches white and ink stays deep
                px[i] = rgb(Math.round((rr - 12f) * 1.06f), Math.round((gg - 12f) * 1.06f), Math.round((bb - 12f) * 1.06f));
            }
        }
    }

    /**
     * Glare reduction. Glare adds a bright veil that lifts ink towards white. For each area,
     * estimate the local paper level and the local darkest ink, then stretch that range back to
     * full contrast. Blank areas are protected by a minimum range so they stay paper-coloured.
     */
    static void removeVeil(int[] px, int w, int h, int block) {
        int[] l = lumaOf(px);
        int gw = (w + block - 1) / block;
        int gh = (h + block - 1) / block;
        float[] paper = lightGrid(l, w, h, block, null);
        float[] ink = darkGrid(l, w, h, block);
        final float minSpan = 70f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float p = sample(paper, gw, gh, block, x, y);
                float d = Math.min(sample(ink, gw, gh, block, x, y), p - minSpan);
                float scale = (245f - 35f) / Math.max(p - d, 1f);
                int c = px[i];
                px[i] = rgb(Math.round((r(c) - d) * scale + 35f),
                        Math.round((g(c) - d) * scale + 35f),
                        Math.round((b(c) - d) * scale + 35f));
            }
        }
    }

    /** Grid of local darkest values (ink level), spread to neighbours and smoothed. */
    static float[] darkGrid(int[] luma, int w, int h, int block) {
        int gw = (w + block - 1) / block;
        int gh = (h + block - 1) / block;
        float[] grid = new float[gw * gh];
        java.util.Arrays.fill(grid, 255f);
        for (int y = 0; y < h; y++) {
            int row = (y / block) * gw;
            for (int x = 0; x < w; x++) {
                int k = row + x / block;
                grid[k] = Math.min(grid[k], luma[y * w + x]);
            }
        }
        float[] out = new float[grid.length];
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                float m = 255f;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = ScanMath.clamp(y + dy, 0, gh - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        m = Math.min(m, grid[yy * gw + ScanMath.clamp(x + dx, 0, gw - 1)]);
                    }
                }
                out[y * gw + x] = m;
            }
        }
        return boxBlur(out, gw, gh);
    }

    /** Clean black-on-white document look (see ScanMath.scanFilter), with a chosen block size. */
    static void scan(int[] px, int w, int h, int block) {
        int[] l = lumaOf(px);
        int gw = (w + block - 1) / block;
        int gh = (h + block - 1) / block;
        float[] grid = lightGrid(l, w, h, block, null);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float v = l[i] * 255f / Math.max(sample(grid, gw, gh, block, x, y), 16f);
                int o = Math.round((v - 70f) * (255f / 170f));
                px[i] = rgb(o, o, o);
            }
        }
    }

    static void threshold(int[] px, int level) {
        for (int i = 0; i < px.length; i++) px[i] = luma(px[i]) >= level ? 0xFFFFFFFF : 0xFF000000;
    }

    /** brightness and contrast in -1..1. */
    static void brightnessContrast(int[] px, float brightness, float contrast) {
        float factor = contrast >= 0 ? 1f + 2f * contrast : 1f + contrast;
        float offset = brightness * 90f;
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) lut[v] = clamp(Math.round((v - 128f) * factor + 128f + offset));
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = 0xFF000000 | (lut[r(c)] << 16) | (lut[g(c)] << 8) | lut[b(c)];
        }
    }

    /** Unsharp mask with a 3x3 blur; amount 0..1. */
    static void sharpen(int[] px, int w, int h, float amount) {
        if (w < 3 || h < 3) return;
        int[] src = px.clone();
        float k = amount * 1.5f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sr = 0;
                int sg = 0;
                int sb = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = ScanMath.clamp(y + dy, 0, h - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        int c = src[yy * w + ScanMath.clamp(x + dx, 0, w - 1)];
                        sr += r(c);
                        sg += g(c);
                        sb += b(c);
                    }
                }
                int c = src[y * w + x];
                px[y * w + x] = rgb(
                        Math.round(r(c) + k * (r(c) - sr / 9f)),
                        Math.round(g(c) + k * (g(c) - sg / 9f)),
                        Math.round(b(c) + k * (b(c) - sb / 9f)));
            }
        }
    }

    // ------------------------------------------------------------------ cleanup

    /** Replaces masked pixels with the surrounding paper colour (estimated per channel). */
    static void fillWithPaper(int[] px, int w, int h, boolean[] mask) {
        boolean any = false;
        for (boolean m : mask) {
            if (m) {
                any = true;
                break;
            }
        }
        if (!any) return;
        final int block = 24;
        int gw = (w + block - 1) / block;
        int gh = (h + block - 1) / block;
        int[] ch = new int[px.length];
        float[][] grids = new float[3][];
        for (int c = 0; c < 3; c++) {
            int shift = 16 - 8 * c;
            for (int i = 0; i < px.length; i++) ch[i] = (px[i] >> shift) & 255;
            grids[c] = lightGrid(ch, w, h, block, mask);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!mask[i]) continue;
                px[i] = rgb(
                        Math.round(sample(grids[0], gw, gh, block, x, y)),
                        Math.round(sample(grids[1], gw, gh, block, x, y)),
                        Math.round(sample(grids[2], gw, gh, block, x, y)));
            }
        }
    }
}
