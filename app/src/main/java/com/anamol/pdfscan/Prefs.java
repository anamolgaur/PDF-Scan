package com.anamol.pdfscan;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings, all stored on the device. */
final class Prefs {
    static final String SIZE_HIGH = "high";
    static final String SIZE_BALANCED = "balanced";
    static final String SIZE_SMALL = "small";
    static final String[] SIZES = {SIZE_HIGH, SIZE_BALANCED, SIZE_SMALL};
    static final String[] SIZE_LABELS = {"High quality", "Balanced", "Small"};

    private Prefs() {
    }

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String defaultFilter(Context c) {
        return get(c).getString("default_filter", ImageOps.SCAN);
    }

    static void setDefaultFilter(Context c, String f) {
        get(c).edit().putString("default_filter", f).apply();
    }

    static boolean autoCapture(Context c) {
        return get(c).getBoolean("auto_capture", true);
    }

    static void setAutoCapture(Context c, boolean on) {
        get(c).edit().putBoolean("auto_capture", on).apply();
    }

    /** Straighten and filter pages as they are captured (off: keep plain photos, edit later). */
    static boolean scanOnCapture(Context c) {
        return get(c).getBoolean("scan_on_capture", false);
    }

    static void setScanOnCapture(Context c, boolean on) {
        get(c).edit().putBoolean("scan_on_capture", on).apply();
    }

    static final int CAMERA_PHOTO = 0, CAMERA_SCAN = 1, CAMERA_ID = 2, CAMERA_QR = 3;
    static final int FLASH_OFF = 0, FLASH_AUTO = 1, FLASH_ON = 2, FLASH_TORCH = 3;

    /** Camera mode: plain manual photo, or page scanning with live detection. */
    static int cameraMode(Context c) {
        return get(c).getInt("camera_mode", CAMERA_PHOTO);
    }

    static void setCameraMode(Context c, int m) {
        get(c).edit().putInt("camera_mode", m).apply();
    }

    static int cameraFlash(Context c) {
        return get(c).getInt("camera_flash", FLASH_OFF);
    }

    static void setCameraFlash(Context c, int f) {
        get(c).edit().putInt("camera_flash", f).apply();
    }

    /** Exposure compensation in EV, kept between sessions. */
    static float cameraEv(Context c) {
        return get(c).getFloat("camera_ev", -0.3f);
    }

    static void setCameraEv(Context c, float ev) {
        get(c).edit().putFloat("camera_ev", ev).apply();
    }

    /** AMOLED black look (true black, maroon and cream type) instead of the maroon ground. */
    static boolean amoled(Context c) {
        return get(c).getBoolean("theme_amoled", false);
    }

    static void setAmoled(Context c, boolean on) {
        get(c).edit().putBoolean("theme_amoled", on).apply();
    }

    /** Remove pages' original photos after this many days to save space (0 = keep them). */
    static int keepOriginalsDays(Context c) {
        return get(c).getInt("keep_originals_days", 0);
    }

    static void setKeepOriginalsDays(Context c, int days) {
        get(c).edit().putInt("keep_originals_days", days).apply();
    }

    static boolean batch(Context c) {
        return get(c).getBoolean("batch", false);
    }

    static void setBatch(Context c, boolean on) {
        get(c).edit().putBoolean("batch", on).apply();
    }

    static String exportSize(Context c) {
        return get(c).getString("export_size", SIZE_BALANCED);
    }

    static void setExportSize(Context c, String s) {
        get(c).edit().putString("export_size", s).apply();
    }

    /** Master switch. While off, the app makes no network connections at all. */
    static boolean internet(Context c) {
        return get(c).getBoolean("internet", false);
    }

    static void setInternet(Context c, boolean on) {
        get(c).edit().putBoolean("internet", on).apply();
    }

    static boolean driveAutoUpload(Context c) {
        return get(c).getBoolean("drive_auto", false);
    }

    static void setDriveAutoUpload(Context c, boolean on) {
        get(c).edit().putBoolean("drive_auto", on).apply();
    }

    static boolean driveWifiOnly(Context c) {
        return get(c).getBoolean("drive_wifi_only", true);
    }

    static void setDriveWifiOnly(Context c, boolean on) {
        get(c).edit().putBoolean("drive_wifi_only", on).apply();
    }

    static int indexOf(String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return 0;
    }
}
