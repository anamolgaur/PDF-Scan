package com.anamol.pdfscan;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * The only code in the app that talks to the network. Every call checks the
 * "Allow internet access" setting first, and only Google's OAuth and Drive hosts are used.
 */
final class DriveClient {
    /** An HTTP error from Google, with its status code. */
    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private DriveClient() {
    }

    static void requireInternet(Context c) throws IOException {
        if (!Prefs.internet(c)) throw new IOException("Internet access is off. Turn it on in Settings.");
    }

    static JSONObject postForm(Context c, String url, Map<String, String> fields) throws IOException {
        return request(c, "POST", url, "application/x-www-form-urlencoded",
                DriveCore.form(fields).getBytes(StandardCharsets.UTF_8), null);
    }

    static JSONObject request(Context c, String method, String url, String contentType, byte[] body, String token)
            throws IOException {
        requireInternet(c);
        HttpsURLConnection conn = (HttpsURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setUseCaches(false);
            if ("PATCH".equals(method)) {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            } else {
                conn.setRequestMethod(method);
            }
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", contentType);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = conn.getResponseCode();
            String text = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new HttpError(code, describeError(code, text));
            if (text.trim().isEmpty()) return new JSONObject();
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                return new JSONObject();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static String describeError(int code, String body) {
        try {
            JSONObject o = new JSONObject(body);
            Object err = o.opt("error");
            if (err instanceof JSONObject) return "Google Drive: " + ((JSONObject) err).optString("message", "error " + code);
            String d = o.optString("error_description", null);
            if (d != null) return "Google sign-in: " + d;
            if (err != null) return "Google: " + err;
        } catch (JSONException ignored) {
            // not JSON
        }
        return "Google returned error " + code;
    }

    // ------------------------------------------------------------------ Drive operations

    /** The "PDF Scan" folder in the user's Drive, created on first use. */
    static String ensureFolder(Context c) throws IOException {
        String token = DriveAccount.accessToken(c);
        String id = Prefs.get(c).getString("drive_folder", null);
        if (id != null) {
            try {
                JSONObject f = request(c, "GET", DriveCore.FILES_URL + "/" + id + "?fields=id,trashed", null, null, token);
                if (!f.optBoolean("trashed", false)) return id;
            } catch (HttpError e) {
                if (e.code != 404) throw e;
            }
        }
        String meta = "{\"name\":" + DriveCore.jsonString(DriveCore.FOLDER_NAME)
                + ",\"mimeType\":" + DriveCore.jsonString(DriveCore.FOLDER_MIME) + "}";
        JSONObject f = request(c, "POST", DriveCore.FILES_URL + "?fields=id", "application/json; charset=UTF-8",
                meta.getBytes(StandardCharsets.UTF_8), token);
        id = f.optString("id", null);
        if (id == null) throw new IOException("Google Drive didn't return a folder");
        Prefs.get(c).edit().putString("drive_folder", id).apply();
        return id;
    }

    /** Creates a file in the folder, or replaces the content of an existing one. Returns the file id. */
    static String upload(Context c, String folderId, String name, String mime, byte[] content, String existingId)
            throws IOException {
        String token = DriveAccount.accessToken(c);
        String boundary = "pdfscan" + DriveCore.randomToken(new SecureRandom(), 12);
        String type = "multipart/related; boundary=" + boundary;
        if (existingId != null) {
            try {
                String meta = "{\"name\":" + DriveCore.jsonString(name) + "}";
                JSONObject f = request(c, "PATCH", DriveCore.UPLOAD_URL + "/" + existingId + "?uploadType=multipart&fields=id",
                        type, DriveCore.multipart(boundary, meta, mime, content), token);
                return f.optString("id", existingId);
            } catch (HttpError e) {
                if (e.code != 404) throw e;
                // deleted in Drive: upload a fresh copy
            }
        }
        String meta = "{\"name\":" + DriveCore.jsonString(name) + ",\"parents\":[" + DriveCore.jsonString(folderId) + "]}";
        JSONObject f = request(c, "POST", DriveCore.UPLOAD_URL + "?uploadType=multipart&fields=id",
                type, DriveCore.multipart(boundary, meta, mime, content), token);
        String id = f.optString("id", null);
        if (id == null) throw new IOException("Google Drive didn't return a file id");
        return id;
    }
}
