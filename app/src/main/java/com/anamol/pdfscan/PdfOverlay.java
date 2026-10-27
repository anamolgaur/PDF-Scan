package com.anamol.pdfscan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;

/**
 * Saves an edited PDF without turning its pages into pictures. The original file is kept byte for
 * byte and an incremental update is appended to it (the way PDF editors save): pages in their new
 * order, any extra rotation, and the mark-up and signatures as a transparent picture laid over
 * each page. Text, vector drawings and fonts stay exactly as they were, so they stay sharp and
 * selectable. Pure Java, so it's tested on the desktop.
 */
final class PdfOverlay {
    private PdfOverlay() {
    }

    /** A transparent picture covering the whole page as it is shown (after rotation). */
    static final class Layer {
        final int width;
        final int height;
        final byte[] rgb;     // width * height * 3
        final byte[] alpha;   // width * height
        final boolean multiply;  // highlighter: darkens what's underneath instead of covering it

        Layer(int width, int height, byte[] rgb, byte[] alpha, boolean multiply) {
            this.width = width;
            this.height = height;
            this.rgb = rgb;
            this.alpha = alpha;
            this.multiply = multiply;
        }
    }

    /** One page of the result: which page of the original, turned how much more, with what on top. */
    static final class PageSpec {
        final int source;          // 0-based page of the original
        final int extraRotation;   // 0, 90, 180, 270 clockwise, on top of the page's own
        final List<Layer> layers = new ArrayList<>();

        PageSpec(int source, int extraRotation) {
            this.source = source;
            this.extraRotation = ((extraRotation % 360) + 360) % 360;
        }
    }

    /** Pages in the PDF, or -1 if it can't be updated this way (protected, damaged, no page tree). */
    static int pageCount(byte[] pdf) {
        try {
            if (startXref(pdf) < 0) return -1;
            PdfText t = PdfText.open(pdf);
            return t.catalog() == null ? -1 : t.pageList().size();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** True when the result would be the original unchanged. */
    static boolean isUnchanged(int sourcePages, List<PageSpec> pages) {
        if (pages.size() != sourcePages) return false;
        for (int i = 0; i < pages.size(); i++) {
            PageSpec p = pages.get(i);
            if (p.source != i || p.extraRotation != 0 || !p.layers.isEmpty()) return false;
        }
        return true;
    }

    static byte[] apply(byte[] pdf, List<PageSpec> pages) throws IOException {
        long prev = startXref(pdf);
        if (prev < 0) throw new IOException("No cross-reference table");
        PdfText t = PdfText.open(pdf);
        PdfText.Dict catalog = t.catalog();
        if (catalog == null || catalog.num < 0) throw new IOException("No document catalog");
        List<PdfText.Dict[]> src = t.pageList();
        if (src.isEmpty()) throw new IOException("No pages");
        for (PageSpec p : pages) {
            if (p.source < 0 || p.source >= src.size()) throw new IOException("Page out of range");
            if (src.get(p.source)[0].num < 0) throw new IOException("Page is not an object");
        }
        if (pages.isEmpty()) throw new IOException("No pages");
        if (isUnchanged(src.size(), pages)) return pdf;

        Writer w = new Writer(pdf, Math.max(t.maxObjectNumber() + 1, (int) t.numberOf(t.trailer().get("Size"), 0)));
        int treeNum = w.allocate();
        List<Object> kids = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        int overlayNo = 0;

        for (PageSpec spec : pages) {
            PdfText.Dict orig = src.get(spec.source)[0];
            PdfText.Dict res = src.get(spec.source)[1];
            int pageNum = used.add(orig.num) ? orig.num : w.allocate();
            int pageGen = pageNum == orig.num ? orig.gen : 0;

            PdfText.Dict page = new PdfText.Dict();
            for (Map.Entry<String, Object> e : orig.entrySet()) page.put(e.getKey(), e.getValue());
            if (pageNum != orig.num) page.remove("Annots");  // an annotation belongs to one page
            page.put("Parent", new PdfText.Ref(treeNum, 0));
            for (String key : new String[]{"MediaBox", "CropBox"}) {
                Object v = t.inherited(orig, key);
                if (v != null) page.put(key, v);
            }
            int ownRotate = (int) t.numberOf(t.inherited(orig, "Rotate"), 0);
            int rotate = (((ownRotate + spec.extraRotation) % 360) + 360) % 360;
            rotate = rotate / 90 * 90;
            if (rotate != 0 || page.containsKey("Rotate") || ownRotate != 0) page.put("Rotate", (double) rotate);

            PdfText.Dict newRes = new PdfText.Dict();
            if (res != null) for (Map.Entry<String, Object> e : res.entrySet()) newRes.put(e.getKey(), e.getValue());

            if (!spec.layers.isEmpty()) {
                float[] box = box(t, orig);
                PdfText.Dict xobjects = copyOf(t.dictOf(newRes.get("XObject")));
                PdfText.Dict states = copyOf(t.dictOf(newRes.get("ExtGState")));
                StringBuilder draw = new StringBuilder("Q\n");
                String cm = matrix(box, rotate);
                for (Layer layer : spec.layers) {
                    overlayNo++;
                    String imgName = unique(xobjects, "PdfScanMark" + overlayNo);
                    xobjects.put(imgName, new PdfText.Ref(w.image(layer), 0));
                    draw.append("q\n");
                    if (layer.multiply) {
                        String gs = unique(states, "PdfScanMultiply");
                        if (!states.containsKey(gs)) {
                            PdfText.Dict m = new PdfText.Dict();
                            m.put("Type", new PdfText.Name("ExtGState"));
                            m.put("BM", new PdfText.Name("Multiply"));
                            states.put(gs, m);
                        }
                        draw.append('/').append(gs).append(" gs\n");
                    }
                    draw.append(cm).append(" cm\n/").append(imgName).append(" Do\nQ\n");
                }
                newRes.put("XObject", xobjects);
                if (!states.isEmpty()) newRes.put("ExtGState", states);

                List<Object> contents = new ArrayList<>();
                contents.add(new PdfText.Ref(w.stream("q\n".getBytes(StandardCharsets.ISO_8859_1)), 0));
                Object c = orig.get("Contents");
                Object cr = t.dictOf(c) != null ? null : t.arrayOf(c);
                if (cr instanceof List) {
                    contents.addAll((List<?>) cr);
                } else if (c != null) {
                    contents.add(c);
                }
                contents.add(new PdfText.Ref(w.stream(draw.toString().getBytes(StandardCharsets.ISO_8859_1)), 0));
                page.put("Contents", contents);
            }
            page.put("Resources", newRes);
            w.object(pageNum, pageGen, page);
            kids.add(new PdfText.Ref(pageNum, pageGen));
        }

        PdfText.Dict tree = new PdfText.Dict();
        tree.put("Type", new PdfText.Name("Pages"));
        tree.put("Kids", kids);
        tree.put("Count", (double) kids.size());
        w.object(treeNum, 0, tree);

        PdfText.Dict newCatalog = new PdfText.Dict();
        for (Map.Entry<String, Object> e : catalog.entrySet()) newCatalog.put(e.getKey(), e.getValue());
        newCatalog.put("Pages", new PdfText.Ref(treeNum, 0));
        w.object(catalog.num, catalog.gen, newCatalog);

        return w.finish(prev, usesXrefStream(pdf, prev), t.trailer(), new PdfText.Ref(catalog.num, catalog.gen));
    }

    /** The visible area: CropBox (within MediaBox), else MediaBox, else A4. */
    private static float[] box(PdfText t, PdfText.Dict page) {
        float[] media = rect(t, t.inherited(page, "MediaBox"));
        if (media == null) media = new float[]{0, 0, 595, 842};
        float[] crop = rect(t, t.inherited(page, "CropBox"));
        if (crop == null) return media;
        float[] r = {Math.max(crop[0], media[0]), Math.max(crop[1], media[1]),
                Math.min(crop[2], media[2]), Math.min(crop[3], media[3])};
        return r[2] > r[0] && r[3] > r[1] ? r : media;
    }

    private static float[] rect(PdfText t, Object o) {
        List<Object> a = t.arrayOf(o);
        if (a == null || a.size() < 4) return null;
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) r[i] = (float) t.numberOf(a.get(i), 0);
        float[] n = {Math.min(r[0], r[2]), Math.min(r[1], r[3]), Math.max(r[0], r[2]), Math.max(r[1], r[3])};
        return n[2] > n[0] && n[3] > n[1] ? n : null;
    }

    /**
     * Maps the unit square of an image (the page as shown, top row first) onto the page's own
     * coordinates, undoing the page's rotation.
     */
    static String matrix(float[] b, int rotate) {
        float x0 = b[0], y0 = b[1], x1 = b[2], y1 = b[3];
        float w = x1 - x0, h = y1 - y0;
        float[] m;
        switch (rotate) {
            case 90:
                m = new float[]{0, h, -w, 0, x1, y0};
                break;
            case 180:
                m = new float[]{-w, 0, 0, -h, x1, y1};
                break;
            case 270:
                m = new float[]{0, -h, w, 0, x0, y1};
                break;
            default:
                m = new float[]{w, 0, 0, h, x0, y0};
                break;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(' ');
            sb.append(PdfText.number(m[i]));
        }
        return sb.toString();
    }

    private static PdfText.Dict copyOf(PdfText.Dict d) {
        PdfText.Dict c = new PdfText.Dict();
        if (d != null) for (Map.Entry<String, Object> e : d.entrySet()) c.put(e.getKey(), e.getValue());
        return c;
    }

    private static String unique(Map<String, Object> m, String base) {
        if (!m.containsKey(base) || base.equals("PdfScanMultiply")) return base;
        for (int i = 2; ; i++) if (!m.containsKey(base + "_" + i)) return base + "_" + i;
    }

    /** Offset of the last cross-reference section, or -1. */
    static long startXref(byte[] pdf) {
        byte[] key = "startxref".getBytes(StandardCharsets.ISO_8859_1);
        int from = Math.max(0, pdf.length - 4096);
        int at = -1;
        for (int i = pdf.length - key.length; i >= from; i--) {
            if (PdfText.indexOf(pdf, key, i, i + key.length) == i) {
                at = i;
                break;
            }
        }
        if (at < 0) return -1;
        int p = at + key.length;
        while (p < pdf.length && PdfText.isWs(pdf[p] & 0xFF)) p++;
        long v = 0;
        int digits = 0;
        while (p < pdf.length && pdf[p] >= '0' && pdf[p] <= '9' && digits < 12) {
            v = v * 10 + (pdf[p] - '0');
            p++;
            digits++;
        }
        return digits == 0 || v >= pdf.length ? -1 : v;
    }

    private static boolean usesXrefStream(byte[] pdf, long at) {
        int p = (int) at;
        while (p < pdf.length && PdfText.isWs(pdf[p] & 0xFF)) p++;
        return !(p + 4 <= pdf.length && pdf[p] == 'x' && pdf[p + 1] == 'r' && pdf[p + 2] == 'e' && pdf[p + 3] == 'f');
    }

    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.length / 8));
        byte[] buf = new byte[65536];
        while (!d.finished()) {
            int n = d.deflate(buf);
            out.write(buf, 0, n);
        }
        d.end();
        return out.toByteArray();
    }

    /** Appends objects after the original bytes and writes the cross-reference section. */
    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final Map<Integer, long[]> offsets = new LinkedHashMap<>();  // num -> {offset, gen}
        private int next;

        Writer(byte[] original, int firstFree) throws IOException {
            out.write(original);
            if (original.length > 0 && original[original.length - 1] != '\n' && original[original.length - 1] != '\r') {
                out.write('\n');
            }
            next = firstFree;
        }

        int allocate() {
            return next++;
        }

        private void begin(int num, int gen) throws IOException {
            offsets.put(num, new long[]{out.size(), gen});
            ascii(num + " " + gen + " obj\n");
        }

        private void ascii(String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.ISO_8859_1));
        }

        void object(int num, int gen, PdfText.Dict d) throws IOException {
            begin(num, gen);
            StringBuilder sb = new StringBuilder();
            PdfText.serialize(d, sb, true);
            ascii(sb.append("\nendobj\n").toString());
        }

        private int rawStream(PdfText.Dict head, byte[] body) throws IOException {
            int num = allocate();
            begin(num, 0);
            head.put("Length", (double) body.length);
            StringBuilder sb = new StringBuilder();
            PdfText.serialize(head, sb, true);
            ascii(sb.append("\nstream\n").toString());
            out.write(body);
            ascii("\nendstream\nendobj\n");
            return num;
        }

        int stream(byte[] content) throws IOException {
            PdfText.Dict d = new PdfText.Dict();
            byte[] z = deflate(content);
            if (z.length < content.length) {
                d.put("Filter", new PdfText.Name("FlateDecode"));
                return rawStream(d, z);
            }
            return rawStream(d, content);
        }

        int image(Layer l) throws IOException {
            PdfText.Dict mask = imageHead(l, "DeviceGray");
            mask.put("Filter", new PdfText.Name("FlateDecode"));
            int maskNum = rawStream(mask, deflate(l.alpha));
            PdfText.Dict img = imageHead(l, "DeviceRGB");
            img.put("Filter", new PdfText.Name("FlateDecode"));
            img.put("SMask", new PdfText.Ref(maskNum, 0));
            return rawStream(img, deflate(l.rgb));
        }

        private static PdfText.Dict imageHead(Layer l, String space) {
            PdfText.Dict d = new PdfText.Dict();
            d.put("Type", new PdfText.Name("XObject"));
            d.put("Subtype", new PdfText.Name("Image"));
            d.put("Width", (double) l.width);
            d.put("Height", (double) l.height);
            d.put("ColorSpace", new PdfText.Name(space));
            d.put("BitsPerComponent", 8.0);
            return d;
        }

        byte[] finish(long prev, boolean xrefStream, PdfText.Dict oldTrailer, PdfText.Ref root) throws IOException {
            PdfText.Dict trailer = new PdfText.Dict();
            trailer.put("Root", root);
            Object info = oldTrailer.get("Info");
            if (info instanceof PdfText.Ref) trailer.put("Info", info);
            Object id = oldTrailer.get("ID");
            if (id instanceof List) trailer.put("ID", id);
            trailer.put("Prev", (double) prev);
            List<Integer> nums = new ArrayList<>(offsets.keySet());
            if (xrefStream) {
                int self = allocate();
                long selfAt = out.size();
                offsets.put(self, new long[]{selfAt, 0});
                nums.add(self);
                java.util.Collections.sort(nums);
                ByteArrayOutputStream rows = new ByteArrayOutputStream();
                List<Object> index = new ArrayList<>();
                for (int n : nums) {
                    long[] o = offsets.get(n);
                    rows.write(1);
                    for (int s = 24; s >= 0; s -= 8) rows.write((int) (o[0] >> s) & 0xFF);
                    rows.write((int) (o[1] >> 8) & 0xFF);
                    rows.write((int) o[1] & 0xFF);
                    index.add((double) n);
                    index.add(1.0);
                }
                trailer.put("Type", new PdfText.Name("XRef"));
                trailer.put("Size", (double) next);
                trailer.put("Index", index);
                List<Object> widths = new ArrayList<>();
                widths.add(1.0);
                widths.add(4.0);
                widths.add(2.0);
                trailer.put("W", widths);
                byte[] body = rows.toByteArray();
                trailer.put("Length", (double) body.length);
                ascii(self + " 0 obj\n");
                StringBuilder sb = new StringBuilder();
                PdfText.serialize(trailer, sb, true);
                ascii(sb.append("\nstream\n").toString());
                out.write(body);
                ascii("\nendstream\nendobj\nstartxref\n" + selfAt + "\n%%EOF\n");
            } else {
                java.util.Collections.sort(nums);
                long at = out.size();
                StringBuilder sb = new StringBuilder("xref\n");
                for (int n : nums) {
                    long[] o = offsets.get(n);
                    sb.append(n).append(" 1\n")
                            .append(String.format(java.util.Locale.ROOT, "%010d %05d n\r\n", o[0], o[1]));
                }
                trailer.put("Size", (double) next);
                sb.append("trailer\n");
                PdfText.serialize(trailer, sb, true);
                sb.append("\nstartxref\n").append(at).append("\n%%EOF\n");
                ascii(sb.toString());
            }
            return out.toByteArray();
        }
    }
}
