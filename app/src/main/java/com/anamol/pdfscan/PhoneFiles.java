package com.anamol.pdfscan;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Access to documents stored on the phone, for the phone-wide search index.
 * Android 11+ needs "All files access"; Android 10 needs the storage permission.
 */
final class PhoneFiles {
    static final int REQ_STORAGE = 41;
    static final long MAX_FILE = 150L * 1024 * 1024;

    interface Visitor {
        /** Return false to stop the walk. */
        boolean file(File f);
    }

    private PhoneFiles() {
    }

    static boolean enabled(Context c) {
        return Prefs.get(c).getBoolean("phone_index", false);
    }

    static void setEnabled(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean("phone_index", on).apply();
    }

    static boolean hasAccess(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Opens the system screen (Android 11+) or dialog (Android 10) that grants access. */
    static void requestAccess(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                MusicPlayer.holdForExternal();
                a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (ActivityNotFoundException e) {
                MusicPlayer.holdForExternal();
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            a.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    /**
     * Deletes a document from the phone after asking. A copy goes to the recycle bin for 30 days,
     * the search index forgets it, and the system's file list is updated.
     */
    static void delete(final Activity a, final long id, final String path, final Runnable after) {
        final File f = new File(path);
        Ui.confirm(a, "Delete \u201C" + f.getName() + "\u201D from this phone? You can put it back from the "
                + "recycle bin for 30 days.", () -> Ui.task(a, () -> {
                    new DocStore(a).trashPhoneFile(f);
                    if (id >= 0) DeviceIndex.get(a).forget(id);
                    android.media.MediaScannerConnection.scanFile(a.getApplicationContext(), new String[]{path}, null, null);
                }, () -> {
                    Ui.toast(a, "Deleted. It's in the recycle bin for 30 days.");
                    if (after != null) after.run();
                }, null));
    }

    /** After a deleted file is put back: index it again and tell the system it exists. */
    static void restored(Context c, File f) {
        DeviceIndex db = DeviceIndex.get(c);
        db.seen(f.getAbsolutePath(), f.getName(), f.length(), f.lastModified(), System.currentTimeMillis());
        for (String[] row : db.pending(10)) {
            if (row[1].equals(f.getAbsolutePath())) FileIndexService.indexText(db, Long.parseLong(row[0]), f);
        }
        android.media.MediaScannerConnection.scanFile(c.getApplicationContext(), new String[]{f.getAbsolutePath()}, null, null);
    }

    /** Explains phone-wide search, then asks Android for file access and starts indexing. */
    static void askToEnable(final Activity a, final Runnable after) {
        new android.app.AlertDialog.Builder(a)
                .setTitle("Search documents on this phone?")
                .setMessage("PDF Scan will read the PDF, Word, Excel, PowerPoint and text files stored on this "
                        + "phone and keep their text in a private index, so you can browse and search them here. "
                        + "Files are never changed, moved or uploaded.\n\nAndroid will ask you to allow access "
                        + "to all files. Indexing then runs in the background, with a notification, until it is done.")
                .setPositiveButton("Continue", (d, w) -> {
                    setEnabled(a, true);
                    if (Build.VERSION.SDK_INT >= 33
                            && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 42);
                    }
                    if (hasAccess(a)) FileIndexService.startIfNeeded(a, true);
                    else requestAccess(a);
                    if (after != null) after.run();
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    /** Shared storage roots: internal shared storage and any SD cards or USB drives. */
    static List<File> roots(Context c) {
        Set<File> out = new LinkedHashSet<>();
        File primary = Environment.getExternalStorageDirectory();
        if (primary != null) out.add(primary);
        StorageManager sm = c.getSystemService(StorageManager.class);
        if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                File d = v.getDirectory();
                if (d != null && d.canRead()) out.add(d);
            }
        }
        return new ArrayList<>(out);
    }

    /** Walks the folders depth-first, skipping hidden folders and other apps' private data. */
    static boolean walk(List<File> roots, Visitor v) {
        Deque<File> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) stack.push(roots.get(i));
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                String n = k.getName();
                if (n.startsWith(".")) continue;
                if (k.isDirectory()) {
                    String p = k.getAbsolutePath();
                    if (p.endsWith("/Android/data") || p.endsWith("/Android/obb") || p.endsWith("/Android/media")) continue;
                    stack.push(k);
                } else if (OfficeText.supported(n) && k.length() > 0 && k.length() <= MAX_FILE) {
                    if (!v.file(k)) return false;
                }
            }
        }
        return true;
    }
}
