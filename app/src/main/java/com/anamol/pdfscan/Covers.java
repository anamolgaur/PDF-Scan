package com.anamol.pdfscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Cover pictures for documents on the phone, shown in the list instead of a type badge.
 * PDFs show their first page (the cover of a book, the title slide of a lecture). Word,
 * PowerPoint, Excel and OpenDocument files show the preview picture saved inside the file,
 * or else the first picture in the document. Covers are made once and kept in the app's cache.
 */
final class Covers {
    static final int WIDTH = 180;
    private static final int MAX_ENTRY = 12 * 1024 * 1024;

    private Covers() {
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static String key(String path, long mtime, long size) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest((path + "|" + mtime + "|" + size).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format(Locale.ROOT, "%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((path + mtime + size).hashCode());
        }
    }

    /** The saved cover if it was made before (fast, a small file), else null. */
    static Bitmap cached(Context c, String path, long mtime, long size) {
        File jpg = new File(dir(c), key(path, mtime, size) + ".jpg");
        return jpg.isFile() ? BitmapFactory.decodeFile(jpg.getAbsolutePath()) : null;
    }

    /** The cover, or null if the file has none. Slow the first time: call off the main thread. */
    static Bitmap get(Context c, String path, long mtime, long size) {
        File d = dir(c);
        String k = key(path, mtime, size);
        File jpg = new File(d, k + ".jpg");
        File none = new File(d, k + ".none");
        if (jpg.exists()) {
            Bitmap b = BitmapFactory.decodeFile(jpg.getAbsolutePath());
            if (b != null) return b;
        }
        if (none.exists()) return null;
        Bitmap b = null;
        try {
            b = make(new File(path));
        } catch (Throwable ignored) {
            // damaged, protected or too large: no cover
        }
        try {
            if (b != null) {
                try (FileOutputStream out = new FileOutputStream(jpg)) {
                    b.compress(Bitmap.CompressFormat.JPEG, 85, out);
                }
            } else {
                //noinspection ResultOfMethodCallIgnored
                none.createNewFile();
            }
        } catch (IOException ignored) {
            // cache is optional
        }
        return b;
    }

    private static Bitmap make(File f) throws IOException {
        if (!f.isFile()) return null;
        String ext = OfficeText.extension(f.getName()).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "pdf":
                return pdf(f);
            case "docx":
            case "docm":
            case "xlsx":
            case "xlsm":
            case "pptx":
            case "pptm":
            case "ppsx":
            case "odt":
            case "ods":
            case "odp":
                return zipPicture(f);
            default:
                return null;
        }
    }

    private static Bitmap pdf(File f) throws IOException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(fd)) {
            if (r.getPageCount() == 0) return null;
            try (PdfRenderer.Page p = r.openPage(0)) {
                int w = WIDTH;
                int h = Math.max(1, Math.round(w * p.getHeight() / (float) Math.max(1, p.getWidth())));
                h = Math.min(h, w * 3);
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                new Canvas(b).drawColor(Color.WHITE);
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            }
        }
    }

    /** Office/OpenDocument: the embedded preview picture, else the first picture in the document. */
    private static Bitmap zipPicture(File f) throws IOException {
        try (ZipFile z = new ZipFile(f)) {
            String[] previews = {"docProps/thumbnail.jpeg", "docProps/thumbnail.jpg", "docProps/thumbnail.png",
                    "Thumbnails/thumbnail.png"};
            for (String name : previews) {
                ZipEntry e = z.getEntry(name);
                Bitmap b = e != null ? decodeEntry(z, e) : null;
                if (b != null) return b;
            }
            List<String> pictures = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                String lower = n.toLowerCase(Locale.ROOT);
                boolean media = lower.startsWith("word/media/") || lower.startsWith("ppt/media/")
                        || lower.startsWith("xl/media/") || lower.startsWith("pictures/");
                boolean image = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
                if (media && image && e.getSize() > 6 * 1024) pictures.add(n);
            }
            // image1, image2, ... in document order
            Collections.sort(pictures, (a, b) -> {
                int c = Integer.compare(a.length(), b.length());
                return c != 0 ? c : a.compareTo(b);
            });
            for (String n : pictures) {
                Bitmap b = decodeEntry(z, z.getEntry(n));
                if (b != null) return b;
            }
        }
        return null;
    }

    private static Bitmap decodeEntry(ZipFile z, ZipEntry e) throws IOException {
        if (e.getSize() > MAX_ENTRY) return null;
        byte[] data;
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_ENTRY) return null;
            }
            data = out.toByteArray();
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth < 32 || o.outHeight < 32) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= WIDTH) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, d);
    }
}
