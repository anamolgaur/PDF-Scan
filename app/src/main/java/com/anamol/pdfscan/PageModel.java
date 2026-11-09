package com.anamol.pdfscan;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Loads the page finder once, when it is first needed: DocAligner's most accurate model in ONNX
 * Runtime (DocNet). If that can't start on this phone, the smaller plain-Java model (PageNet) is
 * used instead; and PageFind always has the classic detector as the last backup.
 */
final class PageModel {
    static final String MODEL = "docaligner_fastvit_sa24.onnx";

    private static Context app;
    private static PageFind.Detector net;
    private static boolean failed;

    private PageModel() {
    }

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    /** The page finder, or null when switched off in Settings or no model could be loaded. */
    static synchronized PageFind.Detector get() {
        if (app == null || failed || !Prefs.smartDetect(app)) return null;
        if (net != null) return net;
        try {
            File f = new File(app.getNoBackupFilesDir(), MODEL);
            long size = app.getAssets().openFd(MODEL).getLength();
            if (!f.isFile() || f.length() != size) copyAsset(MODEL, f);
            net = new DocNet(f.getAbsolutePath(), Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            return net;
        } catch (Throwable e) {
            Diag.log("Page model (ONNX Runtime)", e);
        }
        try (InputStream in = app.getAssets().open("pagenet.bin")) {
            net = PageNet.load(in);
            return net;
        } catch (Throwable e) {
            failed = true;
            Diag.log("Page model", e);
            return null;
        }
    }

    private static void copyAsset(String name, File to) throws java.io.IOException {
        File tmp = new File(to.getParentFile(), name + ".tmp");
        try (InputStream in = app.getAssets().open(name); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(to)) throw new java.io.IOException("Couldn't prepare the page model");
    }
}
