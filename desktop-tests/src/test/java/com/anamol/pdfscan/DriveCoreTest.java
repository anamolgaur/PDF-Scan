package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class DriveCoreTest {
    @Test
    public void pkceMatchesRfc7636Example() {
        // RFC 7636, Appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                DriveCore.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    public void verifierIsUrlSafeAndLongEnough() {
        String v = DriveCore.randomToken(new SecureRandom(), 48);
        assertTrue(v.length() >= 43 && v.length() <= 128);
        assertTrue(v.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    public void authUrlCarriesEverything() {
        String u = DriveCore.authUrl("id.apps.googleusercontent.com", "http://127.0.0.1:5000", "verifier", "st");
        assertTrue(u.startsWith(DriveCore.AUTH_URL + "?client_id=id.apps.googleusercontent.com"));
        assertTrue(u.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A5000"));
        assertTrue(u.contains("scope=openid%20email%20https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"));
        assertTrue(u.contains("code_challenge_method=S256"));
        assertTrue(u.contains("access_type=offline"));
        assertTrue(u.contains("state=st"));
    }

    @Test
    public void parsesRedirectRequestLine() {
        Map<String, String> q = DriveCore.queryOf("GET /?state=abc&code=4%2F0Ab_x-y&scope=email%20openid HTTP/1.1");
        assertEquals("abc", q.get("state"));
        assertEquals("4/0Ab_x-y", q.get("code"));
        assertEquals("email openid", q.get("scope"));
        assertTrue(DriveCore.queryOf("GET /favicon.ico HTTP/1.1").isEmpty());
    }

    @Test
    public void formEncodes() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", "4/a b");
        assertEquals("grant_type=authorization_code&code=4%2Fa%20b", DriveCore.form(f));
    }

    @Test
    public void multipartLayout() throws Exception {
        byte[] body = DriveCore.multipart("BND", "{\"name\":\"a.pdf\"}", "application/pdf", new byte[]{1, 2, 3});
        String s = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("--BND\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{\"name\":\"a.pdf\"}\r\n--BND\r\n"));
        assertTrue(s.contains("Content-Type: application/pdf\r\n\r\n\u0001\u0002\u0003\r\n--BND--\r\n"));
    }

    @Test
    public void jsonStringEscapes() {
        assertEquals("\"Lab \\\"notes\\\" \\\\ 2\\n\"", DriveCore.jsonString("Lab \"notes\" \\ 2\n"));
    }

    @Test
    public void emailFromIdToken() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"x\",\"email\":\"me@example.com\",\"sub\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("me@example.com", DriveCore.emailFromIdToken("h." + payload + ".sig"));
        assertNull(DriveCore.emailFromIdToken("garbage"));
    }

    @Test
    public void fileNamesAreSafe() {
        assertEquals("a_b_c.pdf", DriveCore.fileName("a/b:c", ".pdf"));
        assertEquals("Document.txt", DriveCore.fileName("  ", ".txt"));
    }
}
