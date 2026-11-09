package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The main page finder (DocAligner FastViT-SA24 in ONNX Runtime) with smart edge snapping.
 * When the system property pdfscan.eval points at a folder of photos (NN.jpg plus meta.json with
 * the true corners), it also prints the error for each photo and stage.
 */
public class DocNetTest {
    private static DocNet net;

    @BeforeClass
    public static void load() throws Exception {
        net = new DocNet(new File("../app/src/main/assets/" + "docaligner_fastvit_sa24.onnx").getAbsolutePath(), 2);
    }

    private static int[] pixels(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    @Test
    public void letterboxKeepsTheShape() {
        float[] f = DocNet.fit(600, 800);
        assertEquals(0.32f, f[0], 1e-6f);
        assertEquals(32f, f[1], 0f);   // 192 wide, centred
        assertEquals(0f, f[2], 0f);
    }

    @Test
    public void findsThePage() throws Exception {
        BufferedImage img;
        try (InputStream in = DocNetTest.class.getResourceAsStream("/fixtures/page-photo.png")) {
            img = ImageIO.read(in);
        }
        int w = img.getWidth(), h = img.getHeight();
        int[] px = pixels(img);
        float[] expect = {200 / 599f, 150 / 799f, 500 / 599f, 250 / 799f, 400 / 599f, 700 / 799f, 75 / 599f, 600 / 799f};
        PageNet.Corners raw = net.find(px, w, h);
        assertTrue("sure of every corner", raw.minConfidence() > 0.7f);
        float[] c = PageFind.corners(net, px, w, h);
        for (int i = 0; i < 8; i++) assertEquals("corner value " + i, expect[i], c[i], 0.008);
    }

    @Test
    public void emptyDeskHasNoPage() {
        int[] flat = new int[400 * 300];
        java.util.Arrays.fill(flat, 0xFF7A6050);
        assertNull(PageFind.byNetwork(net, flat, 400, 300, PageFind.MIN_CONFIDENCE));
    }

    @Test
    public void evaluation() throws Exception {
        String dir = System.getProperty("pdfscan.eval");
        if (dir == null) return;
        String meta = new String(Files.readAllBytes(new File(dir, "meta.json").toPath()), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?").matcher(meta);
        java.util.List<Double> v = new java.util.ArrayList<>();
        while (m.find()) v.add(Double.parseDouble(m.group()));
        int count = v.size() / 8;
        String[] names = {"ai", "ai+snap", "classic+snap", "pagefind"};
        double[][] err = new double[4][count];
        for (int k = 0; k < count; k++) {
            BufferedImage img = ImageIO.read(new File(dir, String.format("%02d.jpg", k)));
            int w = img.getWidth(), h = img.getHeight();
            int[] px = pixels(img);
            float[][] res = new float[4][];
            PageNet.Corners rc = net.find(px, w, h);
            res[0] = rc.corners;
            res[1] = ScanMath.refineCorners(px, w, h, res[0], PageFind.AI_REACH);
            res[2] = PageFind.corners(null, px, w, h);
            res[3] = PageFind.corners(net, px, w, h);
            StringBuilder line = new StringBuilder(String.format("%02d conf %.2f", k, rc.minConfidence()));
            for (int s = 0; s < 4; s++) {
                double e = 0;
                if (res[s] == null) e = 9999;
                else for (int i = 0; i < 4; i++) {
                    double dx = res[s][i * 2] * (w - 1) - v.get(k * 8 + i * 2), dy = res[s][i * 2 + 1] * (h - 1) - v.get(k * 8 + i * 2 + 1);
                    e = Math.max(e, Math.hypot(dx, dy));
                }
                err[s][k] = e;
                line.append(String.format("  %s %5.1f", names[s], e));
            }
            System.out.println(line);
        }
        for (int s = 0; s < 4; s++) {
            double[] e = err[s].clone();
            java.util.Arrays.sort(e);
            int bad = 0;
            for (double x : e) if (x > 20) bad++;
            System.out.printf("%-13s median %.1f px  p90 %.1f px  over 20 px: %d of %d%n", names[s], e[e.length / 2], e[(int) (e.length * 0.9)], bad, e.length);
        }
    }
}
