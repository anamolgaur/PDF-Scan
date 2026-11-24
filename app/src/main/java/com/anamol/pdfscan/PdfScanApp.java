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
        Ui.applyTheme(Prefs.theme(this), Prefs.accent(this), Prefs.systemNight(this));
        PageModel.init(this);
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
                if (a instanceof CameraActivity) return;
                // Auto follows the phone's dark mode, which can change while the app is open
                Ui.applyTheme(Prefs.theme(a), Prefs.accent(a), Prefs.systemNight(a));
                a.setTheme(Ui.themeStyle(Prefs.theme(a), Prefs.systemNight(a)));
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
                AppLock.onActivityCreated(a);
                if (!(a instanceof CameraActivity)) Ui.styleWindow(a);
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
