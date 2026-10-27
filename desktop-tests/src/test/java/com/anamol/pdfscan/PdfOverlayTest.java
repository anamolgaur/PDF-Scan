package com.anamol.pdfscan;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Saving an edited PDF as an incremental update: the original bytes stay as they are, pages come
 * out in the new order with their own text, and the mark-up is laid on top. When the system
 * property pdfscan.out is set, the results are also written there for qpdf / pdftotext checks.
 */
public class PdfOverlayTest {
    private static final String[] WORDS = {"Alpha page", "Bravo page", "Charlie page"};

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String content(String text) {
        return "BT /F1 24 Tf 72 700 Td (" + text + ") Tj ET\n";
    }

    /** A plain PDF with a classic cross-reference table; Resources and Rotate are inherited. */
    static byte[] classicPdf() throws IOException {
        List<String> objs = new ArrayList<>();
        objs.add("<< /Type /Catalog /Pages 2 0 R >>");
        objs.add("<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 /MediaBox [0 0 612 792] "
                + "/Resources << /Font << /F1 9 0 R >> >> >>");
        objs.add("<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>");
        objs.add("<< /Type /Page /Parent 2 0 R /Contents [7 0 R] /Rotate 90 >>");
        objs.add("<< /Type /Page /Parent 2 0 R /Contents 8 0 R /CropBox [10 10 600 780] >>");
        for (String w : WORDS) {
            String c = content(w);
            objs.add("<< /Length " + c.length() + " >>\nstream\n" + c + "endstream");
        }
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("%PDF-1.4\n%âãÏÓ\n"));
        long[] at = new long[objs.size()];
        for (int i = 0; i < objs.size(); i++) {
            at[i] = out.size();
            out.write(ascii((i + 1) + " 0 obj\n" + objs.get(i) + "\nendobj\n"));
        }
        long xref = out.size();
        StringBuilder sb = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f\r\n");
        for (long a : at) sb.append(String.format("%010d 00000 n\r\n", a));
        sb.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        out.write(ascii(sb.toString()));
        return out.toByteArray();
    }

    /** A PDF 1.5 file: pages inside an object stream and a cross-reference stream. */
    static byte[] xrefStreamPdf() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("%PDF-1.5\n%âãÏÓ\n"));
        long[] at = new long[12];
        // 1 catalog, 2 pages, 3-5 page (in object stream 10), 6-8 contents, 9 font, 10 objstm, 11 xref
        String[] packed = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 /MediaBox [0 0 595 842] >>",
                "<< /Type /Page /Parent 2 0 R /Contents 6 0 R /Resources << /Font << /F1 9 0 R >> >> >>",
                "<< /Type /Page /Parent 2 0 R /Contents 7 0 R /Resources << /Font << /F1 9 0 R >> >> >>",
                "<< /Type /Page /Parent 2 0 R /Contents 8 0 R /Resources << /Font << /F1 9 0 R >> >> >>",
        };
        for (int i = 0; i < 3; i++) {
            String c = content(WORDS[i]);
            at[6 + i] = out.size();
            out.write(ascii((6 + i) + " 0 obj\n<< /Length " + c.length() + " >>\nstream\n" + c + "endstream\nendobj\n"));
        }
        at[9] = out.size();
        out.write(ascii("9 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"));
        StringBuilder head = new StringBuilder();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < packed.length; i++) {
            head.append(i + 1).append(' ').append(body.length()).append(' ');
            body.append(packed[i]).append('\n');
        }
        String objstm = head + body.toString();
        byte[] z = PdfOverlay.deflate(ascii(objstm));
        at[10] = out.size();
        out.write(ascii("10 0 obj\n<< /Type /ObjStm /N 5 /First " + head.length() + " /Filter /FlateDecode /Length "
                + z.length + " >>\nstream\n"));
        out.write(z);
        out.write(ascii("\nendstream\nendobj\n"));
        at[11] = out.size();
        ByteArrayOutputStream rows = new ByteArrayOutputStream();
        for (int n = 0; n < 12; n++) {
            int type;
            long f2;
            int f3;
            if (n == 0) {
                type = 0;
                f2 = 0;
                f3 = 0xFFFF;
            } else if (n <= 5) {
                type = 2;
                f2 = 10;
                f3 = n - 1;
            } else {
                type = 1;
                f2 = at[n];
                f3 = 0;
            }
            rows.write(type);
            for (int s = 24; s >= 0; s -= 8) rows.write((int) (f2 >> s) & 0xFF);
            rows.write(f3 >> 8);
            rows.write(f3 & 0xFF);
        }
        byte[] r = rows.toByteArray();
        out.write(ascii("11 0 obj\n<< /Type /XRef /Size 12 /W [1 4 2] /Root 1 0 R /Length " + r.length
                + " >>\nstream\n"));
        out.write(r);
        out.write(ascii("\nendstream\nendobj\nstartxref\n" + at[11] + "\n%%EOF\n"));
        return out.toByteArray();
    }

    private static PdfOverlay.Layer stripe(boolean multiply) {
        int w = 40, h = 60;
        byte[] rgb = new byte[w * h * 3];
        byte[] alpha = new byte[w * h];
        for (int y = 10; y < 14; y++) {
            for (int x = 0; x < w; x++) {
                alpha[y * w + x] = (byte) 255;
                rgb[(y * w + x) * 3] = (byte) (multiply ? 0xF2 : 0x20);
                rgb[(y * w + x) * 3 + 1] = (byte) (multiply ? 0xD0 : 0x20);
                rgb[(y * w + x) * 3 + 2] = (byte) (multiply ? 0x4A : 0x80);
            }
        }
        return new PdfOverlay.Layer(w, h, rgb, alpha, multiply);
    }

    private static List<PdfOverlay.PageSpec> edits() {
        PdfOverlay.PageSpec a = new PdfOverlay.PageSpec(2, 0);
        a.layers.add(stripe(false));
        a.layers.add(stripe(true));
        PdfOverlay.PageSpec b = new PdfOverlay.PageSpec(0, 90);
        PdfOverlay.PageSpec c = new PdfOverlay.PageSpec(1, 0);
        c.layers.add(stripe(false));
        PdfOverlay.PageSpec d = new PdfOverlay.PageSpec(0, 0);  // the first page again
        return Arrays.asList(a, b, c, d);
    }

    private static void check(String label, byte[] original) throws IOException {
        assertEquals(3, PdfOverlay.pageCount(original));
        byte[] out = PdfOverlay.apply(original, edits());
        // the original is kept byte for byte at the start
        assertArrayEquals(original, Arrays.copyOf(out, original.length));
        assertEquals(4, PdfOverlay.pageCount(out));
        List<String> text = PdfText.extract(out);
        assertEquals(4, text.size());
        assertTrue(text.get(0), text.get(0).contains("Charlie page"));
        assertTrue(text.get(1), text.get(1).contains("Alpha page"));
        assertTrue(text.get(2), text.get(2).contains("Bravo page"));
        assertTrue(text.get(3), text.get(3).contains("Alpha page"));

        PdfText t = PdfText.open(out);
        List<PdfText.Dict[]> pages = t.pageList();
        assertEquals(90.0, t.numberOf(t.inherited(pages.get(1)[0], "Rotate"), 0), 0);
        // page 2 already had /Rotate 90; nothing added
        assertEquals(label.equals("classic") ? 90.0 : 0.0, t.numberOf(t.inherited(pages.get(2)[0], "Rotate"), 0), 0);
        PdfText.Dict xo = t.dictOf(pages.get(0)[1].get("XObject"));
        assertEquals(2, xo.size());
        assertTrue(t.dictOf(pages.get(0)[1].get("ExtGState")) != null);
        assertTrue(t.dictOf(pages.get(0)[1].get("Font")) != null);

        String dir = System.getProperty("pdfscan.out");
        if (dir != null) {
            Files.write(new File(dir, "overlay-" + label + "-in.pdf").toPath(), original);
            Files.write(new File(dir, "overlay-" + label + "-out.pdf").toPath(), out);
        }
    }

    @Test
    public void classicCrossReferenceTable() throws IOException {
        check("classic", classicPdf());
    }

    @Test
    public void crossReferenceStream() throws IOException {
        check("xrefstream", xrefStreamPdf());
    }

    @Test
    public void unchangedReturnsTheOriginal() throws IOException {
        byte[] pdf = classicPdf();
        List<PdfOverlay.PageSpec> same = new ArrayList<>();
        for (int i = 0; i < 3; i++) same.add(new PdfOverlay.PageSpec(i, 0));
        assertSame(pdf, PdfOverlay.apply(pdf, same));
    }

    @Test
    public void savingTwiceChains() throws IOException {
        byte[] once = PdfOverlay.apply(classicPdf(), edits());
        List<PdfOverlay.PageSpec> drop = new ArrayList<>();
        drop.add(new PdfOverlay.PageSpec(3, 0));
        drop.add(new PdfOverlay.PageSpec(0, 0));
        byte[] twice = PdfOverlay.apply(once, drop);
        List<String> text = PdfText.extract(twice);
        assertEquals(2, text.size());
        assertTrue(text.get(0).contains("Alpha page"));
        assertTrue(text.get(1).contains("Charlie page"));
    }

    @Test
    public void matrixFollowsRotation() {
        float[] box = {0, 0, 600, 800};
        assertEquals("600 0 0 800 0 0", PdfOverlay.matrix(box, 0));
        assertEquals("0 800 -600 0 600 0", PdfOverlay.matrix(box, 90));
        assertEquals("-600 0 0 -800 600 800", PdfOverlay.matrix(box, 180));
        assertEquals("0 -800 600 0 0 800", PdfOverlay.matrix(box, 270));
    }

    @Test
    public void protectedOrDamagedFilesAreRefused() {
        assertEquals(-1, PdfOverlay.pageCount(ascii("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n")));
        assertEquals(-1, PdfOverlay.pageCount(ascii("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
                + "trailer << /Root 1 0 R /Encrypt 3 0 R >>\nstartxref\n9\n%%EOF")));
    }
}
