package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The page-corner network, run in plain Java, against the original model run in ONNX Runtime
 * (page-photo.json was written by onnxruntime from the same picture).
 */
public class PageNetTest {
    private static PageNet net;
    private static int[] px;
    private static int w, h;
    private static double[] peaks;
    private static double[] sums;
    private static double[] expect;

    private static double[] numbers(String json, String key) {
        int at = json.indexOf("\"" + key + "\"");
        int start = json.indexOf('[', at);
        int depth = 0, end = start;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '[') depth++;
            if (json.charAt(i) == ']' && --depth == 0) {
                end = i;
                break;
            }
        }
        Matcher m = Pattern.compile("-?\\d+(\\.\\d+)?(e-?\\d+)?").matcher(json.substring(start, end));
        java.util.List<Double> v = new java.util.ArrayList<>();
        while (m.find()) v.add(Double.parseDouble(m.group()));
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    @BeforeClass
    public static void setUp() throws IOException {
        try (InputStream in = new FileInputStream(new File("../app/src/main/assets/pagenet.bin"))) {
            net = PageNet.load(in);
        }
        BufferedImage img;
        try (InputStream in = PageNetTest.class.getResourceAsStream("/fixtures/page-photo.png")) {
            img = ImageIO.read(in);
        }
        w = img.getWidth();
        h = img.getHeight();
        px = img.getRGB(0, 0, w, h, null, 0, w);
        String json;
        try (InputStream in = PageNetTest.class.getResourceAsStream("/fixtures/page-photo.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        peaks = numbers(json, "heat_peaks");
        sums = numbers(json, "checksum");
        expect = numbers(json, "expect");
    }

    @Test
    public void matchesTheOriginalModel() {
        int[] s = new int[3];
        float[] heat = net.run(PageNet.input(px, w, h), s);
        assertEquals(4, s[0]);
        assertEquals(128, s[1]);
        assertEquals(128, s[2]);
        for (int c = 0; c < 4; c++) {
            int base = c * 128 * 128, best = 0;
            double sum = 0;
            for (int k = 0; k < 128 * 128; k++) {
                sum += heat[base + k];
                if (heat[base + k] > heat[base + best]) best = k;
            }
            assertTrue("peak x " + c, Math.abs(best % 128 - peaks[c * 3]) <= 1);
            assertTrue("peak y " + c, Math.abs(best / 128 - peaks[c * 3 + 1]) <= 1);
            assertEquals("confidence " + c, peaks[c * 3 + 2], heat[base + best], 0.02);
            assertEquals("heatmap total " + c, sums[c], sum, sums[c] * 0.03);
        }
    }

    @Test
    public void findsTheCorners() {
        long t = System.nanoTime();
        PageNet.Corners c = net.find(px, w, h);
        System.out.println("PageNet: " + (System.nanoTime() - t) / 1_000_000 + " ms");
        assertTrue(c.minConfidence() > 0.8f);
        for (int i = 0; i < 8; i++) {
            assertEquals("corner value " + i, expect[i], c.corners[i], 0.012);
        }
    }

    @Test
    public void halfFloats() {
        assertEquals(1f, PageNet.halfToFloat((short) 0x3C00), 0f);
        assertEquals(-2f, PageNet.halfToFloat((short) 0xC000), 0f);
        assertEquals(0.000061035156f, PageNet.halfToFloat((short) 0x0400), 1e-9f);
    }

    @Test
    public void networkFirstThenSnappedToTheEdges() {
        float[] c = PageFind.corners(net, px, w, h);
        for (int i = 0; i < 8; i++) assertEquals("corner value " + i, expect[i], c[i], 0.01);
    }

    @Test
    public void classicDetectorIsTheBackup() {
        float[] c = PageFind.corners(null, px, w, h);  // network off
        for (int i = 0; i < 8; i++) assertEquals("corner value " + i, expect[i], c[i], 0.015);
    }

    @Test
    public void noPageNoCorners() {
        int[] flat = new int[400 * 300];
        java.util.Arrays.fill(flat, 0xFF7A6050);
        assertEquals(null, PageFind.byNetwork(net, flat, 400, 300, PageFind.MIN_CONFIDENCE));
        assertEquals(null, PageFind.corners(net, flat, 400, 300));
    }
}
