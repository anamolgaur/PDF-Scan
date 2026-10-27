package com.anamol.pdfscan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the phone-wide search index in the background, with a progress notification, until
 * every document is done. Step 1 finds PDF, Word, Excel, PowerPoint and text files; step 2 reads
 * their text (scanned PDFs are run through on-device text recognition). Safe to stop at any
 * time: progress is kept and the next run continues where this one stopped.
 */
public class FileIndexService extends Service {
    static final String ACTION_STOP = "com.anamol.pdfscan.STOP_INDEXING";
    private static final String CHANNEL = "phone-index";
    private static final int NOTIFICATION = 7;
    private static final int OCR_PAGE_LIMIT = 30;
    private static final long MAX_PDF = 60L * 1024 * 1024;
    private static final long RESCAN_INTERVAL = 6 * 60 * 60 * 1000L;

    private static volatile boolean running;
    private volatile boolean stop;
    private Thread worker;
    private long lastNotify;

    static boolean isRunning() {
        return running;
    }

    /** Starts indexing if it's switched on, access is granted, and there is something to do. */
    static void startIfNeeded(Context c, boolean force) {
        PhoneWatchJob.schedule(c);
        if (running || !PhoneFiles.enabled(c) || !PhoneFiles.hasAccess(c)) return;
        long last = Prefs.get(c).getLong("phone_index_scan", 0);
        if (!force && System.currentTimeMillis() - last < RESCAN_INTERVAL && DeviceIndex.get(c).counts()[2] == 0) return;
        c.startForegroundService(new Intent(c, FileIndexService.class));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, FileIndexService.class).setAction(ACTION_STOP));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop = true;
            StatusBus.files(null);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Making phone files searchable",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while PDF Scan reads documents on this phone to index them");
            nm.createNotificationChannel(ch);
        }
        try {
            startForeground(NOTIFICATION, notification("Getting ready…", 0, 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (RuntimeException e) {
            // Android didn't allow a background start (e.g. after a restart); it runs next time the app opens
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            stop = false;
            running = true;
            worker = new Thread(this::work, "phone-index");
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        super.onDestroy();
    }

    private Notification notification(String text, int done, int total) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIt = PendingIntent.getService(this, 1,
                new Intent(this, FileIndexService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_scan)
                .setColor(Ui.MAROON)
                .setContentTitle("Making phone files searchable")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stopIt).build());
        if (total > 0) b.setProgress(total, done, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void update(String text, int done, int total) {
        StatusBus.files(text);
        long now = System.currentTimeMillis();
        if (now - lastNotify < 800) return;
        lastNotify = now;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION, notification(text, done, total));
    }

    private void work() {
        DeviceIndex db = DeviceIndex.get(this);
        Ocr ocr = null;
        try {
            // 1. find documents
            final long scan = System.currentTimeMillis();
            final int[] found = {0};
            boolean complete = PhoneFiles.walk(PhoneFiles.roots(this), f -> {
                if (stop) return false;
                db.seen(f.getAbsolutePath(), f.getName(), f.length(), f.lastModified(), scan);
                if (++found[0] % 100 == 0) update("Looking for documents · " + found[0] + " found", 0, 0);
                return true;
            });
            if (stop) return;
            if (complete) {
                db.removeUnseen(scan);
                Prefs.get(this).edit().putLong("phone_index_scan", scan).apply();
            }

            // 2. read their text
            int[] counts = db.counts();
            int total = counts[0];
            int done = counts[1];
            while (!stop) {
                List<String[]> batch = db.pending(25);
                if (batch.isEmpty()) break;
                for (String[] row : batch) {
                    if (stop) return;
                    long id = Long.parseLong(row[0]);
                    File f = new File(row[1]);
                    update(String.format(Locale.US, "%,d of %,d · %s", done + 1, total, f.getName()), done, total);
                    try {
                        ocr = index(db, id, f, ocr);
                    } catch (OutOfMemoryError | Exception e) {
                        db.store(id, DeviceIndex.FAILED, "page", null);
                    }
                    done++;
                }
            }
        } finally {
            if (ocr != null) ocr.close();
            running = false;
            StatusBus.files(null);
            if (!stop) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    /** Reads one file's text into the index. Returns the (possibly newly created) OCR engine. */
    private Ocr index(DeviceIndex db, long id, File f, Ocr ocr) throws IOException {
        if (!f.isFile()) {
            db.store(id, DeviceIndex.FAILED, "page", null);
            return ocr;
        }
        if (OfficeText.extension(f.getName()).equals("pdf")) {
            if (f.length() > MAX_PDF) {
                db.store(id, DeviceIndex.FAILED, "page", null);
                return ocr;
            }
            List<String> pages;
            try {
                pages = PdfText.extract(Files.readAllBytes(f.toPath()));
            } catch (PdfText.LockedException e) {
                db.store(id, DeviceIndex.LOCKED, "page", null);
                return ocr;
            }
            boolean anyText = false;
            for (String p : pages) {
                if (p.trim().length() > 20) {
                    anyText = true;
                    break;
                }
            }
            if (!anyText && Indexer.autoEnabled(this)) {
                if (ocr == null) ocr = new Ocr(this);
                pages = ocrPdf(f, ocr);
            }
            db.store(id, DeviceIndex.INDEXED, "page", pages);
        } else {
            OfficeText.Result r = OfficeText.extract(f);
            db.store(id, DeviceIndex.INDEXED, r.unit, r.parts);
        }
        return ocr;
    }

    /**
     * Quick indexing for the background watcher: reads files whose text can be extracted
     * directly. Returns false (leaving the file waiting) when it needs text recognition, which
     * the full indexing run does later.
     */
    static boolean indexText(DeviceIndex db, long id, File f) {
        try {
            if (!f.isFile()) {
                db.store(id, DeviceIndex.FAILED, "page", null);
                return true;
            }
            if (OfficeText.extension(f.getName()).equals("pdf")) {
                if (f.length() > MAX_PDF) {
                    db.store(id, DeviceIndex.FAILED, "page", null);
                    return true;
                }
                List<String> pages;
                try {
                    pages = PdfText.extract(Files.readAllBytes(f.toPath()));
                } catch (PdfText.LockedException e) {
                    db.store(id, DeviceIndex.LOCKED, "page", null);
                    return true;
                }
                for (String p : pages) {
                    if (p.trim().length() > 20) {
                        db.store(id, DeviceIndex.INDEXED, "page", pages);
                        return true;
                    }
                }
                return false;  // scanned: needs recognition
            }
            OfficeText.Result r = OfficeText.extract(f);
            db.store(id, DeviceIndex.INDEXED, r.unit, r.parts);
            return true;
        } catch (OutOfMemoryError | Exception e) {
            db.store(id, DeviceIndex.FAILED, "page", null);
            return true;
        }
    }

    /** Scanned PDFs: render pages and recognise their text (first OCR_PAGE_LIMIT pages). */
    private List<String> ocrPdf(File f, Ocr ocr) throws IOException {
        List<String> out = new ArrayList<>();
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(pfd)) {
            int n = Math.min(r.getPageCount(), OCR_PAGE_LIMIT);
            for (int i = 0; i < n && !stop; i++) {
                PdfRenderer.Page page = r.openPage(i);
                int w = 1400;
                int h = Math.max(1, Math.round(w * page.getHeight() / (float) page.getWidth()));
                if (h > 4000) h = 4000;
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                page.close();
                try {
                    out.add(ocr.text(b));
                } finally {
                    b.recycle();
                }
            }
        } catch (SecurityException e) {
            throw new IOException("Protected PDF");
        }
        return out;
    }
}
