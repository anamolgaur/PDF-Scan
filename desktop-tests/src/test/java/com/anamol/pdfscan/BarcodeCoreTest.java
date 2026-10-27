package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.junit.Test;

/** Reads codes the way the camera sees them: grey, with a margin, upright or turned. */
public class BarcodeCoreTest {
    private static byte[] render(BitMatrix m, int w, int h) {
        byte[] lum = new byte[w * h];
        java.util.Arrays.fill(lum, (byte) 210);
        int ox = (w - m.getWidth()) / 2, oy = (h - m.getHeight()) / 2;
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                lum[(oy + y) * w + ox + x] = (byte) (m.get(x, y) ? 30 : 230);
            }
        }
        return lum;
    }

    @Test
    public void readsQrCodes() throws Exception {
        String text = "https://github.com/anamolgaur/PDF-Scan";
        BitMatrix m = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300);
        assertEquals(text, BarcodeCore.find(render(m, 640, 480), 640, 480));
    }

    @Test
    public void readsBarcodesEitherWayRound() throws Exception {
        String ean = "4006381333931";
        BitMatrix m = new MultiFormatWriter().encode(ean, BarcodeFormat.EAN_13, 400, 120);
        assertEquals(ean, BarcodeCore.find(render(m, 640, 480), 640, 480));
        // the same barcode upright in the sensor picture (held level on a portrait phone)
        BitMatrix t = new BitMatrix(m.getHeight(), m.getWidth());
        for (int y = 0; y < m.getHeight(); y++) for (int x = 0; x < m.getWidth(); x++) if (m.get(x, y)) t.set(y, x);
        assertEquals(ean, BarcodeCore.find(render(t, 480, 640), 480, 640));
    }

    @Test
    public void nothingInABlankPicture() {
        byte[] lum = new byte[320 * 240];
        java.util.Arrays.fill(lum, (byte) 128);
        assertNull(BarcodeCore.find(lum, 320, 240));
    }
}
