package com.anamol.pdfscan;

import android.content.Context;

import java.io.InputStream;

/** Loads the page-finding network from the app's assets once, when it is first needed. */
final class PageModel {
    private static Context app;
    private static PageNet net;
    private static boolean failed;

    private PageModel() {
    }

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    /** The network, or null when switched off in Settings or it couldn't be loaded. */
    static synchronized PageNet get() {
        if (app == null || failed || !Prefs.smartDetect(app)) return null;
        if (net == null) {
            try (InputStream in = app.getAssets().open("pagenet.bin")) {
                net = PageNet.load(in);
            } catch (Exception | OutOfMemoryError e) {
                failed = true;
                Diag.log("Page model", e);
                return null;
            }
        }
        return net;
    }
}
