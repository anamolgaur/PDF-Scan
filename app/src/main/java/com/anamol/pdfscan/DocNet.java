package com.anamol.pdfscan;

import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * The main page finder: DocAligner's most accurate corner model (FastViT-SA24 heatmap model,
 * Apache-2.0, by DocsaidLab: github.com/DocsaidLab/DocAligner), run with ONNX Runtime on the
 * phone's processor. Nothing is downloaded or sent anywhere.
 *
 * The photo is not squashed into the network's square input: it is scaled to fit and the rest is
 * padded with black, so the page keeps its real shape. (Measured on 40 test photos, padding cut the
 * badly-placed corners from 12 photos to 1.)
 */
final class DocNet implements PageFind.Detector {
    static final int SIZE = 256;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String input;

    DocNet(String modelPath, int threads) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) o.setIntraOpNumThreads(threads);
        session = env.createSession(modelPath, o);
        input = session.getInputNames().iterator().next();
    }

    /** Where a photo of w x h lands inside the square input: {scale, offsetX, offsetY}. */
    static float[] fit(int w, int h) {
        float s = SIZE / (float) Math.max(w, h);
        int nw = Math.max(1, Math.round(w * s)), nh = Math.max(1, Math.round(h * s));
        return new float[]{s, (SIZE - nw) / 2, (SIZE - nh) / 2, nw, nh};
    }

    /** RGB 0..1, 3 x 256 x 256, photo centred and scaled to fit, black around it. */
    static float[] letterbox(int[] px, int w, int h) {
        float[] f = fit(w, h);
        int ox = (int) f[1], oy = (int) f[2], nw = (int) f[3], nh = (int) f[4];
        float[] in = new float[3 * SIZE * SIZE];
        int plane = SIZE * SIZE;
        for (int y = 0; y < nh; y++) {
            int ya = y * h / nh, yb = Math.max(ya + 1, (y + 1) * h / nh);
            for (int x = 0; x < nw; x++) {
                int xa = x * w / nw, xb = Math.max(xa + 1, (x + 1) * w / nw);
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
                int i = (oy + y) * SIZE + ox + x;
                in[i] = r / (255f * n);
                in[plane + i] = g / (255f * n);
                in[2 * plane + i] = b / (255f * n);
            }
        }
        return in;
    }

    @Override
    public PageNet.Corners find(int[] px, int w, int h) {
        float[] in = letterbox(px, w, h);
        float[][][][] heat;
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result r = session.run(Collections.singletonMap(input, t))) {
            heat = (float[][][][]) r.get(0).getValue();
        } catch (OrtException e) {
            throw new IllegalStateException(e);
        }
        return decode(heat[0], w, h);
    }

    /** Peak of each corner's heatmap, refined to a fraction of a cell, back in photo coordinates. */
    static PageNet.Corners decode(float[][][] heat, int w, int h) {
        float[] f = fit(w, h);
        float s = f[0], ox = f[1], oy = f[2];
        float[] corners = new float[8];
        float[] conf = new float[4];
        for (int c = 0; c < 4; c++) {
            float[][] m = heat[c];
            int H = m.length, W = m[0].length;
            int by = 0, bx = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (m[y][x] > m[by][bx]) {
                by = y;
                bx = x;
            }
            float peak = m[by][bx];
            double sx = 0, sy = 0, sum = 0;
            for (int y = Math.max(0, by - 3); y <= Math.min(H - 1, by + 3); y++) {
                for (int x = Math.max(0, bx - 3); x <= Math.min(W - 1, bx + 3); x++) {
                    float v = m[y][x];
                    if (v < peak * 0.5f) continue;
                    sx += v * x;
                    sy += v * y;
                    sum += v;
                }
            }
            float hx = (float) (sum > 0 ? sx / sum : bx), hy = (float) (sum > 0 ? sy / sum : by);
            float ix = (hx + 0.5f) * SIZE / W - 0.5f, iy = (hy + 0.5f) * SIZE / H - 0.5f;  // input pixels
            float x = (ix - ox) / s, y = (iy - oy) / s;                                   // photo pixels
            corners[c * 2] = Math.max(0f, Math.min(1f, x / Math.max(1, w - 1)));
            corners[c * 2 + 1] = Math.max(0f, Math.min(1f, y / Math.max(1, h - 1)));
            conf[c] = peak;
        }
        return new PageNet.Corners(corners, conf);
    }
}
