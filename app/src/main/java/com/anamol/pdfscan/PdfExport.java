package com.anamol.pdfscan;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns finished pages into a PDF with PdfWriter: size presets, the invisible OCR text layer,
 * and optional AES-256 password protection.
 *
 * High quality keeps the stored page images as they are. Balanced re-encodes at 1700 px.
 * Small re-encodes colour pages at 1200 px and stores black-and-white pages as 1-bit images.
 */
final class PdfExport {
    private PdfExport() {
    }

    static void write(List<File> pages, String size, String password, String title, OutputStream out)
            throws IOException {
        if (password == null) {
            byte[] sharp = sharp(pages);
            if (sharp != null) {
                out.write(sharp);
                return;
            }
        }
        List<PdfWriter.Page> list = new ArrayList<>();
        for (File f : pages) list.add(page(f, size));
        PdfWriter.Options o = new PdfWriter.Options();
        o.title = title;
        o.password = password;
        PdfWriter.write(list, o, out);
    }

    /**
     * Pages that came from one PDF and were only marked up, signed, turned, reordered, copied or
     * removed are saved over that PDF's own pages (PdfOverlay), so its text and drawings stay
     * sharp. Null when any page was changed in a way only a picture can show (crop, filters,
     * cleanup...), or came from somewhere else; the pages are then written as pictures.
     */
    static byte[] sharp(List<File> pages) {
        if (pages.isEmpty()) return null;
        try {
            String source = null;
            List<PageMeta> metas = new ArrayList<>();
            for (File f : pages) {
                PageMeta m = PageMeta.read(f);
                if (m.srcPdf == null || m.srcPage < 0) return null;
                if (source == null) source = m.srcPdf;
                if (!source.equals(m.srcPdf)) return null;
                boolean plain = m.corners == null && ImageOps.ORIGINAL.equals(m.filter) && !m.glare
                        && m.brightness == 0 && m.contrast == 0 && m.sharpen == 0 && m.curve == 0
                        && m.erase.isEmpty();
                // recognised text on a picture-only page is worth more than keeping it as it was
                boolean ocr = !m.fromPdfText && m.text != null && m.text.trim().length() >= 20;
                if (!plain || ocr) return null;
                metas.add(m);
            }
            File src = new File(pages.get(0).getParentFile(), source);
            if (!src.isFile()) return null;
            byte[] pdf = Files.readAllBytes(src.toPath());
            int count = PdfOverlay.pageCount(pdf);
            if (count <= 0) return null;
            List<PdfOverlay.PageSpec> specs = new ArrayList<>();
            for (int i = 0; i < pages.size(); i++) {
                PageMeta m = metas.get(i);
                if (m.srcPage >= count) return null;
                PdfOverlay.PageSpec spec = new PdfOverlay.PageSpec(m.srcPage, m.rotation);
                if (!m.marks.isEmpty()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(pages.get(i).getAbsolutePath(), o);
                    if (o.outWidth <= 0 || o.outHeight <= 0) return null;
                    float scale = Math.min(1f, 2400f / Math.max(o.outWidth, o.outHeight));
                    int w = Math.max(1, Math.round(o.outWidth * scale));
                    int h = Math.max(1, Math.round(o.outHeight * scale));
                    List<PageMeta.Mark> highlights = new ArrayList<>();
                    List<PageMeta.Mark> ink = new ArrayList<>();
                    for (PageMeta.Mark k : m.marks) {
                        (PageMeta.Mark.HIGHLIGHT.equals(k.type) ? highlights : ink).add(k);
                    }
                    if (!highlights.isEmpty()) spec.layers.add(layer(highlights, w, h, true));
                    if (!ink.isEmpty()) spec.layers.add(layer(ink, w, h, false));
                }
                specs.add(spec);
            }
            return PdfOverlay.apply(pdf, specs);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;  // fall back to pictures
        }
    }

    private static PdfOverlay.Layer layer(List<PageMeta.Mark> marks, int w, int h, boolean multiply) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            b.eraseColor(0);
            Marks.draw(new android.graphics.Canvas(b), marks, w, h);
            int[] row = new int[w];
            byte[] rgb = new byte[w * h * 3];
            byte[] alpha = new byte[w * h];
            for (int y = 0; y < h; y++) {
                b.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) {
                    int c = row[x];
                    int i = y * w + x;
                    int a = c >>> 24;
                    alpha[i] = (byte) a;
                    if (a != 0) {
                        rgb[i * 3] = (byte) (c >> 16);
                        rgb[i * 3 + 1] = (byte) (c >> 8);
                        rgb[i * 3 + 2] = (byte) c;
                    }
                }
            }
            return new PdfOverlay.Layer(w, h, rgb, alpha, multiply);
        } finally {
            b.recycle();
        }
    }

    private static PdfWriter.Page page(File f, String size) throws IOException {
        PageMeta m = PageMeta.read(f);
        PdfWriter.Page p;
        if (Prefs.SIZE_HIGH.equals(size)) {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), b);
            if (b.outWidth <= 0) throw new IOException("Unreadable page " + f.getName());
            p = PdfWriter.Page.jpeg(Files.readAllBytes(f.toPath()), b.outWidth, b.outHeight);
        } else {
            boolean small = Prefs.SIZE_SMALL.equals(size);
            boolean twoTone = ImageOps.BW.equals(m.filter) || (ImageOps.SCAN.equals(m.filter) && m.marks.isEmpty());
            int max = small && !twoTone ? 1200 : 1700;
            Bitmap bmp = ImageProcessor.decodeFile(f, max);
            try {
                if (small && twoTone) {
                    int w = bmp.getWidth();
                    int h = bmp.getHeight();
                    int[] px = new int[w * h];
                    bmp.getPixels(px, 0, w, 0, 0, w, h);
                    p = PdfWriter.Page.bilevel(px, w, h, 150);
                } else {
                    ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, small ? 55 : 72, jpg);
                    p = PdfWriter.Page.jpeg(jpg.toByteArray(), bmp.getWidth(), bmp.getHeight());
                }
            } finally {
                bmp.recycle();
            }
        }
        if (m.hasText() && m.ocrW > 0 && m.ocrH > 0) {
            for (int i = 0; i < m.words.length; i++) {
                p.words.add(new PdfWriter.Word(m.words[i], m.boxes[i * 4], m.boxes[i * 4 + 1],
                        m.boxes[i * 4 + 2], m.boxes[i * 4 + 3]));
            }
            p.textW = m.ocrW;
            p.textH = m.ocrH;
        } else if (m.hasText() && m.words.length == 0 && !m.text.trim().isEmpty()) {
            // text taken from an imported PDF has no positions: lay its lines down the page so
            // the exported PDF can still be searched and its text selected
            String[] lines = m.text.split("\\n");
            int n = lines.length;
            float step = 900f / Math.max(1, n);
            float lh = Math.min(28f, step * 0.8f);
            for (int i = 0; i < n; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                p.words.add(new PdfWriter.Word(line, 60, 50 + i * step, 880, lh));
            }
            p.textW = 1000;
            p.textH = 1000;
        }
        return p;
    }
}
