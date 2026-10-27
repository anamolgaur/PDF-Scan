package com.anamol.pdfscan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java pieces of the Google Drive connection (no Android types; unit-tested):
 * OAuth 2.0 with PKCE for an installed app using a loopback redirect, and Drive upload bodies.
 * Scope drive.file: the app can only see files it created itself, nothing else in your Drive.
 */
final class DriveCore {
    static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    static final String FILES_URL = "https://www.googleapis.com/drive/v3/files";
    static final String UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files";
    static final String SCOPES = "openid email https://www.googleapis.com/auth/drive.file";
    static final String FOLDER_NAME = "PDF Scan";
    static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    private DriveCore() {
    }

    static String randomToken(SecureRandom r, int bytes) {
        byte[] b = new byte[bytes];
        r.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** PKCE S256 challenge for a verifier. */
    static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String authUrl(String clientId, String redirect, String verifier, String state) {
        return AUTH_URL + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirect)
                + "&response_type=code"
                + "&scope=" + enc(SCOPES)
                + "&code_challenge=" + enc(challenge(verifier))
                + "&code_challenge_method=S256"
                + "&state=" + enc(state)
                + "&access_type=offline&prompt=consent";
    }

    /** Parses the query of an HTTP request line such as "GET /?code=x&state=y HTTP/1.1". */
    static Map<String, String> queryOf(String requestLine) {
        Map<String, String> out = new LinkedHashMap<>();
        if (requestLine == null) return out;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return out;
        int q = parts[1].indexOf('?');
        if (q < 0) return out;
        for (String kv : parts[1].substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), "UTF-8");
                String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                out.put(k, v);
            } catch (java.io.UnsupportedEncodingException | IllegalArgumentException ignored) {
                // skip malformed pair
            }
        }
        return out;
    }

    static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    /** multipart/related body: JSON metadata part, then the file content part. */
    static byte[] multipart(String boundary, String metadataJson, String mime, byte[] content) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(content.length + 512);
        b.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        b.write(metadataJson.getBytes(StandardCharsets.UTF_8));
        b.write(("\r\n--" + boundary + "\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(content);
        b.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return b.toByteArray();
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Email address from an OpenID id_token (decoded only, not verified: used just for display). */
    static String emailFromIdToken(String idToken) {
        if (idToken == null) return null;
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) return null;
        try {
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"email\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            return m.find() ? m.group(1) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Drive file names must not be empty; keep them readable. */
    static String fileName(String title, String extension) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (t.isEmpty()) t = "Document";
        return t + extension;
    }
}
