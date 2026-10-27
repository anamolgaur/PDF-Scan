package com.anamol.pdfscan;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * A small private problem log for testing on the phone: crashes and failed actions are written
 * to the app's own storage, never sent anywhere. Settings > Diagnostics can share a report.
 */
final class Diag {
    private static final long MAX_LOG = 200 * 1024;
    private static File dir;

    private Diag() {
    }

    static void init(Context c) {
        dir = new File(c.getFilesDir(), "logs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                File f = new File(dir, "crash-" + System.currentTimeMillis() + ".txt");
                Files.write(f.toPath(), ("Crash on thread " + thread.getName() + " at " + now() + "\n" + sw)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
                // never make a crash worse
            }
            if (previous != null) previous.uncaughtException(thread, e);
        });
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** Notes a problem (failed save, camera error...). */
    static synchronized void log(String what, Throwable t) {
        if (dir == null) return;
        File f = new File(dir, "log.txt");
        try {
            if (f.length() > MAX_LOG) {
                File old = new File(dir, "log.old.txt");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
            }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(now() + "  " + what + (t != null ? ": " + t : "") + "\n");
                if (t != null) {
                    StackTraceElement[] st = t.getStackTrace();
                    for (int i = 0; i < Math.min(6, st.length); i++) w.write("      at " + st[i] + "\n");
                }
            }
        } catch (IOException ignored) {
            // logging is best effort
        }
    }

    static int crashCount() {
        if (dir == null) return 0;
        File[] c = dir.listFiles((d, n) -> n.startsWith("crash-"));
        return c == null ? 0 : c.length;
    }

    /** Everything useful for fixing a problem, as plain text. */
    static String report(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("PDF Scan ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Made: ").append(now()).append("\n\n");
        if (dir != null) {
            File[] crashes = dir.listFiles((d, n) -> n.startsWith("crash-"));
            if (crashes != null) {
                Arrays.sort(crashes);
                sb.append("== Crashes: ").append(crashes.length).append(" ==\n");
                for (int i = Math.max(0, crashes.length - 5); i < crashes.length; i++) {
                    sb.append(read(crashes[i], 12000)).append('\n');
                }
            }
            sb.append("\n== Recent problems ==\n");
            sb.append(read(new File(dir, "log.txt"), 30000));
        }
        return sb.toString();
    }

    static void clear() {
        if (dir == null) return;
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static String read(File f, int maxChars) {
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return s.length() > maxChars ? s.substring(s.length() - maxChars) : s;
        } catch (IOException e) {
            return "";
        }
    }
}
