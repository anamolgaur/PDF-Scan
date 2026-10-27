package com.anamol.pdfscan;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Google account connection for Drive. Sign-in happens in the browser (OAuth 2.0 with PKCE and a
 * loopback redirect to this phone). The long-lived refresh token is encrypted with a key held in
 * the Android Keystore and never leaves the device except to Google's token endpoint.
 */
final class DriveAccount {
    interface Callback {
        void done(String email, String error);
    }

    private static final String ALIAS = "pdfscan-drive";
    private static String accessToken;
    private static long accessExpiry;

    private DriveAccount() {
    }

    static boolean configured() {
        return !BuildConfig.DRIVE_CLIENT_ID.isEmpty();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("drive", Context.MODE_PRIVATE);
    }

    static boolean connected(Context c) {
        return prefs(c).contains("refresh");
    }

    static String email(Context c) {
        return prefs(c).getString("email", null);
    }

    // ------------------------------------------------------------------ token storage

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    private static String seal(String plain) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new IOException("Couldn't protect the sign-in token: " + e.getMessage(), e);
        }
    }

    private static String open(String sealed) throws IOException {
        try {
            String[] p = sealed.split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IOException("The saved Google sign-in can't be read. Connect Google Drive again.", e);
        }
    }

    // ------------------------------------------------------------------ sign-in

    /** Opens Google sign-in in the browser and waits for it to come back to this phone. */
    static void connect(final Activity a, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (!configured()) {
            cb.done(null, "This build has no Google Drive client ID. See README, “Google Drive setup”.");
            return;
        }
        if (!Prefs.internet(a)) {
            cb.done(null, "Turn on internet access first.");
            return;
        }
        final Context app = a.getApplicationContext();
        new Thread(() -> {
            String email = null;
            String error = null;
            try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(5 * 60 * 1000);
                SecureRandom r = new SecureRandom();
                String verifier = DriveCore.randomToken(r, 48);
                String state = DriveCore.randomToken(r, 16);
                String redirect = "http://127.0.0.1:" + server.getLocalPort();
                String url = DriveCore.authUrl(BuildConfig.DRIVE_CLIENT_ID, redirect, verifier, state);
                main.post(() -> {
                    MusicPlayer.holdForExternal();
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                });

                Map<String, String> q = null;
                while (q == null) {
                    try (Socket s = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        Map<String, String> got = DriveCore.queryOf(in.readLine());
                        boolean ours = got.containsKey("code") || got.containsKey("error");
                        String page = ours
                                ? "<html><body style=\"background:#4A1521;color:#F4EBDD;font-family:sans-serif;padding:32px\">"
                                + "<h2>PDF Scan</h2><p>" + (got.containsKey("code") ? "Signed in. You can go back to the app."
                                : "Sign-in was cancelled.") + "</p></body></html>"
                                : "";
                        byte[] body = page.getBytes(StandardCharsets.UTF_8);
                        OutputStream out = s.getOutputStream();
                        out.write(((ours ? "HTTP/1.1 200 OK" : "HTTP/1.1 404 Not Found")
                                + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(body);
                        out.flush();
                        if (ours) q = got;
                    }
                }
                if (!state.equals(q.get("state"))) throw new IOException("Sign-in response didn't match. Try again.");
                if (q.containsKey("error")) throw new IOException("Sign-in was cancelled (" + q.get("error") + ").");

                Map<String, String> f = new LinkedHashMap<>();
                f.put("code", q.get("code"));
                f.put("client_id", BuildConfig.DRIVE_CLIENT_ID);
                if (!BuildConfig.DRIVE_CLIENT_SECRET.isEmpty()) f.put("client_secret", BuildConfig.DRIVE_CLIENT_SECRET);
                f.put("redirect_uri", redirect);
                f.put("grant_type", "authorization_code");
                f.put("code_verifier", verifier);
                JSONObject t = DriveClient.postForm(app, DriveCore.TOKEN_URL, f);
                String refresh = t.optString("refresh_token", null);
                if (refresh == null) throw new IOException("Google didn't return a long-term sign-in. Try again.");
                email = DriveCore.emailFromIdToken(t.optString("id_token", null));
                prefs(app).edit().putString("refresh", seal(refresh)).putString("email", email).apply();
                synchronized (DriveAccount.class) {
                    accessToken = t.optString("access_token", null);
                    accessExpiry = System.currentTimeMillis() + (t.optLong("expires_in", 0) - 60) * 1000L;
                }
            } catch (SocketTimeoutException e) {
                error = "Sign-in timed out. Try again.";
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String em = email;
            final String er = error;
            main.post(() -> cb.done(em, er));
        }, "drive-signin").start();
    }

    /** A valid access token, refreshed when needed. Network thread only. */
    static synchronized String accessToken(Context c) throws IOException {
        if (accessToken != null && System.currentTimeMillis() < accessExpiry) return accessToken;
        String sealed = prefs(c).getString("refresh", null);
        if (sealed == null) throw new IOException("Google Drive isn't connected");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", BuildConfig.DRIVE_CLIENT_ID);
        if (!BuildConfig.DRIVE_CLIENT_SECRET.isEmpty()) f.put("client_secret", BuildConfig.DRIVE_CLIENT_SECRET);
        f.put("refresh_token", open(sealed));
        f.put("grant_type", "refresh_token");
        JSONObject t;
        try {
            t = DriveClient.postForm(c, DriveCore.TOKEN_URL, f);
        } catch (DriveClient.HttpError e) {
            if (e.code == 400 || e.code == 401) {
                forget(c);
                throw new IOException("Google Drive access was removed. Connect again in Settings.");
            }
            throw e;
        }
        accessToken = t.optString("access_token", null);
        accessExpiry = System.currentTimeMillis() + (t.optLong("expires_in", 0) - 60) * 1000L;
        if (accessToken == null) throw new IOException("Google didn't return an access token");
        return accessToken;
    }

    /** Signs out: revokes the token at Google (if internet is on) and deletes it from the phone. */
    static void disconnect(final Context c) {
        final Context app = c.getApplicationContext();
        final String sealed = prefs(app).getString("refresh", null);
        forget(app);
        if (sealed == null || !Prefs.internet(app)) return;
        new Thread(() -> {
            try {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("token", open(sealed));
                DriveClient.postForm(app, DriveCore.REVOKE_URL, f);
            } catch (IOException ignored) {
                // the local token is gone either way
            }
        }, "drive-revoke").start();
    }

    private static synchronized void forget(Context c) {
        prefs(c).edit().clear().apply();
        Prefs.get(c).edit().remove("drive_folder").apply();
        accessToken = null;
        accessExpiry = 0;
    }
}
