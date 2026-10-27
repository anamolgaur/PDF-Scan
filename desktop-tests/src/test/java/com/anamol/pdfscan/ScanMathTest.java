package com.anamol.pdfscan;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import org.junit.BeforeClass;
import org.junit.Test;

/** Checks page detection, corner rotation and the scan filter on a synthetic photo. */
public class ScanMathTest {
    private static final int W = 1200;
    private static final int H = 1600;
    private static final int[] XS = {180, 1010, 1080, 120};
    private static final int[] YS = {150, 230, 1450, 1380};
    private static BufferedImage photo;

    /** A tilted page with text on a dark desk, with a soft shadow over the lower left. */
    @BeforeClass
    public static void drawPhoto() {
        System.setProperty("java.awt.headless", "true");
        photo = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = photo.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(70, 55, 45));
        g.fillRect(0, 0, W, H);
        Path2D page = new Path2D.Float();
        page.moveTo(XS[0], YS[0]);
        for (int i = 1; i < 4; i++) page.lineTo(XS[i], YS[i]);
        page.closePath();
        g.setColor(new Color(225, 220, 205));
        g.fill(page);
        g.setClip(page);
        g.setColor(new Color(40, 40, 40));
        g.setFont(new Font("SansSerif", Font.PLAIN, 34));
        for (int line = 0; line < 22; line++) {
            g.drawString("The quick brown fox jumps over the lazy dog " + line, 240, 330 + line * 48);
        }
        g.setPaint(new GradientPaint(0, 900, new Color(0, 0, 0, 0), 0, 1500, new Color(0, 0, 0, 110)));
        g.fillRect(0, 900, 700, 700);
        g.dispose();
    }

    private static int[] preview(int sw, int sh) {
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(photo, 0, 0, sw, sh, null);
        g.dispose();
        return small.getRGB(0, 0, sw, sh, null, 0, sw);
    }

    @Test
    public void detectsTiltedPageCorners() {
        int sw = 240;
        int sh = 320;
        float[] c = ScanMath.detectCorners(preview(sw, sh), sw, sh);
        assertNotNull("page should be found", c);
        for (int i = 0; i < 4; i++) {
            float ex = XS[i] / (float) W;
            float ey = YS[i] / (float) H;
            assertTrue("corner " + i + " x", Math.abs(c[2 * i] - ex) < 0.03f);
            assertTrue("corner " + i + " y", Math.abs(c[2 * i + 1] - ey) < 0.03f);
        }
    }

    @Test
    public void plainImageGivesNoPage() {
        int[] flat = new int[240 * 320];
        Arrays.fill(flat, 0xFFEEEEEE);
        assertNull(ScanMath.detectCorners(flat, 240, 320));
    }

    @Test
    public void fourRotationsReturnToStart() {
        float[] c = {0.1f, 0.2f, 0.8f, 0.15f, 0.9f, 0.85f, 0.05f, 0.9f};
        float[] r = c;
        for (int i = 0; i < 4; i++) r = ScanMath.rotateCornersCw(r);
        assertArrayEquals(c, r, 1e-5f);
        assertArrayEquals(ScanMath.FULL, ScanMath.rotateCornersCw(ScanMath.FULL), 1e-5f);
    }

    @Test
    public void scanFilterWhitensShadowedPaperAndKeepsTextDark() {
        int[] px = photo.getRGB(0, 0, W, H, null, 0, W);
        ScanMath.scanFilter(px, W, H);
        assertTrue("lit paper white", ScanMath.luma(px[260 * W + 900]) >= 235);
        assertTrue("shadowed paper white", ScanMath.luma(px[1330 * W + 220]) >= 235);
        int darkest = 255;
        for (int x = 240; x < 700; x++) darkest = Math.min(darkest, ScanMath.luma(px[318 * W + x]));
        assertTrue("text stays dark", darkest <= 60);
    }

    @Test
    public void strictDetectionAcceptsARealPage() {
        assertNotNull("tilted page on a desk", ScanMath.detectPage(preview(240, 320), 240, 320));
    }

    /** Things that are not a sheet of paper: a room-like scene, a blob, a lit wall, noise. */
    @Test
    public void strictDetectionRejectsNonPages() {
        int sw = 240, sh = 320;
        // bright wall filling most of the frame with a dark object in front
        BufferedImage wall = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = wall.createGraphics();
        g.setColor(new Color(200, 200, 195));
        g.fillRect(0, 0, sw, sh);
        g.setColor(new Color(60, 50, 40));
        g.fillRect(60, 180, 120, 140);
        g.dispose();
        assertNull("wall", ScanMath.detectPage(wall.getRGB(0, 0, sw, sh, null, 0, sw), sw, sh));
        // a round bright blob (lamp, face, plate)
        BufferedImage blob = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        g = blob.createGraphics();
        g.setColor(new Color(50, 50, 50));
        g.fillRect(0, 0, sw, sh);
        g.setColor(new Color(230, 230, 230));
        g.fillOval(30, 60, 180, 200);
        g.dispose();
        assertNull("blob", ScanMath.detectPage(blob.getRGB(0, 0, sw, sh, null, 0, sw), sw, sh));
        // busy scene: random bright and dark patches
        java.util.Random r = new java.util.Random(4);
        BufferedImage busy = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        g = busy.createGraphics();
        for (int i = 0; i < 300; i++) {
            int v = r.nextInt(256);
            g.setColor(new Color(v, v, v));
            g.fillRect(r.nextInt(sw), r.nextInt(sh), 10 + r.nextInt(60), 10 + r.nextInt(60));
        }
        g.dispose();
        assertNull("busy scene", ScanMath.detectPage(busy.getRGB(0, 0, sw, sh, null, 0, sw), sw, sh));
        // a thin bright strip (a ruler or a window edge)
        BufferedImage strip = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        g = strip.createGraphics();
        g.setColor(new Color(40, 40, 40));
        g.fillRect(0, 0, sw, sh);
        g.setColor(new Color(235, 235, 235));
        g.fillRect(100, 10, 70, 300);
        g.dispose();
        assertNull("strip", ScanMath.detectPage(strip.getRGB(0, 0, sw, sh, null, 0, sw), sw, sh));
    }

    /** White page on a white desk under uneven light: brightness alone can't separate them. */
    private static int[] whiteOnWhite(int sw, int sh) {
        BufferedImage img = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        java.util.Random r = new java.util.Random(7);
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int v = 190 + (int) (50.0 * y / sh) + r.nextInt(7) - 3;  // desk, lighter towards the bottom
                img.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Path2D page = new Path2D.Float();
        page.moveTo(40, 45);
        page.lineTo(200, 55);
        page.lineTo(205, 280);
        page.lineTo(35, 270);
        page.closePath();
        g.setColor(new Color(150, 150, 150, 120));  // soft shadow along the edge
        g.setStroke(new java.awt.BasicStroke(3f));
        g.translate(2, 2);
        g.draw(page);
        g.translate(-2, -2);
        g.setPaint(new GradientPaint(0, 45, new Color(232, 232, 230), 0, 280, new Color(222, 222, 220)));
        g.fill(page);
        g.setColor(new Color(60, 60, 60));
        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g.setClip(page);
        for (int line = 0; line < 18; line++) g.drawString("lorem ipsum dolor sit amet consectetur", 55, 75 + line * 11);
        g.dispose();
        return img.getRGB(0, 0, sw, sh, null, 0, sw);
    }

    @Test
    public void findsWhitePageOnWhiteDeskByItsOutline() {
        float[] c = ScanMath.detectPage(whiteOnWhite(240, 320), 240, 320);
        assertNotNull("white page on white desk", c);
        float[] expect = {40 / 239f, 45 / 319f, 200 / 239f, 55 / 319f, 205 / 239f, 280 / 319f, 35 / 239f, 270 / 319f};
        for (int i = 0; i < 8; i++) assertTrue("corner value " + i + " = " + c[i], Math.abs(c[i] - expect[i]) < 0.04f);
    }

    @Test
    public void findsTheSpineOfAnOpenBook() {
        int w = 400, h = 300;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double d = Math.abs(x - 228) / 18.0;
                int v = (int) (235 - 70 * Math.exp(-d * d));  // shadow in the fold at x = 228
                px[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        assertTrue(Math.abs(ScanMath.gutter(px, w, h) - 228 / 399f) < 0.01f);
        int[] flat = new int[w * h];
        Arrays.fill(flat, 0xFFEEEEEE);
        assertTrue("no fold: middle", Math.abs(ScanMath.gutter(flat, w, h) - 0.5f) < 1e-6);
    }

    @Test
    public void splitHalvesShareTheFold() {
        float[][] s = ScanMath.splitQuad(null, 0.5f);
        assertArrayEquals(new float[]{0, 0, 0.5f, 0, 0.5f, 1, 0, 1}, s[0], 1e-6f);
        assertArrayEquals(new float[]{0.5f, 0, 1, 0, 1, 1, 0.5f, 1}, s[1], 1e-6f);
    }

    @Test
    public void flattensABowedLineNearTheSpine() {
        int w = 200, h = 200;
        int[] px = new int[w * h];
        Arrays.fill(px, 0xFFFFFFFF);
        // a text line that bows downwards towards the spine on the right: y = 100 + 16 t^2
        float amount = 1f;
        for (int x = 0; x < w; x++) {
            float t = x / (float) (w - 1);
            int y = Math.round(100 + amount * 0.08f * h * t * t);
            px[y * w + x] = 0xFF000000;
        }
        int[] out = ScanMath.flattenCurve(px, w, h, amount, false);
        for (int x = 0; x < w; x += 20) {
            int darkest = 0;
            int best = 255;
            for (int y = 0; y < h; y++) {
                int v = out[y * w + x] & 255;
                if (v < best) {
                    best = v;
                    darkest = y;
                }
            }
            assertTrue("column " + x + " line at " + darkest, Math.abs(darkest - 100) <= 1);
        }
    }
}
