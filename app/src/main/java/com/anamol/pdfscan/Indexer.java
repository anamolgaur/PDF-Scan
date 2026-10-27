package com.anamol.pdfscan;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * Background indexing: recognises text on pages that don't have it yet and keeps every
 * document's transcript up to date. Runs on one low-priority thread while the app is open.
 * When it finishes, it lets Drive auto-upload run (if that is switched on).
 *
 * LOCK guards page files: text recognition of a page and any edit, move or delete of pages
 * never happen at the same time.
 */
final class Indexer {
    static final Object LOCK = new Object();

    private static Thread worker;
    private static volatile boolean again;

    private Indexer() {
    }

    static boolean autoEnabled(Context c) {
        return Prefs.get(c).getBoolean("auto_index", true);
    }

    static void setAutoEnabled(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean("auto_index", on).apply();
        if (on) kick(c);
    }

    /** Asks for a pass over all documents. Cheap to call often. */
    static synchronized void kick(Context context) {
        again = true;
        if (worker != null && worker.isAlive()) return;
        final Context app = context.getApplicationContext();
        worker = new Thread(() -> run(app), "indexer");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void run(Context c) {
        Ocr ocr = null;
        try {
            while (again) {
                again = false;
                DocStore store = new DocStore(c);
                for (File dir : store.listDocs()) {
                    if (autoEnabled(c)) {
                        File[] pages = store.pages(dir);
                        int todo = 0;
                        for (File p : pages) if (!PageMeta.hasTextQuick(p)) todo++;
                        int done = 0;
                        for (File p : pages) {
                            if (!autoEnabled(c)) break;
                            if (PageMeta.hasTextQuick(p)) continue;
                            if (ocr == null) ocr = new Ocr(c);
                            done++;
                            StatusBus.indexing("Indexing “" + store.getTitle(dir) + "” · page "
                                    + done + " of " + todo);
                            try {
                                recognize(ocr, p);
                            } catch (IOException e) {
                                // unreadable page: skip it, keep indexing the rest
                            }
                        }
                    }
                    try {
                        Transcript.update(store, dir);
                    } catch (IOException ignored) {
                        // document removed while indexing
                    }
                }
            }
        } catch (Throwable t) {
            StatusBus.indexing(null);
            StatusBus.drive("Indexing stopped: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            if (ocr != null) ocr.close();
            synchronized (Indexer.class) {
                worker = null;
            }
            return;
        }
        if (ocr != null) ocr.close();
        synchronized (Indexer.class) {
            worker = null;
            if (again) {
                kick(c);
                return;
            }
        }
        StatusBus.indexing(null);
        DriveSync.kickIfAuto(c);
    }

    /** Recognises one page if it still exists and still has no text. Returns true if it did. */
    static boolean recognize(Ocr ocr, File page) throws IOException {
        synchronized (LOCK) {
            if (!page.exists() || PageMeta.hasTextQuick(page)) return false;
            PageMeta m = PageMeta.read(page);
            ocr.recognize(page, m);
            m.write(page);
            return true;
        }
    }
}
