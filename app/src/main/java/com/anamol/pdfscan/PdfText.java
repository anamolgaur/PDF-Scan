package com.anamol.pdfscan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Extracts the text layer of a PDF, page by page (pure Java; unit-tested against pdftotext).
 * Tolerant parser: finds objects by scanning (works with damaged xref tables), reads object
 * streams, Flate/LZW/ASCIIHex/ASCII85 filters, files encrypted with an empty user password
 * (RC4, AES-128, AES-256), ToUnicode maps, standard and custom simple-font encodings.
 * Pages that are only images come back empty (the indexer can then OCR them).
 */
final class PdfText {
    static final int MAX_CHARS = 2_000_000;

    /** Thrown when a PDF needs a password to open. */
    static final class LockedException extends IOException {
        LockedException() {
            super("The PDF is password-protected");
        }
    }

    // ------------------------------------------------------------------ object model

    static final class Ref {
        final int num;
        final int gen;

        Ref(int num, int gen) {
            this.num = num;
            this.gen = gen;
        }
    }

    static final class Name {
        final String v;

        Name(String v) {
            this.v = v;
        }

        @Override
        public String toString() {
            return v;
        }
    }

    static final class Str {
        final byte[] b;

        Str(byte[] b) {
            this.b = b;
        }
    }

    static final class Kw {
        final String v;

        Kw(String v) {
            this.v = v;
        }
    }

    static final class Dict extends LinkedHashMap<String, Object> {
        int num = -1;
        int gen;
        int streamStart = -1;
        int streamLen;
        byte[] streamData;  // for streams decoded from memory (not used for file streams)
    }

    private static final Object NULL = new Object();

    // ------------------------------------------------------------------ lexer

    static boolean isWs(int c) {
        return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;
    }

    static boolean isDelim(int c) {
        return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}'
                || c == '/' || c == '%';
    }

    static final class Lexer {
        final byte[] d;
        int p;
        final int end;

        Lexer(byte[] d, int p, int end) {
            this.d = d;
            this.p = p;
            this.end = end;
        }

        void skipWs() {
            while (p < end) {
                int c = d[p] & 0xFF;
                if (isWs(c)) {
                    p++;
                } else if (c == '%') {
                    while (p < end && d[p] != '\n' && d[p] != '\r') p++;
                } else {
                    break;
                }
            }
        }

        /** Next object or keyword; null at end of input. */
        Object next() {
            skipWs();
            if (p >= end) return null;
            int c = d[p] & 0xFF;
            switch (c) {
                case '/':
                    return name();
                case '(':
                    return literal();
                case '<':
                    if (p + 1 < end && d[p + 1] == '<') {
                        p += 2;
                        return dict();
                    }
                    return hex();
                case '[': {
                    p++;
                    List<Object> a = new ArrayList<>();
                    while (true) {
                        skipWs();
                        if (p >= end) return a;
                        if (d[p] == ']') {
                            p++;
                            return a;
                        }
                        Object o = next();
                        if (o == null) return a;
                        if (o instanceof Kw && (((Kw) o).v.equals(">>"))) return a;
                        a.add(o);
                    }
                }
                case ']':
                    p++;
                    return new Kw("]");
                case '>':
                    p++;
                    if (p < end && d[p] == '>') p++;
                    return new Kw(">>");
                case '{':
                case '}':
                    p++;
                    return new Kw(String.valueOf((char) c));
                default:
                    break;
            }
            if (c == '+' || c == '-' || c == '.' || (c >= '0' && c <= '9')) return number();
            int s = p;
            while (p < end && !isWs(d[p] & 0xFF) && !isDelim(d[p] & 0xFF)) p++;
            if (p == s) {
                p++;
                return new Kw(String.valueOf((char) c));
            }
            String k = new String(d, s, p - s, StandardCharsets.ISO_8859_1);
            switch (k) {
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                case "null":
                    return NULL;
                default:
                    return new Kw(k);
            }
        }

        private Object number() {
            int s = p;
            p++;
            while (p < end) {
                int c = d[p] & 0xFF;
                if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'E' || c == 'e') p++;
                else break;
            }
            String t = new String(d, s, p - s, StandardCharsets.ISO_8859_1);
            double v;
            try {
                v = Double.parseDouble(t);
            } catch (NumberFormatException e) {
                v = 0;
            }
            // "num gen R"?
            if (t.indexOf('.') < 0 && !t.startsWith("-") && !t.startsWith("+")) {
                int save = p;
                skipWs();
                int g = p;
                while (p < end && d[p] >= '0' && d[p] <= '9') p++;
                if (p > g) {
                    int gen = (int) Math.min(Integer.MAX_VALUE, Long.parseLong(new String(d, g, Math.min(p - g, 9), StandardCharsets.ISO_8859_1)));
                    skipWs();
                    if (p < end && d[p] == 'R' && (p + 1 >= end || isWs(d[p + 1] & 0xFF) || isDelim(d[p + 1] & 0xFF))) {
                        p++;
                        return new Ref((int) v, gen);
                    }
                }
                p = save;
            }
            return v;
        }

        private Name name() {
            p++;
            StringBuilder sb = new StringBuilder();
            while (p < end) {
                int c = d[p] & 0xFF;
                if (isWs(c) || isDelim(c)) break;
                if (c == '#' && p + 2 < end) {
                    try {
                        sb.append((char) Integer.parseInt(new String(d, p + 1, 2, StandardCharsets.ISO_8859_1), 16));
                        p += 3;
                        continue;
                    } catch (NumberFormatException ignored) {
                        // literal '#'
                    }
                }
                sb.append((char) c);
                p++;
            }
            return new Name(sb.toString());
        }

        private Str literal() {
            p++;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int depth = 1;
            while (p < end) {
                int c = d[p++] & 0xFF;
                if (c == '\\') {
                    if (p >= end) break;
                    int e = d[p++] & 0xFF;
                    switch (e) {
                        case 'n': b.write('\n'); break;
                        case 'r': b.write('\r'); break;
                        case 't': b.write('\t'); break;
                        case 'b': b.write('\b'); break;
                        case 'f': b.write('\f'); break;
                        case '\r':
                            if (p < end && d[p] == '\n') p++;
                            break;
                        case '\n':
                            break;
                        default:
                            if (e >= '0' && e <= '7') {
                                int v = e - '0';
                                for (int k = 0; k < 2 && p < end && d[p] >= '0' && d[p] <= '7'; k++) v = v * 8 + (d[p++] - '0');
                                b.write(v & 0xFF);
                            } else {
                                b.write(e);
                            }
                    }
                } else if (c == '(') {
                    depth++;
                    b.write(c);
                } else if (c == ')') {
                    if (--depth == 0) break;
                    b.write(c);
                } else {
                    b.write(c);
                }
            }
            return new Str(b.toByteArray());
        }

        private Str hex() {
            p++;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int hi = -1;
            while (p < end) {
                int c = d[p++] & 0xFF;
                if (c == '>') break;
                int v = Character.digit(c, 16);
                if (v < 0) continue;
                if (hi < 0) {
                    hi = v;
                } else {
                    b.write(hi * 16 + v);
                    hi = -1;
                }
            }
            if (hi >= 0) b.write(hi * 16);
            return new Str(b.toByteArray());
        }

        private Dict dict() {
            Dict m = new Dict();
            while (true) {
                skipWs();
                if (p >= end) return m;
                if (d[p] == '>' && p + 1 < end && d[p + 1] == '>') {
                    p += 2;
                    return m;
                }
                Object k = next();
                if (k == null) return m;
                if (k instanceof Kw && ((Kw) k).v.equals(">>")) return m;
                if (!(k instanceof Name)) continue;
                Object v = next();
                if (v == null) return m;
                if (v instanceof Kw && ((Kw) v).v.equals(">>")) {
                    return m;
                }
                m.put(((Name) k).v, v);
            }
        }
    }

    // ------------------------------------------------------------------ document

    private final byte[] data;
    private final Map<Integer, Object> objects = new HashMap<>();
    private final Map<Integer, Integer> objectGens = new HashMap<>();
    private Dict trailer = new Dict();
    private Decryptor decryptor;
    private final Map<Dict, Font> fonts = new IdentityHashMap<>();
    private int totalChars;

    private PdfText(byte[] data) {
        this.data = data;
    }

    /** Text of each page, in order. */
    static List<String> extract(byte[] pdf) throws IOException {
        if (pdf.length < 8 || indexOf(pdf, "%PDF".getBytes(StandardCharsets.ISO_8859_1), 0, Math.min(pdf.length, 1024)) < 0) {
            throw new IOException("Not a PDF file");
        }
        PdfText t = new PdfText(pdf);
        t.scanObjects();
        t.loadObjectStreams();
        t.setUpEncryption();
        return t.pagesText();
    }

    // ------------------------------------------------------------------ access for PdfOverlay

    /** Parses a PDF for changing it (PdfOverlay). Password-protected PDFs throw LockedException. */
    static PdfText open(byte[] pdf) throws IOException {
        if (pdf.length < 8 || indexOf(pdf, "%PDF".getBytes(StandardCharsets.ISO_8859_1), 0, Math.min(pdf.length, 1024)) < 0) {
            throw new IOException("Not a PDF file");
        }
        PdfText t = new PdfText(pdf);
        t.scanObjects();
        if (t.trailer.get("Encrypt") != null) throw new LockedException();
        t.loadObjectStreams();
        return t;
    }

    Dict trailer() {
        return trailer;
    }

    Dict catalog() {
        return dict(trailer.get("Root"));
    }

    int maxObjectNumber() {
        int m = 0;
        for (int k : objects.keySet()) m = Math.max(m, k);
        return m;
    }

    /** Pages in order from the page tree: {page, effective resources}. Empty when there is no usable tree. */
    List<Dict[]> pageList() {
        List<Dict[]> pages = new ArrayList<>();
        Dict root = catalog();
        Dict tree = root != null ? dict(root.get("Pages")) : null;
        if (tree != null) collect(tree, null, pages, new HashSet<>(), 0);
        return pages;
    }

    /** A page attribute, looked up through the page's parents (MediaBox, CropBox, Rotate, Resources). */
    Object inherited(Dict page, String key) {
        Dict d = page;
        for (int guard = 0; d != null && guard < 64; guard++) {
            if (d.containsKey(key)) return d.get(key);
            d = dict(d.get("Parent"));
        }
        return null;
    }

    Dict dictOf(Object o) {
        return dict(o);
    }

    List<Object> arrayOf(Object o) {
        return array(o);
    }

    double numberOf(Object o, double def) {
        return num(o, def);
    }

    /** Writes a PDF value (not a stream) in PDF syntax. Indirect dictionaries are written as references. */
    static void serialize(Object o, StringBuilder sb, boolean top) {
        if (o == null || o == NULL) {
            sb.append("null");
        } else if (o instanceof Double) {
            sb.append(number((Double) o));
        } else if (o instanceof Integer || o instanceof Long) {
            sb.append(o);
        } else if (o instanceof Float) {
            sb.append(number((Float) o));
        } else if (o instanceof Boolean) {
            sb.append(((Boolean) o) ? "true" : "false");
        } else if (o instanceof Name) {
            sb.append('/');
            for (char c : ((Name) o).v.toCharArray()) {
                if (c > 32 && c < 127 && c != '#' && !isDelim(c)) {
                    sb.append(c);
                } else {
                    sb.append('#').append(String.format(java.util.Locale.ROOT, "%02X", c & 0xFF));
                }
            }
        } else if (o instanceof Str) {
            sb.append('<');
            for (byte b : ((Str) o).b) sb.append(String.format(java.util.Locale.ROOT, "%02X", b & 0xFF));
            sb.append('>');
        } else if (o instanceof Ref) {
            sb.append(((Ref) o).num).append(' ').append(((Ref) o).gen).append(" R");
        } else if (o instanceof Kw) {
            sb.append(((Kw) o).v);
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object e : (List<?>) o) {
                if (!first) sb.append(' ');
                first = false;
                serialize(e, sb, false);
            }
            sb.append(']');
        } else if (o instanceof Dict) {
            Dict d = (Dict) o;
            if (!top && d.num >= 0) {
                sb.append(d.num).append(' ').append(d.gen).append(" R");
                return;
            }
            sb.append("<<");
            for (Map.Entry<String, Object> e : d.entrySet()) {
                serialize(new Name(e.getKey()), sb, false);
                sb.append(' ');
                serialize(e.getValue(), sb, false);
                sb.append('\n');
            }
            sb.append(">>");
        } else {
            sb.append("null");
        }
    }

    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = new java.math.BigDecimal(v).setScale(5, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return s.equals("-0") ? "0" : s;
    }

    static int indexOf(byte[] d, byte[] pat, int from, int to) {
        outer:
        for (int i = Math.max(0, from); i <= to - pat.length; i++) {
            for (int k = 0; k < pat.length; k++) if (d[i + k] != pat[k]) continue outer;
            return i;
        }
        return -1;
    }

    private static final byte[] ENDSTREAM = "endstream".getBytes(StandardCharsets.ISO_8859_1);

    /** Finds every "N G obj" in the file; later definitions replace earlier ones. */
    private void scanObjects() {
        int n = data.length;
        int i = 0;
        while (i < n - 3) {
            if (data[i] == 'o' && data[i + 1] == 'b' && data[i + 2] == 'j'
                    && (i + 3 >= n || isWs(data[i + 3] & 0xFF) || isDelim(data[i + 3] & 0xFF))) {
                int j = i - 1;
                while (j >= 0 && isWs(data[j] & 0xFF)) j--;
                int genEnd = j + 1;
                while (j >= 0 && data[j] >= '0' && data[j] <= '9') j--;
                int genStart = j + 1;
                if (genStart < genEnd && j >= 0 && isWs(data[j] & 0xFF)) {
                    while (j >= 0 && isWs(data[j] & 0xFF)) j--;
                    int numEnd = j + 1;
                    while (j >= 0 && data[j] >= '0' && data[j] <= '9') j--;
                    int numStart = j + 1;
                    if (numStart < numEnd && numEnd - numStart <= 9 && (j < 0 || isWs(data[j] & 0xFF) || isDelim(data[j] & 0xFF))) {
                        int num = Integer.parseInt(new String(data, numStart, numEnd - numStart, StandardCharsets.ISO_8859_1));
                        int gen = Integer.parseInt(new String(data, genStart, Math.min(genEnd - genStart, 9), StandardCharsets.ISO_8859_1));
                        i = parseObjectAt(num, gen, i + 3);
                        continue;
                    }
                }
            } else if (data[i] == 't' && i + 7 < n && data[i + 1] == 'r' && data[i + 2] == 'a' && data[i + 3] == 'i'
                    && data[i + 4] == 'l' && data[i + 5] == 'e' && data[i + 6] == 'r') {
                Lexer lx = new Lexer(data, i + 7, n);
                Object t = lx.next();
                if (t instanceof Dict) mergeTrailer((Dict) t);
                i = Math.max(i + 7, lx.p);
                continue;
            }
            i++;
        }
    }

    private void mergeTrailer(Dict t) {
        for (Map.Entry<String, Object> e : t.entrySet()) trailer.put(e.getKey(), e.getValue());
    }

    /** Parses one object body; returns the position to continue scanning from. */
    private int parseObjectAt(int num, int gen, int pos) {
        Lexer lx = new Lexer(data, pos, data.length);
        Object o = lx.next();
        if (o == null) return pos;
        int after = lx.p;
        if (o instanceof Dict) {
            Dict d = (Dict) o;
            d.num = num;
            d.gen = gen;
            lx.skipWs();
            if (lx.p + 6 <= data.length && new String(data, lx.p, 6, StandardCharsets.ISO_8859_1).equals("stream")) {
                int s = lx.p + 6;
                if (s < data.length && data[s] == '\r') s++;
                if (s < data.length && data[s] == '\n') s++;
                int len = -1;
                Object l = d.get("Length");
                if (l instanceof Double) len = (int) (double) (Double) l;
                int endPos = -1;
                if (len >= 0 && s + len <= data.length) {
                    int k = s + len;
                    while (k < data.length && isWs(data[k] & 0xFF) && k < s + len + 4) k++;
                    if (indexOf(data, ENDSTREAM, k, Math.min(data.length, k + 9)) == k) endPos = s + len;
                }
                if (endPos < 0) {
                    int e = indexOf(data, ENDSTREAM, s, data.length);
                    if (e < 0) e = data.length;
                    int k = e;
                    if (k > s && data[k - 1] == '\n') k--;
                    if (k > s && data[k - 1] == '\r') k--;
                    endPos = k;
                }
                d.streamStart = s;
                d.streamLen = Math.max(0, endPos - s);
                after = endPos;
                if (d.get("Type") instanceof Name && ((Name) d.get("Type")).v.equals("XRef")) mergeTrailer(d);
            }
        }
        objects.put(num, o);
        objectGens.put(num, gen);
        return Math.max(after, pos);
    }

    /** Objects compressed inside /Type /ObjStm streams (PDF 1.5+). */
    private void loadObjectStreams() throws LockedException {
        List<Dict> streams = new ArrayList<>();
        for (Object o : objects.values()) {
            if (o instanceof Dict && ((Dict) o).streamStart >= 0) {
                Object t = ((Dict) o).get("Type");
                if (t instanceof Name && ((Name) t).v.equals("ObjStm")) streams.add((Dict) o);
            }
        }
        if (streams.isEmpty()) return;
        setUpEncryption();
        for (Dict s : streams) {
            try {
                byte[] b = decode(s);
                int count = (int) num(s.get("N"), 0);
                int first = (int) num(s.get("First"), 0);
                Lexer head = new Lexer(b, 0, Math.min(first, b.length));
                for (int k = 0; k < count; k++) {
                    Object on = head.next();
                    Object off = head.next();
                    if (!(on instanceof Double) || !(off instanceof Double)) break;
                    int objNum = (int) (double) (Double) on;
                    if (objects.containsKey(objNum)) continue;  // a direct object wins
                    Lexer lx = new Lexer(b, first + (int) (double) (Double) off, b.length);
                    Object o = lx.next();
                    if (o instanceof Dict) ((Dict) o).num = objNum;
                    if (o != null) objects.put(objNum, o);
                }
            } catch (IOException | RuntimeException ignored) {
                // unreadable object stream: skip it
            }
        }
    }

    Object resolve(Object o) {
        int guard = 0;
        while (o instanceof Ref && guard++ < 32) o = objects.get(((Ref) o).num);
        return o == NULL ? null : o;
    }

    private Dict dict(Object o) {
        Object r = resolve(o);
        return r instanceof Dict ? (Dict) r : null;
    }

    @SuppressWarnings("unchecked")
    private List<Object> array(Object o) {
        Object r = resolve(o);
        return r instanceof List ? (List<Object>) r : null;
    }

    private double num(Object o, double def) {
        Object r = resolve(o);
        return r instanceof Double ? (Double) r : def;
    }

    private String name(Object o) {
        Object r = resolve(o);
        return r instanceof Name ? ((Name) r).v : null;
    }

    // ------------------------------------------------------------------ streams and filters

    byte[] decode(Dict s) throws IOException {
        byte[] b;
        if (s.streamData != null) {
            b = s.streamData;
        } else {
            if (s.streamStart < 0) return new byte[0];
            int len = s.streamLen;
            Object l = resolve(s.get("Length"));
            if (l instanceof Double) {
                int want = (int) (double) (Double) l;
                if (want >= 0 && want <= len) len = want;
            }
            b = Arrays.copyOfRange(data, s.streamStart, Math.min(data.length, s.streamStart + len));
            if (decryptor != null && s.num >= 0 && !"XRef".equals(name(s.get("Type")))) b = decryptor.decrypt(b, s.num, s.gen);
        }
        Object f = resolve(s.get("Filter"));
        Object parms = resolve(s.get("DecodeParms"));
        List<Object> filters = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (f instanceof Name) {
            filters.add(f);
            params.add(parms);
        } else if (f instanceof List) {
            filters.addAll(array(f));
            List<Object> pa = parms instanceof List ? array(parms) : null;
            for (int i = 0; i < filters.size(); i++) params.add(pa != null && i < pa.size() ? resolve(pa.get(i)) : null);
        }
        for (int i = 0; i < filters.size(); i++) {
            String fn = name(filters.get(i));
            if (fn == null) continue;
            Dict p = params.get(i) instanceof Dict ? (Dict) params.get(i) : null;
            switch (fn) {
                case "FlateDecode":
                case "Fl":
                    b = predictor(inflate(b), p);
                    break;
                case "LZWDecode":
                case "LZW":
                    b = predictor(lzw(b, p == null || num(p.get("EarlyChange"), 1) != 0), p);
                    break;
                case "ASCIIHexDecode":
                case "AHx":
                    b = asciiHex(b);
                    break;
                case "ASCII85Decode":
                case "A85":
                    b = ascii85(b);
                    break;
                default:
                    return b;  // image codecs etc.: not text
            }
        }
        return b;
    }

    static byte[] inflate(byte[] in) {
        byte[] out = inflate(in, false);
        return out.length > 0 || in.length == 0 ? out : inflate(in, true);
    }

    private static byte[] inflate(byte[] in, boolean raw) {
        Inflater inf = new Inflater(raw);
        inf.setInput(in);
        ByteArrayOutputStream b = new ByteArrayOutputStream(Math.max(64, in.length * 3));
        byte[] buf = new byte[16384];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                b.write(buf, 0, n);
                if (b.size() > 64 * 1024 * 1024) break;
            }
        } catch (DataFormatException e) {
            // keep what was decoded before the damage
        } finally {
            inf.end();
        }
        return b.toByteArray();
    }

    private byte[] predictor(byte[] b, Dict p) {
        if (p == null) return b;
        int pred = (int) num(p.get("Predictor"), 1);
        if (pred < 10) return b;
        int colors = (int) num(p.get("Colors"), 1);
        int bpc = (int) num(p.get("BitsPerComponent"), 8);
        int columns = (int) num(p.get("Columns"), 1);
        int bpp = Math.max(1, colors * bpc / 8);
        int row = (colors * bpc * columns + 7) / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        byte[] prev = new byte[row];
        int i = 0;
        while (i + row < b.length + 1 && i < b.length) {
            int type = b[i++] & 0xFF;
            byte[] cur = new byte[row];
            for (int k = 0; k < row && i + k < b.length; k++) cur[k] = b[i + k];
            i += row;
            for (int k = 0; k < row; k++) {
                int left = k >= bpp ? cur[k - bpp] & 0xFF : 0;
                int up = prev[k] & 0xFF;
                int ul = k >= bpp ? prev[k - bpp] & 0xFF : 0;
                int x = cur[k] & 0xFF;
                switch (type) {
                    case 1: x += left; break;
                    case 2: x += up; break;
                    case 3: x += (left + up) / 2; break;
                    case 4: {
                        int pa = Math.abs(up - ul), pb = Math.abs(left - ul), pc = Math.abs(left + up - 2 * ul);
                        x += (pa <= pb && pa <= pc) ? left : (pb <= pc ? up : ul);
                        break;
                    }
                    default: break;
                }
                cur[k] = (byte) x;
            }
            out.write(cur, 0, row);
            prev = cur;
        }
        return out.toByteArray();
    }

    static byte[] lzw(byte[] in, boolean early) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 3);
        List<byte[]> table = new ArrayList<>();
        int bitPos = 0;
        int codeLen = 9;
        byte[] prev = null;
        for (int i = 0; i < 256; i++) table.add(new byte[]{(byte) i});
        table.add(null);
        table.add(null);
        while (true) {
            if (bitPos + codeLen > in.length * 8) break;
            int code = 0;
            for (int k = 0; k < codeLen; k++) {
                int bit = (in[(bitPos + k) >> 3] >> (7 - ((bitPos + k) & 7))) & 1;
                code = (code << 1) | bit;
            }
            bitPos += codeLen;
            if (code == 256) {
                table.subList(258, table.size()).clear();
                codeLen = 9;
                prev = null;
                continue;
            }
            if (code == 257) break;
            byte[] entry;
            if (code < table.size() && table.get(code) != null) {
                entry = table.get(code);
                if (prev != null) {
                    byte[] n = Arrays.copyOf(prev, prev.length + 1);
                    n[prev.length] = entry[0];
                    table.add(n);
                }
            } else if (prev != null) {
                entry = Arrays.copyOf(prev, prev.length + 1);
                entry[prev.length] = prev[0];
                table.add(entry);
            } else {
                break;
            }
            out.write(entry, 0, entry.length);
            prev = entry;
            int size = table.size() + (early ? 1 : 0);
            if (size > 4095) size = 4095;
            codeLen = size >= 2048 ? 12 : size >= 1024 ? 11 : size >= 512 ? 10 : 9;
        }
        return out.toByteArray();
    }

    static byte[] asciiHex(byte[] in) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(in.length / 2);
        int hi = -1;
        for (byte x : in) {
            if (x == '>') break;
            int v = Character.digit(x, 16);
            if (v < 0) continue;
            if (hi < 0) {
                hi = v;
            } else {
                b.write(hi * 16 + v);
                hi = -1;
            }
        }
        if (hi >= 0) b.write(hi * 16);
        return b.toByteArray();
    }

    static byte[] ascii85(byte[] in) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(in.length);
        long acc = 0;
        int n = 0;
        int i = 0;
        if (in.length > 1 && in[0] == '<' && in[1] == '~') i = 2;
        for (; i < in.length; i++) {
            int c = in[i] & 0xFF;
            if (c == '~') break;
            if (isWs(c)) continue;
            if (c == 'z' && n == 0) {
                b.write(0); b.write(0); b.write(0); b.write(0);
                continue;
            }
            if (c < '!' || c > 'u') continue;
            acc = acc * 85 + (c - '!');
            if (++n == 5) {
                for (int k = 3; k >= 0; k--) b.write((int) (acc >> (8 * k)) & 0xFF);
                acc = 0;
                n = 0;
            }
        }
        if (n > 1) {
            for (int k = n; k < 5; k++) acc = acc * 85 + 84;
            for (int k = 3; k > 3 - (n - 1); k--) b.write((int) (acc >> (8 * k)) & 0xFF);
        }
        return b.toByteArray();
    }

    // ------------------------------------------------------------------ encryption (empty user password)

    private static final byte[] PAD = {
            0x28, (byte) 0xBF, 0x4E, 0x5E, 0x4E, 0x75, (byte) 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, (byte) 0xFF, (byte) 0xFA, 0x01, 0x08,
            0x2E, 0x2E, 0x00, (byte) 0xB6, (byte) 0xD0, 0x68, 0x3E, (byte) 0x80, 0x2F, 0x0C, (byte) 0xA9, (byte) 0xFE, 0x64, 0x53, 0x69, 0x7A};

    private interface Decryptor {
        byte[] decrypt(byte[] in, int num, int gen) throws IOException;
    }

    private void setUpEncryption() throws LockedException {
        if (decryptor != null) return;
        Object e = trailer.get("Encrypt");
        if (e == null) return;
        Dict enc = dict(e);
        if (enc == null) return;
        String filter = name(enc.get("Filter"));
        if (filter != null && !filter.equals("Standard")) throw new LockedException();
        int v = (int) num(enc.get("V"), 0);
        int r = (int) num(enc.get("R"), 2);
        byte[] o = strBytes(enc.get("O"));
        byte[] u = strBytes(enc.get("U"));
        int p = (int) (long) num(enc.get("P"), 0);
        try {
            if (r >= 5) {
                byte[] ue = strBytes(enc.get("UE"));
                byte[] empty = new byte[0];
                byte[] check = r == 5
                        ? MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(u, 32, 40))
                        : PdfWriter.Crypto.hash(empty, Arrays.copyOfRange(u, 32, 40), empty);
                if (!Arrays.equals(Arrays.copyOf(check, 32), Arrays.copyOf(u, 32))) throw new LockedException();
                byte[] ik = r == 5
                        ? MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(u, 40, 48))
                        : PdfWriter.Crypto.hash(empty, Arrays.copyOfRange(u, 40, 48), empty);
                Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOf(ik, 32), "AES"), new IvParameterSpec(new byte[16]));
                final byte[] fileKey = c.doFinal(ue);
                decryptor = (in, num, gen) -> aesDecrypt(fileKey, in);
                return;
            }
            int lengthBits = (int) num(enc.get("Length"), 40);
            int n = r == 2 ? 5 : Math.max(5, Math.min(16, lengthBits / 8));
            List<Object> ids = array(trailer.get("ID"));
            byte[] id0 = ids != null && !ids.isEmpty() ? strBytes(ids.get(0)) : new byte[0];
            boolean encMeta = !(resolve(enc.get("EncryptMetadata")) instanceof Boolean) || (Boolean) resolve(enc.get("EncryptMetadata"));
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(PAD);
            md5.update(Arrays.copyOf(o, 32));
            md5.update(new byte[]{(byte) p, (byte) (p >> 8), (byte) (p >> 16), (byte) (p >> 24)});
            md5.update(id0);
            if (r >= 4 && !encMeta) md5.update(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
            byte[] key = md5.digest();
            if (r >= 3) for (int i = 0; i < 50; i++) key = MessageDigest.getInstance("MD5").digest(Arrays.copyOf(key, n));
            final byte[] fileKey = Arrays.copyOf(key, n);
            // check the empty password against U
            byte[] want;
            if (r == 2) {
                want = rc4(fileKey, PAD);
            } else {
                MessageDigest m = MessageDigest.getInstance("MD5");
                m.update(PAD);
                m.update(id0);
                want = rc4(fileKey, m.digest());
                for (int i = 1; i <= 19; i++) {
                    byte[] k2 = new byte[fileKey.length];
                    for (int j = 0; j < k2.length; j++) k2[j] = (byte) (fileKey[j] ^ i);
                    want = rc4(k2, want);
                }
            }
            int cmp = r == 2 ? 32 : 16;
            if (u.length < cmp || !Arrays.equals(Arrays.copyOf(want, cmp), Arrays.copyOf(u, cmp))) throw new LockedException();
            boolean aes = false;
            if (v >= 4) {
                Dict cf = dict(enc.get("CF"));
                String stmF = name(enc.get("StmF"));
                Dict f = cf != null && stmF != null ? dict(cf.get(stmF)) : null;
                String cfm = f != null ? name(f.get("CFM")) : null;
                if ("Identity".equals(stmF) || "None".equals(cfm)) return;
                aes = "AESV2".equals(cfm);
            }
            final boolean useAes = aes;
            decryptor = (in, num, gen) -> {
                try {
                    MessageDigest m = MessageDigest.getInstance("MD5");
                    m.update(fileKey);
                    m.update(new byte[]{(byte) num, (byte) (num >> 8), (byte) (num >> 16), (byte) gen, (byte) (gen >> 8)});
                    if (useAes) m.update(new byte[]{'s', 'A', 'l', 'T'});
                    byte[] k = Arrays.copyOf(m.digest(), Math.min(fileKey.length + 5, 16));
                    return useAes ? aesDecrypt(k, in) : rc4(k, in);
                } catch (GeneralSecurityException ex) {
                    throw new IOException(ex);
                }
            };
        } catch (GeneralSecurityException | ArrayIndexOutOfBoundsException ex) {
            throw new LockedException();
        }
    }

    private byte[] strBytes(Object o) {
        Object r = resolve(o);
        return r instanceof Str ? ((Str) r).b : new byte[0];
    }

    static byte[] rc4(byte[] key, byte[] in) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        for (int i = 0, j = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xFF)) & 0xFF;
            int t = s[i]; s[i] = s[j]; s[j] = t;
        }
        byte[] out = new byte[in.length];
        for (int k = 0, i = 0, j = 0; k < in.length; k++) {
            i = (i + 1) & 0xFF;
            j = (j + s[i]) & 0xFF;
            int t = s[i]; s[i] = s[j]; s[j] = t;
            out[k] = (byte) (in[k] ^ s[(s[i] + s[j]) & 0xFF]);
        }
        return out;
    }

    static byte[] aesDecrypt(byte[] key, byte[] in) throws IOException {
        if (in.length < 32) return new byte[0];
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOf(in, 16)));
            int len = (in.length - 16) / 16 * 16;
            byte[] out = c.doFinal(in, 16, len);
            int pad = out.length > 0 ? out[out.length - 1] & 0xFF : 0;
            if (pad >= 1 && pad <= 16 && pad <= out.length) out = Arrays.copyOf(out, out.length - pad);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------------ pages

    private List<String> pagesText() throws IOException {
        List<Dict[]> pages = new ArrayList<>();
        Dict root = dict(trailer.get("Root"));
        Dict tree = root != null ? dict(root.get("Pages")) : null;
        if (tree != null) collect(tree, null, pages, new HashSet<>(), 0);
        if (pages.isEmpty()) {
            // no usable page tree: take /Type /Page objects in object-number order
            List<Integer> nums = new ArrayList<>(objects.keySet());
            java.util.Collections.sort(nums);
            for (int k : nums) {
                Object o = objects.get(k);
                if (o instanceof Dict && "Page".equals(name(((Dict) o).get("Type")))) {
                    pages.add(new Dict[]{(Dict) o, dict(((Dict) o).get("Resources"))});
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (Dict[] pg : pages) {
            if (totalChars > MAX_CHARS) {
                out.add("");
                continue;
            }
            TextOut t = new TextOut();
            Object contents = resolve(pg[0].get("Contents"));
            List<Dict> streams = new ArrayList<>();
            if (contents instanceof Dict) {
                streams.add((Dict) contents);
            } else if (contents instanceof List) {
                for (Object c : array(contents)) {
                    Dict d = dict(c);
                    if (d != null) streams.add(d);
                }
            }
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (Dict s : streams) {
                try {
                    all.write(decode(s));
                    all.write('\n');
                } catch (IOException ignored) {
                    // unreadable content stream
                }
            }
            try {
                run(all.toByteArray(), pg[1], new float[]{1, 0, 0, 1, 0, 0}, t, 0);
            } catch (RuntimeException ignored) {
                // keep what was read before a malformed operator
            }
            String text = t.result();
            totalChars += text.length();
            out.add(text);
        }
        return out;
    }

    private void collect(Dict node, Dict inheritedRes, List<Dict[]> out, Set<Dict> seen, int depth) {
        if (node == null || depth > 64 || !seen.add(node) || out.size() > 20000) return;
        Dict res = node.containsKey("Resources") ? dict(node.get("Resources")) : inheritedRes;
        List<Object> kids = array(node.get("Kids"));
        String type = name(node.get("Type"));
        if (kids != null && !"Page".equals(type)) {
            for (Object k : kids) collect(dict(k), res, out, seen, depth + 1);
        } else {
            out.add(new Dict[]{node, res});
        }
    }

    // ------------------------------------------------------------------ fonts

    static final class Font {
        int[][] codespace;           // {bytes, lo, hi}
        boolean twoByte;
        boolean ucs2;
        Map<Integer, String> toUnicode;
        String[] simple;             // 256 entries for simple fonts
        float[] widths = new float[256];
        Map<Integer, Float> cidWidths;
        float defaultWidth = 500f;
        float scale = 0.001f;        // glyph space to text space
    }

    private Font font(Dict fd) {
        Font f = fonts.get(fd);
        if (f != null) return f;
        f = new Font();
        fonts.put(fd, f);
        String sub = name(fd.get("Subtype"));
        Dict tu = dict(fd.get("ToUnicode"));
        if (tu != null) {
            try {
                parseCMap(new String(decode(tu), StandardCharsets.ISO_8859_1), f);
            } catch (IOException ignored) {
                // no unicode map
            }
        }
        if ("Type0".equals(sub)) {
            f.twoByte = true;
            String enc = name(fd.get("Encoding"));
            if (enc != null && (enc.contains("UCS2") || enc.contains("UTF16"))) f.ucs2 = true;
            List<Object> desc = array(fd.get("DescendantFonts"));
            Dict cid = desc != null && !desc.isEmpty() ? dict(desc.get(0)) : null;
            if (cid != null) {
                f.defaultWidth = (float) num(cid.get("DW"), 1000);
                List<Object> w = array(cid.get("W"));
                if (w != null) {
                    f.cidWidths = new HashMap<>();
                    int i = 0;
                    while (i < w.size()) {
                        int c1 = (int) num(w.get(i), 0);
                        Object nx = i + 1 < w.size() ? resolve(w.get(i + 1)) : null;
                        if (nx instanceof List) {
                            List<Object> ws = array(nx);
                            for (int k = 0; k < ws.size(); k++) f.cidWidths.put(c1 + k, (float) num(ws.get(k), f.defaultWidth));
                            i += 2;
                        } else if (i + 2 < w.size()) {
                            int c2 = (int) num(nx, c1);
                            float wv = (float) num(w.get(i + 2), f.defaultWidth);
                            for (int c = c1; c <= c2 && c - c1 < 65536; c++) f.cidWidths.put(c, wv);
                            i += 3;
                        } else {
                            break;
                        }
                    }
                }
            }
        } else {
            f.simple = new String[256];
            Object enc = resolve(fd.get("Encoding"));
            String base = "TrueType".equals(sub) ? "WinAnsiEncoding" : "StandardEncoding";
            List<Object> diffs = null;
            if (enc instanceof Name) {
                base = ((Name) enc).v;
            } else if (enc instanceof Dict) {
                String b = name(((Dict) enc).get("BaseEncoding"));
                if (b != null) base = b;
                diffs = array(((Dict) enc).get("Differences"));
            }
            for (int c = 0; c < 256; c++) f.simple[c] = Glyphs.baseChar(base, c);
            if (diffs != null) {
                int code = 0;
                for (Object o : diffs) {
                    Object r = resolve(o);
                    if (r instanceof Double) {
                        code = (int) (double) (Double) r;
                    } else if (r instanceof Name) {
                        if (code >= 0 && code < 256) f.simple[code] = Glyphs.fromName(((Name) r).v);
                        code++;
                    }
                }
            }
            int first = (int) num(fd.get("FirstChar"), 0);
            List<Object> w = array(fd.get("Widths"));
            Arrays.fill(f.widths, 500f);
            if (w != null) {
                for (int k = 0; k < w.size() && first + k < 256; k++) f.widths[first + k] = (float) num(w.get(k), 500);
            } else {
                // standard 14 fonts carry no widths: use Courier's fixed pitch or Helvetica's metrics
                String bf = name(fd.get("BaseFont"));
                boolean mono = bf != null && bf.contains("Courier");
                for (int c = 32; c < 127; c++) f.widths[c] = mono ? 600f : PdfWriter.helveticaWidth(new byte[]{(byte) c});
            }
            if ("Type3".equals(sub)) {
                List<Object> m = array(fd.get("FontMatrix"));
                if (m != null && !m.isEmpty()) f.scale = (float) Math.abs(num(m.get(0), 0.001));
            }
        }
        return f;
    }

    /** Reads codespace ranges, bfchar and bfrange from a ToUnicode CMap. */
    static void parseCMap(String cmap, Font f) {
        f.toUnicode = new HashMap<>();
        List<int[]> spaces = new ArrayList<>();
        Lexer lx = new Lexer(cmap.getBytes(StandardCharsets.ISO_8859_1), 0, cmap.length());
        List<Object> ops = new ArrayList<>();
        Object o;
        while ((o = lx.next()) != null) {
            if (!(o instanceof Kw)) {
                ops.add(o);
                continue;
            }
            String k = ((Kw) o).v;
            if (k.equals("endcodespacerange")) {
                for (int i = 0; i + 1 < ops.size(); i += 2) {
                    if (ops.get(i) instanceof Str && ops.get(i + 1) instanceof Str) {
                        byte[] lo = ((Str) ops.get(i)).b;
                        spaces.add(new int[]{lo.length, toInt(lo), toInt(((Str) ops.get(i + 1)).b)});
                    }
                }
            } else if (k.equals("endbfchar")) {
                for (int i = 0; i + 1 < ops.size(); i += 2) {
                    if (ops.get(i) instanceof Str && ops.get(i + 1) instanceof Str) {
                        f.toUnicode.put(toInt(((Str) ops.get(i)).b), utf16(((Str) ops.get(i + 1)).b));
                    } else if (ops.get(i) instanceof Str && ops.get(i + 1) instanceof Name) {
                        f.toUnicode.put(toInt(((Str) ops.get(i)).b), Glyphs.fromName(((Name) ops.get(i + 1)).v));
                    }
                }
            } else if (k.equals("endbfrange")) {
                for (int i = 0; i + 2 < ops.size(); i += 3) {
                    if (!(ops.get(i) instanceof Str) || !(ops.get(i + 1) instanceof Str)) continue;
                    int lo = toInt(((Str) ops.get(i)).b);
                    int hi = toInt(((Str) ops.get(i + 1)).b);
                    Object dst = ops.get(i + 2);
                    if (hi - lo > 65535 || hi < lo) continue;
                    if (dst instanceof Str) {
                        byte[] base = ((Str) dst).b.clone();
                        for (int c = lo; c <= hi; c++) {
                            f.toUnicode.put(c, utf16(base));
                            if (base.length > 0) {
                                int last = base.length - 1;
                                int v = (base[last] & 0xFF) + 1;
                                base[last] = (byte) v;
                                if (v > 255 && last > 0) base[last - 1]++;
                            }
                        }
                    } else if (dst instanceof List) {
                        @SuppressWarnings("unchecked") List<Object> arr = (List<Object>) dst;
                        for (int c = lo; c <= hi && c - lo < arr.size(); c++) {
                            Object e = arr.get(c - lo);
                            if (e instanceof Str) f.toUnicode.put(c, utf16(((Str) e).b));
                        }
                    }
                }
            }
            ops.clear();
        }
        if (!spaces.isEmpty()) f.codespace = spaces.toArray(new int[0][]);
    }

    private static int toInt(byte[] b) {
        int v = 0;
        for (int i = 0; i < b.length && i < 4; i++) v = (v << 8) | (b[i] & 0xFF);
        return v;
    }

    private static String utf16(byte[] b) {
        if (b.length == 1) return String.valueOf((char) (b[0] & 0xFF));
        return new String(b, 0, b.length / 2 * 2, StandardCharsets.UTF_16BE);
    }

    /** Splits a string into character codes for this font. */
    private static int[] codes(Font f, byte[] s) {
        int[] out = new int[s.length];
        int n = 0;
        int i = 0;
        while (i < s.length) {
            int len = f.twoByte ? 2 : 1;
            if (f.codespace != null) {
                len = 0;
                for (int l = 1; l <= 4 && len == 0 && i + l <= s.length; l++) {
                    int v = 0;
                    for (int k = 0; k < l; k++) v = (v << 8) | (s[i + k] & 0xFF);
                    for (int[] cs : f.codespace) {
                        if (cs[0] == l && v >= cs[1] && v <= cs[2]) {
                            len = l;
                            break;
                        }
                    }
                }
                if (len == 0) len = f.twoByte ? 2 : 1;
            }
            int v = 0;
            for (int k = 0; k < len && i + k < s.length; k++) v = (v << 8) | (s[i + k] & 0xFF);
            out[n++] = v;
            i += len;
        }
        return Arrays.copyOf(out, n);
    }

    private static String charFor(Font f, int code) {
        if (f.toUnicode != null) {
            String u = f.toUnicode.get(code);
            if (u != null) return u;
        }
        if (f.ucs2) return String.valueOf((char) code);
        if (f.simple != null && code < 256) return f.simple[code] != null ? f.simple[code] : "";
        return "";
    }

    private static float widthOf(Font f, int code) {
        if (f.simple != null) return code < 256 ? f.widths[code] : 500f;
        if (f.cidWidths != null) {
            Float w = f.cidWidths.get(code);
            if (w != null) return w;
        }
        return f.defaultWidth;
    }

    // ------------------------------------------------------------------ content streams

    /** Collects text with line breaks and spaces inferred from positions. */
    static final class TextOut {
        final StringBuilder sb = new StringBuilder();
        float lastX = Float.NaN;
        float lastY = Float.NaN;
        boolean pendingSpace;

        void show(String s, float x, float y, float size, float endX) {
            if (s.isEmpty()) return;
            float h = Math.max(1f, Math.abs(size));
            if (!Float.isNaN(lastY)) {
                if (Math.abs(y - lastY) > h * 0.55f) {
                    newline();
                } else if (pendingSpace || x - lastX > h * 0.18f || x < lastX - h * 3f) {
                    space();
                }
            }
            pendingSpace = false;
            sb.append(s);
            lastX = endX;
            lastY = y;
        }

        void space() {
            int n = sb.length();
            if (n > 0 && sb.charAt(n - 1) != ' ' && sb.charAt(n - 1) != '\n') sb.append(' ');
        }

        void newline() {
            int n = sb.length();
            while (n > 0 && sb.charAt(n - 1) == ' ') sb.setLength(--n);
            if (n > 0 && sb.charAt(n - 1) != '\n') sb.append('\n');
        }

        String result() {
            String s = sb.toString()
                    .replace("ﬀ", "ff").replace("ﬁ", "fi").replace("ﬂ", "fl")
                    .replace("ﬃ", "ffi").replace("ﬄ", "ffl").replace("­", "")
                    .replace(' ', ' ').replace("\u0000", "");
            StringBuilder out = new StringBuilder(s.length());
            for (String line : s.split("\n")) {
                String t = line.replaceAll("[ \\t]+", " ").trim();
                if (t.isEmpty()) continue;
                int n = out.length();
                // join words hyphenated across a line break: "inform-" + "ation"
                if (n > 2 && out.charAt(n - 1) == '\n' && out.charAt(n - 2) == '-'
                        && Character.isLetter(out.charAt(Math.max(0, n - 3))) && Character.isLowerCase(t.charAt(0))) {
                    out.setLength(n - 2);
                }
                out.append(t).append('\n');
            }
            return out.toString().trim();
        }
    }

    private static float[] mul(float[] a, float[] b) {
        return new float[]{
                a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
                a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
                a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]};
    }

    private float[] matrix(List<Object> ops, int at) {
        float[] m = new float[6];
        for (int i = 0; i < 6; i++) m[i] = (float) num(ops.get(at + i), i == 0 || i == 3 ? 1 : 0);
        return m;
    }

    private void run(byte[] content, Dict resources, float[] ctmStart, TextOut out, int depth) {
        if (depth > 6) return;
        Lexer lx = new Lexer(content, 0, content.length);
        List<Object> ops = new ArrayList<>();
        List<float[]> stack = new ArrayList<>();
        float[] ctm = ctmStart.clone();
        float[] tm = {1, 0, 0, 1, 0, 0};
        float[] lm = tm.clone();
        Font font = null;
        float size = 1;
        float tc = 0;
        float tw = 0;
        float th = 1;
        float tl = 0;
        Dict fontRes = resources != null ? dict(resources.get("Font")) : null;
        Dict xobjRes = resources != null ? dict(resources.get("XObject")) : null;
        Object o;
        while ((o = lx.next()) != null) {
            if (!(o instanceof Kw)) {
                ops.add(o);
                if (ops.size() > 1024) ops.clear();
                continue;
            }
            String op = ((Kw) o).v;
            int n = ops.size();
            switch (op) {
                case "q":
                    stack.add(ctm.clone());
                    break;
                case "Q":
                    if (!stack.isEmpty()) ctm = stack.remove(stack.size() - 1);
                    break;
                case "cm":
                    if (n >= 6) ctm = mul(matrix(ops, n - 6), ctm);
                    break;
                case "BT":
                    tm = new float[]{1, 0, 0, 1, 0, 0};
                    lm = tm.clone();
                    break;
                case "ET":
                    break;
                case "Tf":
                    if (n >= 2) {
                        String fn = name(ops.get(n - 2));
                        Dict fd = fn != null && fontRes != null ? dict(fontRes.get(fn)) : null;
                        font = fd != null ? font(fd) : null;
                        size = (float) num(ops.get(n - 1), 1);
                    }
                    break;
                case "Tc":
                    if (n >= 1) tc = (float) num(ops.get(n - 1), 0);
                    break;
                case "Tw":
                    if (n >= 1) tw = (float) num(ops.get(n - 1), 0);
                    break;
                case "Tz":
                    if (n >= 1) th = (float) num(ops.get(n - 1), 100) / 100f;
                    break;
                case "TL":
                    if (n >= 1) tl = (float) num(ops.get(n - 1), 0);
                    break;
                case "Tm":
                    if (n >= 6) {
                        tm = matrix(ops, n - 6);
                        lm = tm.clone();
                    }
                    break;
                case "Td":
                case "TD":
                    if (n >= 2) {
                        float tx = (float) num(ops.get(n - 2), 0);
                        float ty = (float) num(ops.get(n - 1), 0);
                        if (op.equals("TD")) tl = -ty;
                        lm = mul(new float[]{1, 0, 0, 1, tx, ty}, lm);
                        tm = lm.clone();
                    }
                    break;
                case "T*":
                    lm = mul(new float[]{1, 0, 0, 1, 0, -tl}, lm);
                    tm = lm.clone();
                    break;
                case "'":
                case "\"":
                case "Tj":
                case "TJ": {
                    if (op.equals("'") || op.equals("\"")) {
                        if (op.equals("\"") && n >= 3) {
                            tw = (float) num(ops.get(n - 3), tw);
                            tc = (float) num(ops.get(n - 2), tc);
                        }
                        lm = mul(new float[]{1, 0, 0, 1, 0, -tl}, lm);
                        tm = lm.clone();
                    }
                    if (n < 1 || font == null) break;
                    Object arg = ops.get(n - 1);
                    List<Object> parts = new ArrayList<>();
                    if (arg instanceof Str) {
                        parts.add(arg);
                    } else if (arg instanceof List) {
                        @SuppressWarnings("unchecked") List<Object> l = (List<Object>) arg;
                        parts.addAll(l);
                    }
                    for (Object part : parts) {
                        if (part instanceof Double) {
                            float adj = (float) (double) (Double) part;
                            float tx = -adj / 1000f * size * th;
                            tm = mul(new float[]{1, 0, 0, 1, tx, 0}, tm);
                            if (adj < -180f) out.pendingSpace = true;
                            continue;
                        }
                        if (!(part instanceof Str)) continue;
                        int[] codes = codes(font, ((Str) part).b);
                        float[] start = mul(tm, ctm);
                        StringBuilder s = new StringBuilder();
                        float adv = 0;
                        for (int c : codes) {
                            s.append(charFor(font, c));
                            float w = widthOf(font, c) * font.scale * size + tc;
                            if (!font.twoByte && c == 32) w += tw;
                            adv += w * th;
                        }
                        tm = mul(new float[]{1, 0, 0, 1, adv, 0}, tm);
                        float[] endM = mul(tm, ctm);
                        float scale = (float) Math.sqrt(Math.abs(start[0] * start[3] - start[1] * start[2]));
                        out.show(s.toString(), start[4], start[5], size * scale, endM[4]);
                        if (out.sb.length() > 400_000) return;
                    }
                    break;
                }
                case "Do":
                    if (n >= 1 && xobjRes != null) {
                        Dict x = dict(xobjRes.get(name(ops.get(n - 1))));
                        if (x != null && "Form".equals(name(x.get("Subtype")))) {
                            try {
                                List<Object> mm = array(x.get("Matrix"));
                                float[] fm = mm != null && mm.size() == 6 ? matrix(mm, 0) : new float[]{1, 0, 0, 1, 0, 0};
                                Dict xr = x.containsKey("Resources") ? dict(x.get("Resources")) : resources;
                                run(decode(x), xr, mul(fm, ctm), out, depth + 1);
                            } catch (IOException ignored) {
                                // unreadable form
                            }
                        }
                    }
                    break;
                case "ID": {
                    // inline image data: skip to EI
                    int p = lx.p + 1;
                    while (p + 2 < content.length) {
                        if (content[p] == 'E' && content[p + 1] == 'I' && isWs(content[p - 1] & 0xFF)
                                && (p + 2 >= content.length || isWs(content[p + 2] & 0xFF))) break;
                        p++;
                    }
                    lx.p = Math.min(content.length, p + 2);
                    break;
                }
                default:
                    break;
            }
            ops.clear();
        }
    }
}
