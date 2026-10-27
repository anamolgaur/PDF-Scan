package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Text extraction from real files made by LibreOffice, reportlab, qpdf, python-docx/openpyxl/
 * python-pptx (see desktop-tests/src/test/resources/fixtures) and by this app's own PDF writer.
 */
public class ExtractorsTest {
    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = ExtractorsTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IOException("missing fixture " + name);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static File file(String name) throws IOException {
        File f = File.createTempFile("fixture", "-" + name);
        f.deleteOnExit();
        try (InputStream in = ExtractorsTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IOException("missing fixture " + name);
            Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return f;
    }

    private static void hasAll(String text, String... words) {
        for (String w : words) assertTrue("expected “" + w + "” in:\n" + text, text.contains(w));
    }

    private static String pdf(String name) throws IOException {
        return String.join("\n", PdfText.extract(bytes(name)));
    }

    @Test
    public void libreOfficePdfWithEmbeddedFonts() throws Exception {
        String t = pdf("lo-report.pdf");
        hasAll(t, "Field report Porvorim", "“smart quotes”", "café", "naïve résumé",
                "Invoice 4521", "Affluent official offices find fluffy waffles", "Agarose", "100 V");
    }

    @Test
    public void objectStreams() throws Exception {
        hasAll(pdf("objstm.pdf"), "Field report Porvorim", "fluffy waffles");
    }

    @Test
    public void encryptedWithEmptyUserPassword() throws Exception {
        for (String f : new String[]{"enc-rc4-40.pdf", "enc-aes128.pdf", "enc-aes256.pdf"}) {
            hasAll(pdf(f), "Quarterly field report", "efficient workflow", "Invoice 4521");
        }
    }

    @Test
    public void passwordProtectedPdfIsReportedAsLocked() throws Exception {
        try {
            PdfText.extract(bytes("locked.pdf"));
            fail("should need a password");
        } catch (PdfText.LockedException expected) {
            // ok
        }
    }

    @Test
    public void subsetTrueTypeWithToUnicode() throws Exception {
        hasAll(pdf("reportlab-ttf.pdf"), "résumé", "café", "Ωmega", "12,400", "office efficient fluffy");
    }

    @Test
    public void ownSearchablePdfsAreIndexable() throws Exception {
        PdfWriter.Page p = PdfWriter.Page.bilevel(new int[100 * 100], 100, 100, 128);
        p.words.add(new PdfWriter.Word("Invoice", 5, 5, 40, 10));
        p.words.add(new PdfWriter.Word("4521", 50, 5, 25, 10));
        p.words.add(new PdfWriter.Word("paid", 5, 30, 25, 10));
        p.textW = 100;
        p.textH = 100;
        PdfWriter.Options o = new PdfWriter.Options();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.write(Arrays.asList(p, p), o, out);
        List<String> pages = PdfText.extract(out.toByteArray());
        assertEquals(2, pages.size());
        assertEquals("Invoice 4521\npaid", pages.get(0));

        // and with a password-free AES-256 file written by our writer? Protected files stay locked.
        o.password = "pw";
        o.random = new SecureRandom();
        out.reset();
        PdfWriter.write(Arrays.asList(p), o, out);
        try {
            PdfText.extract(out.toByteArray());
            fail("protected file should be locked");
        } catch (PdfText.LockedException expected) {
            // ok
        }
    }

    @Test
    public void notAPdf() {
        try {
            PdfText.extract("hello".getBytes());
            fail();
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void word() throws Exception {
        OfficeText.Result r = OfficeText.extract(file("report.docx"));
        hasAll(r.parts.get(0), "Field report Porvorim", "“smart quotes”", "naïve résumé",
                "fluffy waffles", "Agarose", "1 percent");
    }

    @Test
    public void excelSheets() throws Exception {
        OfficeText.Result r = OfficeText.extract(file("budget.xlsx"));
        assertEquals("sheet", r.unit);
        assertEquals(2, r.parts.size());
        hasAll(r.parts.get(0), "Item\tCost\tNotes", "Drone frame\t2450\tcarbon fibre");
        hasAll(r.parts.get(1), "Team Crimson");
    }

    @Test
    public void powerPointSlides() throws Exception {
        OfficeText.Result r = OfficeText.extract(file("slides.pptx"));
        assertEquals("slide", r.unit);
        assertEquals(2, r.parts.size());
        hasAll(r.parts.get(0), "Gel electrophoresis", "Agarose gel separates DNA fragments by size");
        hasAll(r.parts.get(1), "Automated imaging under UV light");
    }

    @Test
    public void openDocument() throws Exception {
        hasAll(OfficeText.extract(file("report.odt")).parts.get(0), "Field report Porvorim", "fluffy waffles");
        OfficeText.Result p = OfficeText.extract(file("slides.odp"));
        assertEquals(2, p.parts.size());
        hasAll(p.parts.get(1), "Documentation system");
    }

    @Test
    public void rtfAndText() throws Exception {
        hasAll(OfficeText.extract(file("report.rtf")).parts.get(0), "Field report Porvorim",
                "“smart quotes”", "naïve résumé", "fluffy waffles");
        hasAll(OfficeText.extract(file("letter.txt")).parts.get(0), "₹12,400", "Quarterly field report — Porvorim");
    }

    @Test
    public void oldWordBestEffort() throws Exception {
        hasAll(OfficeText.extract(file("report.doc")).parts.get(0), "Porvorim", "waffles", "Agarose");
    }

    @Test
    public void glyphNames() {
        assertEquals("é", Glyphs.fromName("eacute"));
        assertEquals("ffi", Glyphs.fromName("f_f_i"));
        assertEquals("A", Glyphs.fromName("A.sc"));
        assertEquals("€", Glyphs.fromName("uni20AC"));
        assertEquals("Ł", Glyphs.fromName("Lslash"));
        assertEquals("’", Glyphs.baseChar("StandardEncoding", 0x27));
        assertEquals("—", Glyphs.baseChar("WinAnsiEncoding", 0x97));
        assertEquals("é", Glyphs.baseChar("MacRomanEncoding", 0x8E));
    }
}
