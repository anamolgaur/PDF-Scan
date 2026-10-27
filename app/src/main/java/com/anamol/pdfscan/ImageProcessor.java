package com.anamol.pdfscan;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Decoding, page rendering (rotate, perspective crop, filter) and import. All on-device. */
final class ImageProcessor {
    static final int MAX_SIDE = 2200;

    private interface Source {
        InputStream open() throws IOException;
    }

    private ImageProcessor() {
    }

    // ------------------------------------------------------------------ decoding

    static Bitmap decodeScaled(ContentResolver cr, Uri uri, int maxSide) throws IOException {
        return decode(() -> {
            InputStream in = cr.openInputStream(uri);
            if (in == null) throw new IOException("Cannot open file");
            return in;
        }, maxSide);
    }

    static Bitmap decodeFile(File f, int maxSide) throws IOException {
        return decode(() -> new FileInputStream(f), maxSide);
    }

    /** Down-samples so the long side is at most maxSide, and applies EXIF rotation. */
    private static Bitmap decode(Source src, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = src.open()) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not a readable image");

        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = src.open()) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IOException("Not a readable image");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = src.open()) {
            orientation = new ExifInterface(in)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
            // no EXIF data
        }
        int degrees = 0;
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;

        // Exact final size: scale the long side down to maxSide and rotate in one pass.
        float scale = Math.min(1f, maxSide / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
        if (degrees != 0 || scale < 1f) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(degrees);
            Bitmap t = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (t != bmp) bmp.recycle();
            bmp = t;
        }
        return bmp;
    }

    static void saveJpeg(Bitmap bmp, File out) throws IOException {
        try (FileOutputStream os = new FileOutputStream(out)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 88, os)) {
                throw new IOException("JPEG encoding failed");
            }
        }
    }

    // ------------------------------------------------------------------ page rendering

    static float[] detectCorners(Bitmap b) {
        float s = 320f / Math.max(b.getWidth(), b.getHeight());
        Bitmap small = s < 1f
                ? Bitmap.createScaledBitmap(b, Math.max(8, Math.round(b.getWidth() * s)),
                Math.max(8, Math.round(b.getHeight() * s)), true)
                : b;
        int w = small.getWidth();
        int h = small.getHeight();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != b) small.recycle();
        return ScanMath.detectCorners(px, w, h);
    }

    /** Applies look settings, cleanup and mark-up to a cropped page. Returns a new bitmap. */
    static Bitmap look(Bitmap page, PageMeta m, boolean withMarks) {
        int w = page.getWidth();
        int h = page.getHeight();
        int[] px = new int[w * h];
        page.getPixels(px, 0, w, 0, 0, w, h);
        ImageOps.apply(px, w, h, m.adjust());
        if (!m.erase.isEmpty()) ImageOps.fillWithPaper(px, w, h, Marks.mask(m.erase, w, h));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        if (withMarks && !m.marks.isEmpty()) Marks.draw(new Canvas(out), m.marks, w, h);
        return out;
    }

    /** Perspective-corrects the quad (normalised corners) into a flat rectangle. */
    static Bitmap warp(Bitmap b, float[] c) {
        int bw = b.getWidth();
        int bh = b.getHeight();
        float[] p = new float[8];
        for (int i = 0; i < 4; i++) {
            p[2 * i] = c[2 * i] * bw;
            p[2 * i + 1] = c[2 * i + 1] * bh;
        }
        float top = dist(p, 0, 1);
        float bottom = dist(p, 3, 2);
        float left = dist(p, 0, 3);
        float right = dist(p, 1, 2);
        float ow = Math.max(top, bottom);
        float oh = Math.max(left, right);
        float cap = Math.min(1f, MAX_SIDE / Math.max(ow, oh));
        int w = Math.max(16, Math.round(ow * cap));
        int h = Math.max(16, Math.round(oh * cap));
        float[] dst = {0, 0, w, 0, w, h, 0, h};
        Matrix m = new Matrix();
        if (!m.setPolyToPoly(p, 0, dst, 0, 4)) {
            return b.copy(Bitmap.Config.ARGB_8888, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(b, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    private static float dist(float[] p, int a, int b) {
        return (float) Math.hypot(p[2 * a] - p[2 * b], p[2 * a + 1] - p[2 * b + 1]);
    }

    /** Rotation and perspective crop only. May return src itself; never recycles src. */
    static Bitmap geometry(Bitmap src, PageMeta m) {
        Bitmap cur = src;
        if (m.rotation % 360 != 0) {
            Matrix r = new Matrix();
            r.postRotate(m.rotation);
            cur = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), r, true);
        }
        if (!ScanMath.isFull(m.corners)) {
            Bitmap w = warp(cur, m.corners);
            if (cur != src) cur.recycle();
            cur = w;
        }
        if (m.curve != 0f) {
            int w = cur.getWidth();
            int h = cur.getHeight();
            int[] px = new int[w * h];
            cur.getPixels(px, 0, w, 0, 0, w, h);
            int[] flat = ScanMath.flattenCurve(px, w, h, m.curve, m.spineLeft);
            Bitmap f = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            f.setPixels(flat, 0, w, 0, 0, w, h);
            if (cur != src) cur.recycle();
            cur = f;
        }
        return cur;
    }

    /** The finished page: geometry, look, cleanup and mark-up. Returns a new bitmap; never recycles src. */
    static Bitmap render(Bitmap src, PageMeta m) {
        Bitmap g = geometry(src, m);
        Bitmap out = look(g, m, true);
        if (g != src) g.recycle();
        return out;
    }

    /**
     * Re-renders a page from its original using the given settings, and saves both.
     * Pages without an original (imported or older pages) adopt the current image as original.
     */
    static void renderPage(File jpg, PageMeta m) throws IOException {
        synchronized (Indexer.LOCK) {
            renderPageLocked(jpg, m);
        }
    }

    private static void renderPageLocked(File jpg, PageMeta m) throws IOException {
        File src = DocStore.srcFile(jpg);
        if (!src.exists()) Files.copy(jpg.toPath(), src.toPath());
        Bitmap s = decodeFile(src, MAX_SIDE);
        Bitmap out = render(s, m);
        try {
            saveJpeg(out, jpg);
        } finally {
            out.recycle();
            s.recycle();
        }
        m.clearText();
        m.write(jpg);
    }

    /** Adds a new page at the end of dir from an original image file (which is moved in). */
    static File createPage(DocStore store, File dir, File original, PageMeta m) throws IOException {
        synchronized (Indexer.LOCK) {
            File jpg = store.newPage(dir);
            Files.move(original.toPath(), DocStore.srcFile(jpg).toPath(), StandardCopyOption.REPLACE_EXISTING);
            renderPageLocked(jpg, m);
            return jpg;
        }
    }

    // ------------------------------------------------------------------ import

    /** Writes a finished page image; locked so the indexer never reads a half-written file. */
    private static File addPage(Bitmap b, DocStore store, File dir) throws IOException {
        synchronized (Indexer.LOCK) {
            File f = store.newPage(dir);
            saveJpeg(b, f);
            return f;
        }
    }

    /** Imports PDFs and images, in order, as new pages at the end of dir. Returns pages added. */
    static int importInto(ContentResolver cr, List<Uri> uris, DocStore store, File dir) throws IOException {
        return importInto(cr, uris, store, dir, false);
    }

    /**
     * keepSource: also keep a copy of each PDF in the document, so pages that are only marked up,
     * turned or reordered can be saved over the original without turning them into pictures.
     */
    static int importInto(ContentResolver cr, List<Uri> uris, DocStore store, File dir, boolean keepSource)
            throws IOException {
        int added = 0;
        for (Uri uri : uris) {
            String mime = cr.getType(uri);
            if ("application/pdf".equals(mime) || (!(mime != null && mime.startsWith("image/")) && looksLikePdf(cr, uri))) {
                added += importPdf(cr, uri, store, dir, keepSource);
            } else {
                Bitmap b = decodeScaled(cr, uri, MAX_SIDE);
                try {
                    addPage(b, store, dir);
                } finally {
                    b.recycle();
                }
                added++;
            }
        }
        if (added == 0) throw new IOException("Nothing to import");
        return added;
    }

    /**
     * ID card page: the front and (optionally) the back of a card, each straightened and placed
     * at real size (85.6 x 54 mm) on one A4 page at 300 dpi, front above back.
     */
    static void composeIdCard(File front, float[] frontHint, File back, float[] backHint, File out) throws IOException {
        int pw = 2480, ph = 3508, cw = 1011, ch = 638;
        Bitmap page = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(page);
        cv.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        int x = (pw - cw) / 2;
        int y = 420;
        Bitmap f = cardImage(front, frontHint);
        cv.drawBitmap(f, null, fitInto(f, x, y, cw, ch), p);
        f.recycle();
        if (back != null) {
            Bitmap b = cardImage(back, backHint);
            cv.drawBitmap(b, null, fitInto(b, x, y + ch + 260, cw, ch), p);
            b.recycle();
        }
        try {
            saveJpeg(page, out);
        } finally {
            page.recycle();
        }
    }

    private static Bitmap cardImage(File photo, float[] hint) throws IOException {
        Bitmap b = decodeFile(photo, MAX_SIDE);
        float[] c = detectCorners(b);
        if (c == null) c = hint;
        Bitmap card = b;
        if (c != null && !ScanMath.isFull(c)) {
            card = warp(b, c);
            b.recycle();
        }
        if (card.getHeight() > card.getWidth()) {  // cards are landscape
            Matrix m = new Matrix();
            m.postRotate(90);
            Bitmap r = Bitmap.createBitmap(card, 0, 0, card.getWidth(), card.getHeight(), m, true);
            card.recycle();
            card = r;
        }
        return card;
    }

    private static android.graphics.Rect fitInto(Bitmap b, int x, int y, int w, int h) {
        float s = Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
        int dw = Math.round(b.getWidth() * s), dh = Math.round(b.getHeight() * s);
        int dx = x + (w - dw) / 2, dy = y + (h - dh) / 2;
        return new android.graphics.Rect(dx, dy, dx + dw, dy + dh);
    }

    /** Some apps share PDFs without a type: check the file's first bytes. */
    private static boolean looksLikePdf(ContentResolver cr, Uri uri) {
        try (java.io.InputStream in = cr.openInputStream(uri)) {
            if (in == null) return false;
            byte[] head = new byte[1024];
            int n = in.read(head);
            String s = new String(head, 0, Math.max(0, n), java.nio.charset.StandardCharsets.ISO_8859_1);
            return s.contains("%PDF-");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Renders each PDF page to a JPEG page in the document folder. Returns the page count. */
    static int importPdf(ContentResolver cr, Uri uri, DocStore store, File dir, boolean keepSource) throws IOException {
        int count = 0;
        byte[] whole = null;
        // reuse the PDF's own text where it has any, instead of recognising it again from images
        List<String> texts = null;
        try (java.io.InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 80 * 1024 * 1024) break;
                }
                if (bo.size() <= 80 * 1024 * 1024) {
                    whole = bo.toByteArray();
                    texts = PdfText.extract(whole);
                }
            }
        } catch (IOException | RuntimeException e) {
            texts = null;  // protected or unreadable text: pages get recognised as usual
        }
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("Cannot open PDF");
            try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                String source = null;
                if (keepSource && whole != null && PdfOverlay.pageCount(whole) == renderer.getPageCount()) {
                    source = "source-" + System.currentTimeMillis() + ".pdf";
                    java.nio.file.Files.write(new File(dir, source).toPath(), whole);
                }
                for (int i = 0; i < renderer.getPageCount(); i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    int w = 2000;  // sharp enough to edit, crop and print
                    int h = Math.round(w * page.getHeight() / (float) page.getWidth());
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    page.close();
                    File added;
                    try {
                        added = addPage(bmp, store, dir);
                    } finally {
                        bmp.recycle();
                    }
                    String text = texts != null && i < texts.size() ? texts.get(i) : null;
                    boolean hasText = text != null && text.trim().length() >= 20;
                    if (hasText || source != null) {
                        PageMeta m = PageMeta.read(added);
                        if (hasText) {
                            m.text = text.trim();
                            m.fromPdfText = true;
                        }
                        if (source != null) {
                            m.srcPdf = source;
                            m.srcPage = i;
                        }
                        m.write(added);
                    }
                    count++;
                }
            }
        }
        return count;
    }
}
