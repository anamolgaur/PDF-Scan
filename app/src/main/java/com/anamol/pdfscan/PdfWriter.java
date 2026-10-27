package com.anamol.pdfscan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Small, dependency-free PDF writer for scanned pages (pure Java, unit-tested on a desktop JVM).
 * Each page is one image fitted to the page, plus an optional invisible text layer
 * (text render mode 3) so the PDF is searchable. Optional AES-256 password protection
 * (Standard security handler, revision 6, as in PDF 2.0 / Acrobat X and later).
 */
final class PdfWriter {

    /** A recognised word, in the pixel coordinates of the page image (top-left origin). */
    static final class Word {
        final String text;
        final float x, y, w, h;

        Word(String text, float x, float y, float w, float h) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** One page image, already encoded. */
    static final class Page {
        byte[] data;
        int width;
        int height;
        String filter = "DCTDecode";      // or FlateDecode
        String colorSpace = "DeviceRGB";  // or DeviceGray
        int bitsPerComponent = 8;
        List<Word> words = new ArrayList<>();
        int textW;                        // size of the image the word boxes refer to
        int textH;

        static Page jpeg(byte[] jpeg, int w, int h) {
            Page p = new Page();
            p.data = jpeg;
            p.width = w;
            p.height = h;
            return p;
        }

        /** 1-bit black-and-white page, Flate-compressed. Pixels are ARGB; darker than level is black. */
        static Page bilevel(int[] argb, int w, int h, int level) {
            int rowBytes = (w + 7) / 8;
            byte[] bits = new byte[rowBytes * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = argb[y * w + x];
                    int l = (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8;
                    if (l >= level) bits[y * rowBytes + (x >> 3)] |= (byte) (0x80 >> (x & 7));  // 1 = white
                }
            }
            Page p = new Page();
            p.data = deflate(bits);
            p.width = w;
            p.height = h;
            p.filter = "FlateDecode";
            p.colorSpace = "DeviceGray";
            p.bitsPerComponent = 1;
            return p;
        }
    }

    static final class Options {
        String title = "";
        String password;          // null or empty = no protection
        float pageWidth = 595f;   // A4 in points
        float pageHeight = 842f;
        SecureRandom random = new SecureRandom();
    }

    private final List<byte[]> objects = new ArrayList<>();  // index = object number - 1
    private Crypto crypto;

    private PdfWriter() {
    }

    static void write(List<Page> pages, Options o, OutputStream out) throws IOException {
        if (pages.isEmpty()) throw new IOException("No pages to export");
        new PdfWriter().writeDoc(pages, o, out);
    }

    private int reserve() {
        objects.add(null);
        return objects.size();
    }

    private void set(int num, byte[] body) {
        objects.set(num - 1, body);
    }

    private void writeDoc(List<Page> pages, Options o, OutputStream out) throws IOException {
        boolean encrypted = o.password != null && !o.password.isEmpty();
        if (encrypted) crypto = new Crypto(o.password, o.random);

        int catalog = reserve();
        int pagesObj = reserve();
        int font = reserve();
        int uniFont = reserve();
        int cidFont = reserve();
        int fontDesc = reserve();
        int toUnicode = reserve();
        int info = reserve();
        int encrypt = encrypted ? reserve() : 0;

        List<Integer> kids = new ArrayList<>();
        for (Page p : pages) {
            int pageObj = reserve();
            int contentObj = reserve();
            int imageObj = reserve();
            kids.add(pageObj);

            String imgDict = "<< /Type /XObject /Subtype /Image /Width " + p.width + " /Height " + p.height
                    + " /ColorSpace /" + p.colorSpace + " /BitsPerComponent " + p.bitsPerComponent
                    + " /Filter /" + p.filter;
            set(imageObj, stream(imgDict, p.data, imageObj));

            byte[] content = deflate(contentFor(p, o).getBytes(StandardCharsets.ISO_8859_1));
            set(contentObj, stream("<< /Filter /FlateDecode", content, contentObj));

            set(pageObj, ascii("<< /Type /Page /Parent " + pagesObj + " 0 R /MediaBox [0 0 " + num(o.pageWidth) + " "
                    + num(o.pageHeight) + "] /Resources << /XObject << /Im0 " + imageObj + " 0 R >> /Font << /F1 "
                    + font + " 0 R /F2 " + uniFont + " 0 R >> >> /Contents " + contentObj + " 0 R >>"));
        }

        StringBuilder k = new StringBuilder();
        for (int id : kids) k.append(id).append(" 0 R ");
        set(pagesObj, ascii("<< /Type /Pages /Count " + kids.size() + " /Kids [ " + k + "] >>"));
        set(font, ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        // Any script (Indian, Chinese, Japanese, Greek, Cyrillic...): an invisible-text font whose
        // character codes are the Unicode values themselves, so the text can be searched and copied.
        set(uniFont, ascii("<< /Type /Font /Subtype /Type0 /BaseFont /GlyphLessFont /Encoding /Identity-H"
                + " /DescendantFonts [" + cidFont + " 0 R] /ToUnicode " + toUnicode + " 0 R >>"));
        set(cidFont, ascii("<< /Type /Font /Subtype /CIDFontType2 /BaseFont /GlyphLessFont /CIDSystemInfo << /Registry "
                + rawString("Adobe") + " /Ordering " + rawString("Identity") + " /Supplement 0 >> /FontDescriptor "
                + fontDesc + " 0 R /DW 500 /CIDToGIDMap /Identity >>"));
        set(fontDesc, ascii("<< /Type /FontDescriptor /FontName /GlyphLessFont /Flags 5 /FontBBox [0 0 500 1000]"
                + " /ItalicAngle 0 /Ascent 1000 /Descent 0 /CapHeight 1000 /StemV 80 >>"));
        set(toUnicode, stream("<< /Filter /FlateDecode", deflate(identityCmap()), toUnicode));
        String ext = encrypted ? " /Extensions << /ADBE << /BaseVersion /1.7 /ExtensionLevel 8 >> >>" : "";
        set(catalog, ascii("<< /Type /Catalog /Pages " + pagesObj + " 0 R" + ext + " >>"));
        set(info, ascii("<< /Title " + string(o.title == null ? "" : o.title, info) + " /Producer "
                + string("PDF Scan", info) + " >>"));
        if (encrypted) set(encrypt, ascii(crypto.dictionary()));

        // ---- serialise
        CountingStream cs = new CountingStream(out);
        cs.write(ascii("%PDF-1.7\n%âãÏÓ\n"));
        long[] offsets = new long[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = cs.count;
            cs.write(ascii((i + 1) + " 0 obj\n"));
            cs.write(objects.get(i));
            cs.write(ascii("\nendobj\n"));
        }
        long xref = cs.count;
        StringBuilder sb = new StringBuilder();
        sb.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (long off : offsets) sb.append(String.format(Locale.US, "%010d 00000 n \n", off));
        byte[] id = new byte[16];
        o.random.nextBytes(id);
        String hexId = hex(id);
        sb.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root ").append(catalog)
                .append(" 0 R /Info ").append(info).append(" 0 R /ID [<").append(hexId).append("> <")
                .append(hexId).append(">]");
        if (encrypted) sb.append(" /Encrypt ").append(encrypt).append(" 0 R");
        sb.append(" >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        cs.write(ascii(sb.toString()));
        cs.flush();
    }

    /** Draws the image fitted and centred on the page, then the invisible words over it. */
    private static String contentFor(Page p, Options o) {
        float s = Math.min(o.pageWidth / p.width, o.pageHeight / p.height);
        float dw = p.width * s;
        float dh = p.height * s;
        float left = (o.pageWidth - dw) / 2f;
        float bottom = (o.pageHeight - dh) / 2f;
        StringBuilder c = new StringBuilder();
        c.append("q ").append(num(dw)).append(" 0 0 ").append(num(dh)).append(' ').append(num(left)).append(' ')
                .append(num(bottom)).append(" cm /Im0 Do Q\n");
        if (!p.words.isEmpty() && p.textW > 0 && p.textH > 0) {
            float sx = dw / p.textW;
            float sy = dh / p.textH;
            c.append("BT 3 Tr\n");
            for (Word w : p.words) {
                if (!latin(w.text)) {
                    unicodeWord(c, w, sx, sy, left, bottom, p.textH);
                    continue;
                }
                byte[] enc = winAnsi(w.text);
                if (enc.length == 0) continue;
                float size = Math.max(1f, w.h * sy);
                float natural = helveticaWidth(enc) * size / 1000f;
                float scale = natural > 0 ? 100f * (w.w * sx) / natural : 100f;
                float x = left + w.x * sx;
                float y = bottom + (p.textH - (w.y + w.h)) * sy + size * 0.2f;
                c.append("/F1 ").append(num(size)).append(" Tf ").append(num(scale)).append(" Tz 1 0 0 1 ")
                        .append(num(x)).append(' ').append(num(y)).append(" Tm ");
                literal(c, enc);
                c.append(" Tj\n");
            }
            c.append("ET\n");
        }
        return c.toString();
    }

    /** True when WinAnsi (Helvetica) can show every character. */
    static boolean latin(String s) {
        byte[] enc = winAnsi(s);
        int q = 0;
        for (byte b : enc) if (b == '?') q++;
        int real = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '?') real++;
        return q == real;
    }

    /** Writes one word with the Unicode font: two-byte codes equal to its UTF-16 units. */
    private static void unicodeWord(StringBuilder c, Word w, float sx, float sy, float left, float bottom, int textH) {
        String s = w.text;
        if (s.isEmpty()) return;
        float size = Math.max(1f, w.h * sy);
        float natural = s.length() * 500f * size / 1000f;
        float scale = natural > 0 ? 100f * (w.w * sx) / natural : 100f;
        float x = left + w.x * sx;
        float y = bottom + (textH - (w.y + w.h)) * sy + size * 0.2f;
        c.append("/F2 ").append(num(size)).append(" Tf ").append(num(scale)).append(" Tz 1 0 0 1 ")
                .append(num(x)).append(' ').append(num(y)).append(" Tm <");
        for (int i = 0; i < s.length(); i++) c.append(String.format(Locale.US, "%04X", (int) s.charAt(i)));
        c.append("> Tj\n");
    }

    /** ToUnicode map: every two-byte code is the UTF-16 unit with the same value. */
    static byte[] identityCmap() {
        StringBuilder m = new StringBuilder();
        m.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
                .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
                .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
                .append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        for (int block = 0; block < 256; block += 100) {
            int n = Math.min(100, 256 - block);
            m.append(n).append(" beginbfrange\n");
            for (int hi = block; hi < block + n; hi++) {
                String h = String.format(Locale.US, "%02X", hi);
                m.append('<').append(h).append("00> <").append(h).append("FF> <").append(h).append("00>\n");
            }
            m.append("endbfrange\n");
        }
        m.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        return m.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** A byte string (not text) in a dictionary, encrypted when the document is protected. */
    private String rawString(String ascii) throws IOException {
        byte[] raw = ascii.getBytes(StandardCharsets.US_ASCII);
        if (crypto != null) return "<" + hex(crypto.encrypt(raw)) + ">";
        return "(" + ascii + ")";
    }

    private byte[] stream(String dictStart, byte[] data, int objNum) throws IOException {
        byte[] body = crypto != null ? crypto.encrypt(data) : data;
        ByteArrayOutputStream b = new ByteArrayOutputStream(body.length + 128);
        b.write(ascii(dictStart + " /Length " + body.length + " >>\nstream\n"));
        b.write(body);
        b.write(ascii("\nendstream"));
        return b.toByteArray();
    }

    /** A text string: plain literal, or encrypted hex when the document is protected. */
    private String string(String s, int objNum) throws IOException {
        byte[] raw = utf16(s);
        if (crypto != null) return "<" + hex(crypto.encrypt(raw)) + ">";
        return "<" + hex(raw) + ">";
    }

    private static byte[] utf16(String s) {
        byte[] body = s.getBytes(StandardCharsets.UTF_16BE);
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) 0xFE;
        out[1] = (byte) 0xFF;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    // ------------------------------------------------------------------ text helpers

    /** Encodes to WinAnsi (Windows-1252); characters it can't represent become '?'. */
    static byte[] winAnsi(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int v;
            if (ch >= 32 && ch < 127) v = ch;
            else if (ch >= 0xA0 && ch <= 0xFF) v = ch;
            else {
                switch (ch) {
                    case '€': v = 0x80; break;
                    case '…': v = 0x85; break;
                    case '‘': v = 0x91; break;
                    case '’': v = 0x92; break;
                    case '“': v = 0x93; break;
                    case '”': v = 0x94; break;
                    case '•': v = 0x95; break;
                    case '–': v = 0x96; break;
                    case '—': v = 0x97; break;
                    default: v = ch < 32 ? -1 : '?'; break;
                }
            }
            if (v >= 0) b.write(v);
        }
        return b.toByteArray();
    }

    private static void literal(StringBuilder c, byte[] enc) {
        c.append('(');
        for (byte x : enc) {
            int v = x & 0xFF;
            if (v == '(' || v == ')' || v == '\\') c.append('\\').append((char) v);
            else if (v < 32 || v > 126) c.append(String.format(Locale.US, "\\%03o", v));
            else c.append((char) v);
        }
        c.append(')');
    }

    /** Helvetica advance widths (1/1000 em) for ASCII 32..126; others use 556. */
    private static final int[] HELV = {
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584};

    static float helveticaWidth(byte[] enc) {
        float w = 0;
        for (byte x : enc) {
            int v = x & 0xFF;
            w += (v >= 32 && v <= 126) ? HELV[v - 32] : 556;
        }
        return w;
    }

    // ------------------------------------------------------------------ misc helpers

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream b = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        byte[] buf = new byte[64 * 1024];
        while (!d.finished()) {
            int n = d.deflate(buf);
            b.write(buf, 0, n);
        }
        d.end();
        return b.toByteArray();
    }

    private static String num(float v) {
        if (Math.abs(v - Math.round(v)) < 0.0005f) return Integer.toString(Math.round(v));
        return String.format(Locale.US, "%.3f", v);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02X", x & 0xFF));
        return sb.toString();
    }

    private static final class CountingStream extends OutputStream {
        private final OutputStream out;
        long count;

        CountingStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }

    // ------------------------------------------------------------------ AES-256 (R6) encryption

    /** Standard security handler, V5 / R6, AES-256, ISO 32000-2 algorithms 8, 9, 10 and 2.B. */
    static final class Crypto {
        static final int PERMISSIONS = -4;  // everything allowed once the password is entered
        final byte[] fileKey = new byte[32];
        final byte[] u;
        final byte[] ue;
        final byte[] o;
        final byte[] oe;
        final byte[] perms;
        private final SecureRandom random;

        Crypto(String password, SecureRandom random) throws IOException {
            this.random = random;
            try {
                byte[] pwd = passwordBytes(password);
                random.nextBytes(fileKey);

                byte[] uvs = randomBytes(8);
                byte[] uks = randomBytes(8);
                u = concat(hash(pwd, uvs, new byte[0]), uvs, uks);
                ue = aesNoPad(hash(pwd, uks, new byte[0]), fileKey);

                // Owner password = user password: whoever opens the file has full rights.
                byte[] ovs = randomBytes(8);
                byte[] oks = randomBytes(8);
                o = concat(hash(pwd, ovs, u), ovs, oks);
                oe = aesNoPad(hash(pwd, oks, u), fileKey);

                byte[] p = new byte[16];
                p[0] = (byte) PERMISSIONS;
                p[1] = (byte) (PERMISSIONS >> 8);
                p[2] = (byte) (PERMISSIONS >> 16);
                p[3] = (byte) (PERMISSIONS >> 24);
                p[4] = p[5] = p[6] = p[7] = (byte) 0xFF;
                p[8] = 'T';
                p[9] = 'a';
                p[10] = 'd';
                p[11] = 'b';
                System.arraycopy(randomBytes(4), 0, p, 12, 4);
                Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
                ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(fileKey, "AES"));
                perms = ecb.doFinal(p);
            } catch (GeneralSecurityException e) {
                throw new IOException("Encryption is not available: " + e.getMessage(), e);
            }
        }

        String dictionary() {
            return "<< /Filter /Standard /V 5 /R 6 /Length 256 /P " + PERMISSIONS + " /EncryptMetadata true"
                    + " /CF << /StdCF << /Type /CryptFilter /CFM /AESV3 /AuthEvent /DocOpen /Length 32 >> >>"
                    + " /StmF /StdCF /StrF /StdCF"
                    + " /O <" + hex(o) + "> /U <" + hex(u) + "> /OE <" + hex(oe) + "> /UE <" + hex(ue) + ">"
                    + " /Perms <" + hex(perms) + "> >>";
        }

        /** AES-256-CBC with a random IV in front and PKCS#7 padding (used for every string and stream). */
        byte[] encrypt(byte[] data) throws IOException {
            try {
                byte[] iv = randomBytes(16);
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(fileKey, "AES"), new IvParameterSpec(iv));
                return concat(iv, c.doFinal(data));
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        private byte[] randomBytes(int n) {
            byte[] b = new byte[n];
            random.nextBytes(b);
            return b;
        }

        static byte[] passwordBytes(String password) {
            byte[] b = password.getBytes(StandardCharsets.UTF_8);
            return b.length > 127 ? Arrays.copyOf(b, 127) : b;
        }

        /** AES-256-CBC, zero IV, no padding (for UE and OE; input is a multiple of 16 bytes). */
        static byte[] aesNoPad(byte[] key, byte[] data) throws GeneralSecurityException {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            return c.doFinal(data);
        }

        /** Algorithm 2.B: hardened hash of password, salt and (for the owner key) the U string. */
        static byte[] hash(byte[] pwd, byte[] salt, byte[] udata) throws GeneralSecurityException {
            byte[] k = MessageDigest.getInstance("SHA-256").digest(concat(pwd, salt, udata));
            int round = 0;
            while (true) {
                byte[] block = concat(pwd, k, udata);
                byte[] k1 = new byte[block.length * 64];
                for (int i = 0; i < 64; i++) System.arraycopy(block, 0, k1, i * block.length, block.length);
                Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(k, 16), "AES"),
                        new IvParameterSpec(Arrays.copyOfRange(k, 16, 32)));
                byte[] e = c.doFinal(k1);
                int sum = 0;
                for (int i = 0; i < 16; i++) sum += e[i] & 0xFF;
                String alg = sum % 3 == 0 ? "SHA-256" : (sum % 3 == 1 ? "SHA-384" : "SHA-512");
                k = MessageDigest.getInstance(alg).digest(e);
                round++;
                if (round >= 64 && (e[e.length - 1] & 0xFF) <= round - 32) break;
            }
            return Arrays.copyOf(k, 32);
        }

        static byte[] concat(byte[]... parts) {
            int n = 0;
            for (byte[] p : parts) n += p.length;
            byte[] out = new byte[n];
            int at = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, out, at, p.length);
                at += p.length;
            }
            return out;
        }
    }
}
