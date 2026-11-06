package com.anamol.pdfscan;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * On-device neural network that finds the four corners of a document in a photo.
 *
 * The network is DocAligner's lightweight heatmap model (LCNet backbone + BiFPN, Apache-2.0,
 * by DocsaidLab: github.com/DocsaidLab/DocAligner), converted by tools/pagenet_export.py into a
 * compact file (assets/pagenet.bin, 2.3 MB). This class runs it directly in plain Java, so the
 * app needs no machine-learning library, nothing is downloaded and nothing leaves the phone.
 *
 * It looks at a 256 x 256 copy of the photo and returns, for each corner, a heatmap of where that
 * corner probably is; the peak gives the position and its height the confidence.
 */
final class PageNet {
    static final int SIZE = 256;

    private static final int CONV = 1, RELU = 2, SIGMOID = 3, MAXPOOL = 4, RESIZE = 5, MEAN = 6,
            ADDC = 7, CLIP = 8, MULC = 9, MUL = 10, WSUM = 11;

    /** One step of the network. */
    private static final class Op {
        int code;
        int in, in2, out;
        int[] p;          // integer parameters
        float[] f;        // float parameters
        float[] w, b;     // convolution weights and bias
        int[] srcs;       // weighted-sum inputs
    }

    /** Result: corners (TL, TR, BR, BL, normalised 0..1) and how sure the network is of each. */
    static final class Corners {
        final float[] corners;
        final float[] confidence;

        Corners(float[] corners, float[] confidence) {
            this.corners = corners;
            this.confidence = confidence;
        }

        float minConfidence() {
            float m = 1f;
            for (float c : confidence) m = Math.min(m, c);
            return m;
        }
    }

    private final Op[] ops;
    private final int tensors;
    private final int output;
    private final int[] lastUse;

    private PageNet(Op[] ops, int tensors, int output) {
        this.ops = ops;
        this.tensors = tensors;
        this.output = output;
        lastUse = new int[tensors];
        Arrays.fill(lastUse, -1);
        for (int i = 0; i < ops.length; i++) {
            Op o = ops[i];
            if (o.code == WSUM) {
                for (int s : o.srcs) lastUse[s] = i;
            } else {
                lastUse[o.in] = i;
                if (o.code == MUL) lastUse[o.in2] = i;
            }
        }
    }

    static PageNet load(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw, 1 << 16));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != 'P' || magic[1] != 'G' || magic[2] != 'N' || magic[3] != 'T') throw new IOException("Not a page model");
        int version = le(in);
        if (version != 1) throw new IOException("Unknown page model version");
        int count = le(in);
        int tensors = le(in);
        int output = le(in);
        Op[] ops = new Op[count];
        for (int i = 0; i < count; i++) {
            Op o = new Op();
            o.code = le(in);
            switch (o.code) {
                case CONV:
                    o.in = le(in);
                    o.out = le(in);
                    o.p = new int[14];  // cin cout kh kw sh sw pt pl pb pr group dh dw relu
                    for (int k = 0; k < 14; k++) o.p[k] = le(in);
                    o.w = halfs(in);
                    o.b = halfs(in);
                    break;
                case RELU:
                case SIGMOID:
                case MEAN:
                    o.in = le(in);
                    o.out = le(in);
                    break;
                case MAXPOOL:
                    o.in = le(in);
                    o.out = le(in);
                    o.p = new int[]{le(in), le(in), le(in)};
                    break;
                case RESIZE:
                case CLIP:
                    o.in = le(in);
                    o.out = le(in);
                    o.f = new float[]{lef(in), lef(in)};
                    break;
                case ADDC:
                case MULC:
                    o.in = le(in);
                    o.out = le(in);
                    o.f = new float[]{lef(in)};
                    break;
                case MUL:
                    o.in = le(in);
                    o.in2 = le(in);
                    o.out = le(in);
                    break;
                case WSUM: {
                    o.out = le(in);
                    int k = le(in);
                    o.srcs = new int[k];
                    for (int j = 0; j < k; j++) o.srcs[j] = le(in);
                    o.f = new float[k];
                    for (int j = 0; j < k; j++) o.f[j] = lef(in);
                    o.in = o.srcs[0];
                    break;
                }
                default:
                    throw new IOException("Unknown step " + o.code + " in the page model");
            }
            ops[i] = o;
        }
        return new PageNet(ops, tensors, output);
    }

    private static int le(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static float lef(DataInputStream in) throws IOException {
        return Float.intBitsToFloat(le(in));
    }

    private static float[] halfs(DataInputStream in) throws IOException {
        int n = le(in);
        byte[] raw = new byte[n * 2];
        in.readFully(raw);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = halfToFloat(bb.getShort());
        return out;
    }

    static float halfToFloat(short h) {
        int bits = h & 0xFFFF;
        int sign = (bits >>> 15) << 31;
        int exp = (bits >>> 10) & 0x1F;
        int mant = bits & 0x3FF;
        if (exp == 0) {
            if (mant == 0) return Float.intBitsToFloat(sign);
            float v = mant / 1024f * (float) Math.pow(2, -14);
            return sign != 0 ? -v : v;
        }
        if (exp == 31) return Float.intBitsToFloat(sign | 0x7F800000 | (mant << 13));
        return Float.intBitsToFloat(sign | ((exp - 15 + 127) << 23) | (mant << 13));
    }

    // ------------------------------------------------------------------ running

    /** Runs the network on a 3 x 256 x 256 RGB input (0..1). Returns 4 x 128 x 128 heatmaps. */
    float[] run(float[] input, int[] outShape) {
        float[][] data = new float[tensors][];
        int[][] shape = new int[tensors][];
        data[0] = input;
        shape[0] = new int[]{3, SIZE, SIZE};
        for (int i = 0; i < ops.length; i++) {
            Op o = ops[i];
            switch (o.code) {
                case CONV:
                    conv(o, data, shape);
                    break;
                case RELU: {
                    float[] x = data[o.in], y = new float[x.length];
                    for (int k = 0; k < x.length; k++) y[k] = x[k] > 0 ? x[k] : 0;
                    put(data, shape, o.out, y, shape[o.in]);
                    break;
                }
                case SIGMOID: {
                    float[] x = data[o.in], y = new float[x.length];
                    for (int k = 0; k < x.length; k++) y[k] = (float) (1.0 / (1.0 + Math.exp(-x[k])));
                    put(data, shape, o.out, y, shape[o.in]);
                    break;
                }
                case ADDC:
                case MULC: {
                    float[] x = data[o.in], y = new float[x.length];
                    float c = o.f[0];
                    if (o.code == ADDC) for (int k = 0; k < x.length; k++) y[k] = x[k] + c;
                    else for (int k = 0; k < x.length; k++) y[k] = x[k] * c;
                    put(data, shape, o.out, y, shape[o.in]);
                    break;
                }
                case CLIP: {
                    float[] x = data[o.in], y = new float[x.length];
                    for (int k = 0; k < x.length; k++) y[k] = Math.max(o.f[0], Math.min(o.f[1], x[k]));
                    put(data, shape, o.out, y, shape[o.in]);
                    break;
                }
                case MEAN: {
                    int[] s = shape[o.in];
                    int plane = s[1] * s[2];
                    float[] x = data[o.in], y = new float[s[0]];
                    for (int c = 0; c < s[0]; c++) {
                        double acc = 0;
                        for (int k = 0; k < plane; k++) acc += x[c * plane + k];
                        y[c] = (float) (acc / plane);
                    }
                    put(data, shape, o.out, y, new int[]{s[0], 1, 1});
                    break;
                }
                case MUL: {
                    float[] a = data[o.in], b = data[o.in2];
                    int[] sa = shape[o.in], sb = shape[o.in2];
                    if (a.length < b.length) {
                        float[] t = a;
                        a = b;
                        b = t;
                        int[] ts = sa;
                        sa = sb;
                        sb = ts;
                    }
                    float[] y = new float[a.length];
                    if (b.length == a.length) {
                        for (int k = 0; k < a.length; k++) y[k] = a[k] * b[k];
                    } else {  // per-channel scale
                        int plane = sa[1] * sa[2];
                        for (int c = 0; c < sa[0]; c++) {
                            float m = b[c];
                            for (int k = c * plane, e = k + plane; k < e; k++) y[k] = a[k] * m;
                        }
                    }
                    put(data, shape, o.out, y, sa);
                    break;
                }
                case MAXPOOL:
                    maxPool(o, data, shape);
                    break;
                case RESIZE:
                    resize(o, data, shape);
                    break;
                case WSUM: {
                    float[] y = new float[data[o.srcs[0]].length];
                    for (int j = 0; j < o.srcs.length; j++) {
                        float[] x = data[o.srcs[j]];
                        float wj = o.f[j];
                        for (int k = 0; k < y.length; k++) y[k] += x[k] * wj;
                    }
                    put(data, shape, o.out, y, shape[o.srcs[0]]);
                    break;
                }
                default:
                    throw new IllegalStateException();
            }
            // free what is no longer needed, to keep memory low on phones
            for (int t = 1; t < tensors; t++) {
                if (lastUse[t] == i && t != output) data[t] = null;
            }
        }
        System.arraycopy(shape[output], 0, outShape, 0, 3);
        return data[output];
    }

    private static void put(float[][] data, int[][] shape, int id, float[] v, int[] s) {
        data[id] = v;
        shape[id] = s;
    }

    private static void conv(Op o, float[][] data, int[][] shape) {
        final int[] p = o.p;
        final int cin = p[0], cout = p[1], kh = p[2], kw = p[3], sh = p[4], sw = p[5];
        final int pt = p[6], pl = p[7], pb = p[8], pr = p[9], group = p[10], dh = p[11], dw = p[12];
        final boolean relu = p[13] != 0;
        final float[] x = data[o.in];
        final int[] s = shape[o.in];
        final int H = s[1], W = s[2];
        final int oh = (H + pt + pb - dh * (kh - 1) - 1) / sh + 1;
        final int ow = (W + pl + pr - dw * (kw - 1) - 1) / sw + 1;
        final int cinG = cin / group, coutG = cout / group;
        final float[] y = new float[cout * oh * ow];
        final float[] w = o.w, b = o.b;
        final boolean pointwise = kh == 1 && kw == 1 && sh == 1 && sw == 1 && pt == 0 && pl == 0;
        IntStream.range(0, cout).parallel().forEach(oc -> {
            int g = oc / coutG;
            int base = oc * oh * ow;
            Arrays.fill(y, base, base + oh * ow, b[oc]);
            for (int ic = 0; ic < cinG; ic++) {
                int inC = g * cinG + ic;
                int inBase = inC * H * W;
                if (pointwise) {
                    float wv = w[oc * cinG + ic];
                    if (wv == 0f) continue;
                    for (int k = 0, n = oh * ow; k < n; k++) y[base + k] += wv * x[inBase + k];
                    continue;
                }
                for (int ky = 0; ky < kh; ky++) {
                    for (int kx = 0; kx < kw; kx++) {
                        float wv = w[((oc * cinG + ic) * kh + ky) * kw + kx];
                        if (wv == 0f) continue;
                        int offX = kx * dw - pl;
                        // output columns whose input column is inside the picture
                        int oxStart = Math.max(0, (-offX + sw - 1) / sw);
                        int oxEnd = Math.min(ow, (W - 1 - offX) / sw + 1);
                        if (oxStart >= oxEnd) continue;
                        for (int oy = 0; oy < oh; oy++) {
                            int iy = oy * sh + ky * dh - pt;
                            if (iy < 0 || iy >= H) continue;
                            int row = inBase + iy * W + offX;
                            int orow = base + oy * ow;
                            if (sw == 1) {
                                for (int ox = oxStart; ox < oxEnd; ox++) y[orow + ox] += wv * x[row + ox];
                            } else {
                                for (int ox = oxStart; ox < oxEnd; ox++) y[orow + ox] += wv * x[row + ox * sw];
                            }
                        }
                    }
                }
            }
            if (relu) for (int k = base, e = base + oh * ow; k < e; k++) if (y[k] < 0) y[k] = 0;
        });
        put(data, shape, o.out, y, new int[]{cout, oh, ow});
    }

    private static void maxPool(Op o, float[][] data, int[][] shape) {
        int k = o.p[0], st = o.p[1], pad = o.p[2];
        int[] s = shape[o.in];
        int C = s[0], H = s[1], W = s[2];
        int oh = (H + 2 * pad - k) / st + 1, ow = (W + 2 * pad - k) / st + 1;
        float[] x = data[o.in], y = new float[C * oh * ow];
        for (int c = 0; c < C; c++) {
            for (int oy = 0; oy < oh; oy++) {
                for (int ox = 0; ox < ow; ox++) {
                    float m = Float.NEGATIVE_INFINITY;
                    for (int ky = 0; ky < k; ky++) {
                        int iy = oy * st + ky - pad;
                        if (iy < 0 || iy >= H) continue;
                        for (int kx = 0; kx < k; kx++) {
                            int ix = ox * st + kx - pad;
                            if (ix < 0 || ix >= W) continue;
                            m = Math.max(m, x[(c * H + iy) * W + ix]);
                        }
                    }
                    y[(c * oh + oy) * ow + ox] = m;
                }
            }
        }
        put(data, shape, o.out, y, new int[]{C, oh, ow});
    }

    /** Bilinear resize, "half pixel" alignment (as in the ONNX model). */
    private static void resize(Op o, float[][] data, int[][] shape) {
        int[] s = shape[o.in];
        int C = s[0], H = s[1], W = s[2];
        float scH = o.f[0], scW = o.f[1];
        int oh = (int) Math.floor(H * scH), ow = (int) Math.floor(W * scW);
        float[] x = data[o.in], y = new float[C * oh * ow];
        int[] x0 = new int[ow], x1 = new int[ow];
        float[] fx = new float[ow];
        for (int ox = 0; ox < ow; ox++) {
            float sx = Math.max(0f, (ox + 0.5f) / scW - 0.5f);
            x0[ox] = Math.min(W - 1, (int) sx);
            x1[ox] = Math.min(W - 1, x0[ox] + 1);
            fx[ox] = sx - (int) sx;
        }
        for (int oy = 0; oy < oh; oy++) {
            float sy = Math.max(0f, (oy + 0.5f) / scH - 0.5f);
            int y0 = Math.min(H - 1, (int) sy), y1 = Math.min(H - 1, y0 + 1);
            float fy = sy - (int) sy;
            for (int c = 0; c < C; c++) {
                int r0 = (c * H + y0) * W, r1 = (c * H + y1) * W, ro = (c * oh + oy) * ow;
                for (int ox = 0; ox < ow; ox++) {
                    float a = x[r0 + x0[ox]] + (x[r0 + x1[ox]] - x[r0 + x0[ox]]) * fx[ox];
                    float b = x[r1 + x0[ox]] + (x[r1 + x1[ox]] - x[r1 + x0[ox]]) * fx[ox];
                    y[ro + ox] = a + (b - a) * fy;
                }
            }
        }
        put(data, shape, o.out, y, new int[]{C, oh, ow});
    }

    // ------------------------------------------------------------------ corners from a photo

    /** The photo (ARGB pixels) squeezed to 256 x 256, averaging blocks so detail isn't lost. */
    static float[] input(int[] px, int w, int h) {
        float[] in = new float[3 * SIZE * SIZE];
        int plane = SIZE * SIZE;
        for (int oy = 0; oy < SIZE; oy++) {
            int ya = oy * h / SIZE, yb = Math.max(ya + 1, (oy + 1) * h / SIZE);
            for (int ox = 0; ox < SIZE; ox++) {
                int xa = ox * w / SIZE, xb = Math.max(xa + 1, (ox + 1) * w / SIZE);
                int r = 0, g = 0, b = 0, n = 0;
                for (int yy = ya; yy < yb && yy < h; yy++) {
                    for (int xx = xa; xx < xb && xx < w; xx++) {
                        int c = px[yy * w + xx];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                int i = oy * SIZE + ox;
                in[i] = r / (255f * n);
                in[plane + i] = g / (255f * n);
                in[2 * plane + i] = b / (255f * n);
            }
        }
        return in;
    }

    /** Finds the four corners. Works best with a picture about 500-1000 px on the long side. */
    Corners find(int[] px, int w, int h) {
        int[] s = new int[3];
        float[] heat = run(input(px, w, h), s);
        int C = s[0], H = s[1], W = s[2];
        float[] corners = new float[8];
        float[] conf = new float[4];
        for (int c = 0; c < Math.min(4, C); c++) {
            int base = c * H * W;
            int best = 0;
            for (int k = 1; k < H * W; k++) if (heat[base + k] > heat[base + best]) best = k;
            float peak = heat[base + best];
            int by = best / W, bx = best % W;
            // centre of the bright spot around the peak, for a position finer than one cell
            double sx = 0, sy = 0, sum = 0;
            for (int yy = Math.max(0, by - 3); yy <= Math.min(H - 1, by + 3); yy++) {
                for (int xx = Math.max(0, bx - 3); xx <= Math.min(W - 1, bx + 3); xx++) {
                    float v = heat[base + yy * W + xx];
                    if (v < peak * 0.5f) continue;
                    sx += v * xx;
                    sy += v * yy;
                    sum += v;
                }
            }
            float hx = (float) (sum > 0 ? sx / sum : bx), hy = (float) (sum > 0 ? sy / sum : by);
            float nx = ((hx + 0.5f) / W * w - 0.5f) / Math.max(1, w - 1);
            float ny = ((hy + 0.5f) / H * h - 0.5f) / Math.max(1, h - 1);
            corners[c * 2] = Math.max(0f, Math.min(1f, nx));
            corners[c * 2 + 1] = Math.max(0f, Math.min(1f, ny));
            conf[c] = peak;
        }
        return new Corners(corners, conf);
    }
}
