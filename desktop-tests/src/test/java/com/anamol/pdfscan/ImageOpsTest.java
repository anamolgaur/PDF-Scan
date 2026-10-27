package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.junit.Test;

/** Filters, glare reduction, sliders, sharpening and the cleanup eraser. */
public class ImageOpsTest {
    private static final int W = 600;
    private static final int H = 400;

    /** Off-white paper with dark text, optionally with a bright glare patch over the text. */
    private static int[] page(boolean glare) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(200, 190, 170));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(60, 50, 70));
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        for (int i = 0; i < 8; i++) g.drawString("Glare test line number " + i, 30, 50 + i * 42);
        if (glare) {
            // a washed-out highlight: brighter paper and lighter ink
            g.setPaint(new RadialGradientPaint(300, 200, 150, new float[]{0f, 1f},
                    new Color[]{new Color(255, 255, 255, 170), new Color(255, 255, 255, 0)}));
            g.fillRect(0, 0, W, H);
        }
        g.dispose();
        return img.getRGB(0, 0, W, H, null, 0, W);
    }

    private static double mean(int[] px) {
        double s = 0;
        for (int c : px) s += ImageOps.luma(c);
        return s / px.length;
    }

    private static double std(int[] px) {
        double m = mean(px);
        double s = 0;
        for (int c : px) {
            double d = ImageOps.luma(c) - m;
            s += d * d;
        }
        return Math.sqrt(s / px.length);
    }

    /** Ink contrast inside the glare centre: paper level minus darkest ink. */
    private static int contrastAtCentre(int[] px) {
        int min = 255;
        int max = 0;
        for (int y = 170; y < 230; y++) {
            for (int x = 220; x < 380; x++) {
                int v = ImageOps.luma(px[y * W + x]);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        return max - min;
    }

    @Test
    public void everyFilterRunsAndKeepsSize() {
        for (String f : ImageOps.FILTERS) {
            int[] px = page(false);
            ImageOps.Adjust a = new ImageOps.Adjust();
            a.filter = f;
            ImageOps.apply(px, W, H, a);
            assertEquals(W * H, px.length);
            for (int c : px) assertEquals("opaque output for " + f, 0xFF, (c >>> 24));
        }
    }

    @Test
    public void blackAndWhiteIsTwoTone() {
        int[] px = page(false);
        ImageOps.Adjust a = new ImageOps.Adjust();
        a.filter = ImageOps.BW;
        ImageOps.apply(px, W, H, a);
        for (int c : px) assertTrue(c == 0xFFFFFFFF || c == 0xFF000000);
    }

    @Test
    public void autoColourWhitensPaperAndKeepsInkDark() {
        int[] px = page(false);
        ImageOps.Adjust a = new ImageOps.Adjust();
        a.filter = ImageOps.AUTO;
        ImageOps.apply(px, W, H, a);
        assertTrue("paper near white", ImageOps.luma(px[390 * W + 590]) > 235);
        int darkest = 255;
        for (int x = 30; x < 300; x++) darkest = Math.min(darkest, ImageOps.luma(px[42 * W + x]));
        assertTrue("ink dark", darkest < 110);
    }

    @Test
    public void glareReductionRestoresInkContrast() {
        int[] before = page(true);
        int[] after = page(true);
        ImageOps.Adjust a = new ImageOps.Adjust();
        a.glare = true;
        ImageOps.apply(after, W, H, a);
        int c0 = contrastAtCentre(before);
        int c1 = contrastAtCentre(after);
        System.out.println("ink contrast under glare: before " + c0 + ", after " + c1);
        assertTrue("contrast under glare improves", c1 > c0 * 1.4);
    }

    @Test
    public void brightnessAndContrastSliders() {
        int[] base = page(false);
        int[] bright = base.clone();
        ImageOps.brightnessContrast(bright, 0.5f, 0f);
        assertTrue(mean(bright) > mean(base) + 30);
        int[] punchy = base.clone();
        ImageOps.brightnessContrast(punchy, 0f, 0.5f);
        assertTrue(std(punchy) > std(base) * 1.5);
        int[] flat = base.clone();
        ImageOps.brightnessContrast(flat, 0f, -0.5f);
        assertTrue(std(flat) < std(base) * 0.7);
    }

    @Test
    public void sharpenIncreasesEdgeContrast() {
        int[] base = page(false);
        int[] sharp = base.clone();
        ImageOps.sharpen(sharp, W, H, 1f);
        assertTrue(std(sharp) > std(base));
    }

    @Test
    public void eraserFillsSmudgeWithPaperColour() {
        int[] px = page(false);
        boolean[] mask = new boolean[W * H];
        // a dark smudge in an empty margin area
        for (int y = 360; y < 390; y++) {
            for (int x = 400; x < 470; x++) {
                px[y * W + x] = 0xFF202020;
                mask[y * W + x] = true;
            }
        }
        ImageOps.fillWithPaper(px, W, H, mask);
        int c = px[375 * W + 435];
        assertTrue("red near paper", Math.abs(ImageOps.r(c) - 200) < 12);
        assertTrue("green near paper", Math.abs(ImageOps.g(c) - 190) < 12);
        assertTrue("blue near paper", Math.abs(ImageOps.b(c) - 170) < 12);
    }
}
