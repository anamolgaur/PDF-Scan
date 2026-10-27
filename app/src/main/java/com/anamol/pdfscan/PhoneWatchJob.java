package com.anamol.pdfscan;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.util.List;

/**
 * Keeps the phone search up to date without opening the app: Android wakes this job when files
 * on the phone are added or changed (a download, a shared attachment), and it indexes the new
 * documents' text straight away. Scanned PDFs that need text recognition wait for the next
 * full indexing run.
 */
public class PhoneWatchJob extends JobService {
    private static final int JOB_ID = 4711;
    private static final Uri FILES = MediaStore.Files.getContentUri("external");
    private volatile boolean stopped;

    /** Asks Android to run the job the next time files change. Safe to call often. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!PhoneFiles.enabled(c) || !PhoneFiles.hasAccess(c)) {
            js.cancel(JOB_ID);
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, PhoneWatchJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(FILES,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(10_000)
                .setTriggerContentMaxDelay(120_000)
                .build();
        try {
            js.schedule(job);
        } catch (RuntimeException ignored) {
            // too many jobs or not allowed right now; the next app start tries again
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        stopped = false;
        new Thread(() -> {
            try {
                work();
            } finally {
                schedule(getApplicationContext());  // content-triggered jobs run once; watch again
                jobFinished(params, false);
            }
        }, "phone-watch").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;
    }

    private void work() {
        if (!PhoneFiles.enabled(this) || !PhoneFiles.hasAccess(this) || FileIndexService.isRunning()) return;
        DeviceIndex db = DeviceIndex.get(this);
        long since = Prefs.get(this).getLong("phone_watch_since", System.currentTimeMillis() / 1000 - 3600);
        long now = System.currentTimeMillis() / 1000;
        String[] cols = {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_MODIFIED};
        int changed = 0;
        try (Cursor c = getContentResolver().query(FILES, cols,
                MediaStore.MediaColumns.DATE_MODIFIED + " >= ?", new String[]{String.valueOf(since - 5)}, null)) {
            while (c != null && c.moveToNext() && !stopped) {
                String path = c.getString(0);
                if (path == null) continue;
                File f = new File(path);
                String n = f.getName();
                if (n.startsWith(".") || !OfficeText.supported(n) || !f.isFile() || f.length() == 0
                        || f.length() > PhoneFiles.MAX_FILE || path.contains("/Android/data/")) {
                    continue;
                }
                db.seen(path, n, f.length(), f.lastModified(), System.currentTimeMillis());
                changed++;
            }
        } catch (RuntimeException ignored) {
            // MediaStore unavailable; the full run will catch up
        }
        Prefs.get(this).edit().putLong("phone_watch_since", now).apply();
        if (changed == 0) return;
        List<String[]> todo = db.pending(40);
        for (String[] row : todo) {
            if (stopped) return;
            FileIndexService.indexText(db, Long.parseLong(row[0]), new File(row[1]));
        }
        StatusBus.files(null);
    }
}
