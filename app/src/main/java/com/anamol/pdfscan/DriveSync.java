package com.anamol.pdfscan;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Uploads documents to the "PDF Scan" folder in Google Drive as a searchable PDF plus its
 * transcript. Each document remembers its Drive file ids, so later uploads replace the same files.
 */
final class DriveSync {
    private static Thread worker;
    private static volatile boolean again;
    private static volatile boolean forceAll;

    private DriveSync() {
    }

    static boolean networkOk(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (nc == null || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
        return !Prefs.driveWifiOnly(c) || nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    static boolean ready(Context c) {
        return Prefs.internet(c) && DriveAccount.configured() && DriveAccount.connected(c);
    }

    /** Called after indexing: uploads changed documents if auto-upload is on. */
    static void kickIfAuto(Context c) {
        if (ready(c) && Prefs.driveAutoUpload(c) && networkOk(c)) kick(c, false);
    }

    /** Uploads every document that changed since its last upload (or all of them, when forced). */
    static synchronized void kick(Context context, boolean force) {
        again = true;
        if (force) forceAll = true;
        if (worker != null && worker.isAlive()) return;
        final Context app = context.getApplicationContext();
        worker = new Thread(() -> run(app), "drive-sync");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void run(Context c) {
        String error = null;
        while (again) {
            again = false;
            boolean force = forceAll;
            forceAll = false;
            DocStore store = new DocStore(c);
            for (File dir : store.listDocs()) {
                if (!ready(c)) break;
                File[] pages = store.pages(dir);
                if (pages.length == 0) continue;
                if (!force && Indexer.autoEnabled(c)) {
                    boolean pending = false;
                    for (File p : pages) {
                        if (!PageMeta.hasTextQuick(p)) {
                            pending = true;
                            break;
                        }
                    }
                    if (pending) continue;  // upload once text recognition has finished
                }
                if (!force && Transcript.signature(store, dir).equals(state(dir).optString("sig"))) continue;
                try {
                    StatusBus.drive("Uploading “" + store.getTitle(dir) + "” to Google Drive");
                    uploadDoc(c, store, dir);
                } catch (IOException e) {
                    error = e.getMessage();
                    break;
                }
            }
        }
        synchronized (DriveSync.class) {
            worker = null;
        }
        if (error != null) StatusBus.drive("Drive upload failed: " + error);
        else StatusBus.drive(null);
    }

    private static File stateFile(File dir) {
        return new File(dir, "drive.json");
    }

    private static JSONObject state(File dir) {
        try {
            return new JSONObject(new String(Files.readAllBytes(stateFile(dir).toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    /** Uploads one document now (PDF with its text layer, and the transcript). */
    static void uploadDoc(Context c, DocStore store, File dir) throws IOException {
        if (!ready(c)) throw new IOException("Google Drive isn't connected");
        String text = Transcript.update(store, dir);
        String sig = Transcript.signature(store, dir);
        String title = store.getTitle(dir);
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        PdfExport.write(Arrays.asList(store.pages(dir)), Prefs.exportSize(c), null, title, pdf);

        JSONObject st = state(dir);
        String folder = DriveClient.ensureFolder(c);
        String pdfId = DriveClient.upload(c, folder, DriveCore.fileName(title, ".pdf"), "application/pdf",
                pdf.toByteArray(), st.optString("pdf", null));
        String txtId = DriveClient.upload(c, folder, DriveCore.fileName(title, ".txt"), "text/plain; charset=UTF-8",
                text.getBytes(StandardCharsets.UTF_8), st.optString("txt", null));
        try {
            JSONObject out = new JSONObject().put("pdf", pdfId).put("txt", txtId).put("sig", sig)
                    .put("at", System.currentTimeMillis());
            Files.write(stateFile(dir).toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Uploads one exported file (any name) to the Drive folder, without tracking it. */
    static void uploadFile(Context c, String name, String mime, byte[] content) throws IOException {
        String folder = DriveClient.ensureFolder(c);
        DriveClient.upload(c, folder, name, mime, content, null);
    }
}
