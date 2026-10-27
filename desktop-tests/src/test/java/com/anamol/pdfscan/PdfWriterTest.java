package com.anamol.pdfscan;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;

import org.junit.Test;

/**
 * Structure checks for the PDF writer. When the system property pdfscan.out is set, the test
 * also writes sample files there so they can be checked with qpdf, pdftotext and pikepdf.
 */
public class PdfWriterTest {
    private static final int W = 800;
    private static final int H = 1100;

    private static BufferedImage pageImage() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 36));
        g.drawString("Invoice 4521", 80, 140);
        g.drawString("Total due 12,400", 80, 220);
        g.dispose();
        return img;
    }

    private static List<PdfWriter.Word> words() {
        return Arrays.asList(
                new PdfWriter.Word("Invoice", 80, 110, 125, 38),
                new PdfWriter.Word("4521", 215, 110, 80, 38),
                new PdfWriter.Word("Total", 80, 190, 85, 38),
                new PdfWriter.Word("due", 175, 190, 60, 38),
                new PdfWriter.Word("12,400", 245, 190, 110, 38),
                new PdfWriter.Word("café (“quotes”) \\ 中", 80, 270, 300, 38),
                new PdfWriter.Word("हिंदी", 80, 330, 120, 38),
                new PdfWriter.Word("中文文档", 220, 330, 150, 38),
                new PdfWriter.Word("Ελληνικά", 400, 330, 160, 38),
                new PdfWriter.Word("தமிழ்", 580, 330, 120, 38));
    }

    private static byte[] build(boolean bilevel, String password) throws IOException {
        BufferedImage img = pageImage();
        PdfWriter.Page p;
        if (bilevel) {
            p = PdfWriter.Page.bilevel(img.getRGB(0, 0, W, H, null, 0, W), W, H, 128);
        } else {
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", jpg);
            p = PdfWriter.Page.jpeg(jpg.toByteArray(), W, H);
        }
        p.words = new ArrayList<>(words());
        p.textW = W;
        p.textH = H;
        PdfWriter.Options o = new PdfWriter.Options();
        o.title = "Test – invoice";
        o.password = password;
        o.random = new SecureRandom(new byte[]{1, 2, 3});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.write(Arrays.asList(p, p), o, out);
        byte[] pdf = out.toByteArray();
        String dir = System.getProperty("pdfscan.out");
        if (dir != null) {
            String name = (bilevel ? "bilevel" : "jpeg") + (password != null ? "-encrypted" : "") + ".pdf";
            try (FileOutputStream f = new FileOutputStream(new File(dir, name))) {
                f.write(pdf);
            }
        }
        return pdf;
    }

    /** Checks every xref offset points at "N 0 obj". */
    private static void checkXref(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        int start = Integer.parseInt(s.substring(s.lastIndexOf("startxref") + 10, s.lastIndexOf("%%EOF")).trim());
        assertTrue(s.startsWith("xref", start));
        String[] lines = s.substring(start).split("\n");
        int count = Integer.parseInt(lines[1].split(" ")[1]);
        for (int i = 1; i < count; i++) {
            long off = Long.parseLong(lines[2 + i].substring(0, 10));
            assertTrue("object " + i + " at its offset", s.startsWith(i + " 0 obj", (int) off));
        }
    }

    @Test
    public void plainPdfHasValidXrefAndTextLayer() throws Exception {
        byte[] pdf = build(false, null);
        checkXref(pdf);
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("%PDF-1.7"));
        assertTrue(s.contains("/Count 2"));
        assertTrue(s.contains("/DCTDecode"));
        assertTrue(!s.contains("/Encrypt"));
    }

    @Test
    public void bilevelPageIsSmall() throws Exception {
        byte[] small = build(true, null);
        byte[] jpeg = build(false, null);
        checkXref(small);
        System.out.println("2-page sizes: jpeg " + jpeg.length + " bytes, 1-bit " + small.length + " bytes");
        assertTrue(small.length < jpeg.length);
        assertTrue(new String(small, StandardCharsets.ISO_8859_1).contains("/BitsPerComponent 1"));
    }

    @Test
    public void encryptedPdfHasR6Dictionary() throws Exception {
        byte[] pdf = build(false, "s3cret pass");
        checkXref(pdf);
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(s.contains("/V 5 /R 6"));
        assertTrue(s.contains("/CFM /AESV3"));
        assertTrue("words are not readable in the file", !s.contains("Invoice"));
    }

    @Test
    public void streamEncryptionRoundTrips() throws Exception {
        PdfWriter.Crypto c = new PdfWriter.Crypto("pw", new SecureRandom());
        byte[] msg = "hello stream".getBytes(StandardCharsets.UTF_8);
        byte[] enc = c.encrypt(msg);
        Cipher d = Cipher.getInstance("AES/CBC/PKCS5Padding");
        d.init(Cipher.DECRYPT_MODE, new SecretKeySpec(c.fileKey, "AES"),
                new IvParameterSpec(Arrays.copyOf(enc, 16)));
        assertArrayEquals(msg, d.doFinal(Arrays.copyOfRange(enc, 16, enc.length)));
        assertEquals(48, c.u.length);
        assertEquals(32, c.ue.length);
        assertEquals(16, c.perms.length);
    }

    @Test
    public void bilevelBitsDecodeBack() throws Exception {
        int[] px = {0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000};
        PdfWriter.Page p = PdfWriter.Page.bilevel(px, 9, 1, 128);
        Inflater inf = new Inflater();
        inf.setInput(p.data);
        byte[] out = new byte[2];
        assertEquals(2, inf.inflate(out));
        assertEquals((byte) 0b10100111, out[0]);
        assertEquals((byte) 0b00000000, out[1]);
    }

    /** Words in any script come back out of the PDF's text layer, plain and encrypted. */
    @Test
    public void textLayerKeepsEveryScript() throws Exception {
        for (String pw : new String[]{null, ""}) {
            String text = String.join("\n", PdfText.extract(build(false, pw)));
            for (String w : new String[]{"Invoice", "हिंदी", "中文文档", "Ελληνικά", "தமிழ்", "café"}) {
                assertTrue(w + " in: " + text, text.contains(w));
            }
        }
        assertTrue(PdfWriter.latin("Total due"));
        assertTrue(!PdfWriter.latin("हिंदी"));
    }
}
