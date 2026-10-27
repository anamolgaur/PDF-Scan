package com.anamol.pdfscan;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Starts the music when the app comes on screen; it keeps playing in the background unless turned off. */
public class PdfScanApp extends Application {
    private int started;

    @Override
    public void onCreate() {
        super.onCreate();
        Diag.init(this);
        Ui.applyTheme(Prefs.amoled(this));
        new Thread(() -> {
            DocStore store = new DocStore(this);
            store.purgeTrash();                    // recycle bin: older than 30 days
            int keep = Prefs.keepOriginalsDays(this);
            if (keep > 0) store.pruneOriginals(keep);  // optional: free up space
            PhoneWatchJob.schedule(this);          // keep phone search current
        }, "startup").start();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity a) {
                if (started++ == 0) MusicPlayer.appVisible(PdfScanApp.this);
            }

            @Override
            public void onActivityStopped(Activity a) {
                if (--started == 0) {
                    AppLock.onAppHidden(MusicPlayer.heldForExternal());
                    MusicPlayer.appHidden(PdfScanApp.this);
                }
            }

            @Override
            public void onActivityPreCreated(Activity a, Bundle b) {
                // AMOLED black look for every screen except the camera (already black)
                if (Ui.amoled && !(a instanceof CameraActivity)) a.setTheme(R.style.Theme_PdfScan_Black);
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
                AppLock.onActivityCreated(a);
            }

            @Override
            public void onActivityResumed(Activity a) {
                AppLock.onActivityResumed(a);
            }

            @Override
            public void onActivityPaused(Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
    }
}
