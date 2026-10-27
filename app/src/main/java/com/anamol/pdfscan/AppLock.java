package com.anamol.pdfscan;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.view.WindowManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Locks the app, or chosen folders, behind the phone's own screen lock: fingerprint, face, PIN,
 * pattern or password. Nothing is stored by the app; Android checks it. The lock comes back each
 * time the app is left (except while another app is open for this one, such as the camera or a
 * file picker).
 */
final class AppLock {
    private static boolean unlocked;
    private static final Set<String> openFolders = new HashSet<>();

    private AppLock() {
    }

    static boolean appLockOn(Context c) {
        return Prefs.get(c).getBoolean("lock_app", false);
    }

    static void setAppLock(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean("lock_app", on).apply();
        unlocked = on;  // turning it on happens after a successful check
    }

    /** Hide the app's screens in the recent-apps view and block screenshots. */
    static boolean secureScreens(Context c) {
        return Prefs.get(c).getBoolean("lock_secure", false);
    }

    static void setSecureScreens(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean("lock_secure", on).apply();
    }

    /** True when the phone has a screen lock, which the app lock relies on. */
    static boolean deviceSecure(Context c) {
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        return km != null && km.isDeviceSecure();
    }

    static Set<String> lockedFolders(Context c) {
        return new HashSet<>(Prefs.get(c).getStringSet("locked_folders", new HashSet<>()));
    }

    static boolean folderLocked(Context c, String folder) {
        if (folder == null || folder.isEmpty()) return false;
        for (String f : lockedFolders(c)) if (f.equalsIgnoreCase(folder)) return true;
        return false;
    }

    static void setFolderLocked(Context c, String folder, boolean locked) {
        Set<String> s = lockedFolders(c);
        s.removeIf(f -> f.equalsIgnoreCase(folder));
        if (locked) s.add(folder);
        Prefs.get(c).edit().putStringSet("locked_folders", s).apply();
        if (!locked) openFolders.remove(folder.toLowerCase(java.util.Locale.ROOT));
    }

    /** A locked folder whose contents may be shown right now (unlocked this visit). */
    static boolean folderOpen(Context c, String folder) {
        return !folderLocked(c, folder) || openFolders.contains(folder.toLowerCase(java.util.Locale.ROOT));
    }

    static void markFolderOpen(String folder) {
        openFolders.add(folder.toLowerCase(java.util.Locale.ROOT));
    }

    // ------------------------------------------------------------------ app lifecycle

    /** Every screen: apply "hide in recents", and ask to unlock when needed. */
    static void onActivityCreated(Activity a) {
        if (secureScreens(a)) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    static void onActivityResumed(Activity a) {
        if (a instanceof LockActivity) return;
        if (appLockOn(a) && !unlocked) {
            a.startActivity(new Intent(a, LockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
            a.overridePendingTransition(0, 0);
        }
    }

    /** The app left the screen: lock again, unless another app was opened on its behalf. */
    static void onAppHidden(boolean heldForExternal) {
        if (heldForExternal) return;
        unlocked = false;
        openFolders.clear();
    }

    static void markUnlocked() {
        unlocked = true;
    }

    // ------------------------------------------------------------------ the check itself

    interface Result {
        void ok();
    }

    /** Shows the phone's own unlock (fingerprint, face, PIN...). Calls ok on success. */
    @SuppressWarnings("deprecation")
    static void authenticate(Activity a, String title, String subtitle, Result ok, Runnable failed) {
        if (!deviceSecure(a)) {
            Ui.toast(a, "Set a screen lock on this phone first (Settings › Security)");
            if (failed != null) failed.run();
            return;
        }
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(a).setTitle(title);
        if (subtitle != null) b.setSubtitle(subtitle);
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            b.setDeviceCredentialAllowed(true);
        }
        b.build().authenticate(new CancellationSignal(), a.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                ok.ok();
            }

            @Override
            public void onAuthenticationError(int code, CharSequence msg) {
                if (failed != null) failed.run();
            }
        });
    }
}
