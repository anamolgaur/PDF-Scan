package com.anamol.pdfscan;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Music settings and the app-side controls for MusicService. */
final class MusicPlayer {
    private MusicPlayer() {
    }

    static boolean enabled(Context c) {
        return Prefs.get(c).getBoolean("music", true);
    }

    static void setEnabled(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean("music", on).apply();
        if (on) MusicService.send(c, MusicService.ACTION_PLAY);
        else MusicService.send(c, MusicService.ACTION_STOP);
    }

    /** Keep playing after leaving the app (until stopped from the notification). Off: pause on leaving. */
    static boolean keepPlaying(Context c) {
        return Prefs.get(c).getBoolean("music_background", false);
    }

    static void setKeepPlaying(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean("music_background", on).apply();
    }

    /** Move on to the next track every couple of minutes (otherwise repeat the chosen one). */
    static boolean playAll(Context c) {
        return Prefs.get(c).getBoolean("music_all", true);
    }

    static void setPlayAll(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean("music_all", on).apply();
    }

    static int track(Context c) {
        return Prefs.get(c).getInt("music_track", 0);
    }

    static void play(Context c, int track) {
        Prefs.get(c).edit().putInt("music_track", track).apply();
        if (enabled(c)) MusicService.playTrack(c, track);
    }

    /** 0..1 */
    static float volume(Context c) {
        return Prefs.get(c).getFloat("music_volume", 0.3f);
    }

    static void setVolume(Context c, float v) {
        Prefs.get(c).edit().putFloat("music_volume", v).apply();
    }

    /** Perceptual curve so the slider feels even. */
    static float curve(float v) {
        return v * v;
    }

    /** How long the music waits for the app to come back from a picker, camera or viewer. */
    private static final long HOLD_MS = 15 * 60_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long holdUntil;
    private static boolean visible;

    /**
     * Call just before handing the user to another app for this app's sake (file picker, camera,
     * document viewer, export location, sign-in). The music keeps playing while they are there.
     */
    static void holdForExternal() {
        holdUntil = SystemClock.elapsedRealtime() + HOLD_MS;
    }

    /** True while another app is open on this app's behalf (camera, picker...). */
    static boolean heldForExternal() {
        return holdUntil > SystemClock.elapsedRealtime();
    }

    /** Number of times the app has been opened. */
    static int opens(Context c) {
        return Prefs.get(c).getInt("music_opens", 0);
    }

    /** The app came to the screen: count the visit and continue the soundtrack where it stopped. */
    static void appVisible(Context c) {
        MAIN.removeCallbacksAndMessages(null);
        boolean wasHeld = holdUntil > SystemClock.elapsedRealtime();
        holdUntil = 0;
        visible = true;
        if (!wasHeld) Prefs.get(c).edit().putInt("music_opens", opens(c) + 1).apply();
        if (enabled(c)) MusicService.send(c, MusicService.ACTION_PLAY);
    }

    /** The app left the screen. */
    static void appHidden(Context c) {
        visible = false;
        if (keepPlaying(c)) return;
        final Context app = c.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        if (holdUntil > now) {
            // another app is open on our behalf; pause only if the user never comes back
            MAIN.postDelayed(() -> {
                if (!visible) {
                    holdUntil = 0;
                    MusicService.send(app, MusicService.ACTION_PAUSE);
                }
            }, holdUntil - now);
            return;
        }
        MusicService.send(app, MusicService.ACTION_PAUSE);
    }
}
