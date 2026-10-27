package com.anamol.pdfscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * On-device text recognition with Tesseract: the bundled English model plus any extra
 * languages downloaded in Settings.
 * Nothing is sent anywhere; the app has no network permission.
 */
final class Ocr implements Closeable {
    private final TessBaseAPI api;

    Ocr(Context c) throws IOException {
        File base = c.getFilesDir();
        File dataDir = new File(base, "tessdata");
        //noinspection ResultOfMethodCallIgnored
        dataDir.mkdirs();
        File model = new File(dataDir, "eng.traineddata");
        if (!model.exists() || model.length() == 0) {
            File tmp = new File(dataDir, "eng.traineddata.tmp");
            try (InputStream in = c.getAssets().open("tessdata/eng.traineddata");
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(model)) throw new IOException("Could not install the text model");
        }
        api = new TessBaseAPI();
        String langs = Languages.active(c);
        if (!api.init(base.getAbsolutePath(), langs) && (langs.equals("eng") || !api.init(base.getAbsolutePath(), "eng"))) {
            api.recycle();
            throw new IOException("Text recognition could not start");
        }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
    }

    /** Recognises one page and stores the text and word boxes in meta. */
    void recognize(File pageJpg, PageMeta meta) throws IOException {
        Bitmap b = BitmapFactory.decodeFile(pageJpg.getAbsolutePath());
        if (b == null) throw new IOException("Cannot read page image");
        try {
            api.setImage(b);
            String text = api.getUTF8Text();
            List<String> words = new ArrayList<>();
            List<Integer> boxes = new ArrayList<>();
            ResultIterator it = api.getResultIterator();
            if (it != null) {
                int level = TessBaseAPI.PageIteratorLevel.RIL_WORD;
                it.begin();
                do {
                    String w = it.getUTF8Text(level);
                    Rect r = it.getBoundingRect(level);
                    if (w != null && !w.trim().isEmpty() && r != null && r.width() > 0 && r.height() > 0) {
                        words.add(w.trim());
                        boxes.add(r.left);
                        boxes.add(r.top);
                        boxes.add(r.width());
                        boxes.add(r.height());
                    }
                } while (it.next(level));
                it.delete();
            }
            meta.text = text == null ? "" : SearchCore.compactCjk(text.trim());
            meta.ocrW = b.getWidth();
            meta.ocrH = b.getHeight();
            meta.words = words.toArray(new String[0]);
            meta.boxes = new int[boxes.size()];
            for (int i = 0; i < meta.boxes.length; i++) meta.boxes[i] = boxes.get(i);
        } finally {
            api.clear();
            b.recycle();
        }
    }

    /** Recognised text of a bitmap (used for scanned PDFs found on the phone). */
    String text(Bitmap b) {
        try {
            api.setImage(b);
            String t = api.getUTF8Text();
            return t == null ? "" : SearchCore.compactCjk(t.trim());
        } finally {
            api.clear();
        }
    }

    @Override
    public void close() {
        api.recycle();
    }
}
