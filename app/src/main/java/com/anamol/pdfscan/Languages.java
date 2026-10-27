package com.anamol.pdfscan;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extra text-recognition languages. English is built in; others are optional downloads of the
 * official Tesseract "fast" models (about 1-3 MB each), fetched only when the user taps
 * Download with "Allow internet access" on. After that they work offline like English.
 */
final class Languages {
    /** Groups shown in the language screen: title, then code/name pairs. */
    static final String[][][] GROUPS = {
            {{"Indian languages"},
                    {"hin", "Hindi"}, {"mar", "Marathi (also for Konkani)"}, {"ben", "Bengali"}, {"tam", "Tamil"},
                    {"tel", "Telugu"}, {"kan", "Kannada"}, {"mal", "Malayalam"}, {"guj", "Gujarati"},
                    {"pan", "Punjabi (Gurmukhi)"}, {"ori", "Odia"}, {"asm", "Assamese"}, {"urd", "Urdu"},
                    {"nep", "Nepali"}, {"san", "Sanskrit"}},
            {{"Chinese, Japanese, Korean"},
                    {"chi_sim", "Chinese (Simplified)"}, {"chi_tra", "Chinese (Traditional)"}, {"jpn", "Japanese"},
                    {"kor", "Korean"}},
            {{"European languages"},
                    {"fra", "French"}, {"deu", "German"}, {"spa", "Spanish"}, {"ita", "Italian"}, {"por", "Portuguese"},
                    {"nld", "Dutch"}, {"swe", "Swedish"}, {"nor", "Norwegian"}, {"dan", "Danish"}, {"fin", "Finnish"},
                    {"pol", "Polish"}, {"ces", "Czech"}, {"slk", "Slovak"}, {"hun", "Hungarian"}, {"ron", "Romanian"},
                    {"hrv", "Croatian"}, {"ell", "Greek"}, {"rus", "Russian"}, {"ukr", "Ukrainian"}, {"bul", "Bulgarian"},
                    {"tur", "Turkish"}}};
    static final String[] CODES;
    static final String[] NAMES;

    static {
        List<String> codes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String[][] g : GROUPS) {
            for (int i = 1; i < g.length; i++) {
                codes.add(g[i][0]);
                names.add(g[i][1]);
            }
        }
        CODES = codes.toArray(new String[0]);
        NAMES = names.toArray(new String[0]);
    }
    private static final String BASE = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/";
    private static final long MAX_BYTES = 30L * 1024 * 1024;

    private Languages() {
    }

    static File model(Context c, String code) {
        return new File(new File(c.getFilesDir(), "tessdata"), code + ".traineddata");
    }

    static boolean installed(Context c, String code) {
        File f = model(c, code);
        return f.isFile() && f.length() > 100_000;
    }

    static Set<String> enabled(Context c) {
        return new HashSet<>(Prefs.get(c).getStringSet("ocr_langs", new HashSet<>()));
    }

    static void setEnabled(Context c, String code, boolean on) {
        Set<String> s = enabled(c);
        if (on) s.add(code);
        else s.remove(code);
        Prefs.get(c).edit().putStringSet("ocr_langs", s).apply();
    }

    /** The Tesseract language string: English plus every installed, switched-on language. */
    static String active(Context c) {
        StringBuilder sb = new StringBuilder("eng");
        Set<String> on = enabled(c);
        for (String code : CODES) if (on.contains(code) && installed(c, code)) sb.append('+').append(code);
        return sb.toString();
    }

    static List<String> activeNames(Context c) {
        List<String> out = new ArrayList<>();
        out.add("English");
        Set<String> on = enabled(c);
        for (int i = 0; i < CODES.length; i++) if (on.contains(CODES[i]) && installed(c, CODES[i])) out.add(NAMES[i]);
        return out;
    }

    /**
     * Downloads one model. Call off the main thread. With "Allow internet access" off, the user
     * must have confirmed this one download (oneOff).
     */
    static void download(Context c, String code, boolean oneOff) throws IOException {
        fetch(c, BASE + code + ".traineddata", model(c, code), oneOff);
        setEnabled(c, code, true);
    }

    private static final String BEST_ENGLISH = "https://github.com/tesseract-ocr/tessdata_best/raw/main/eng.traineddata";

    /** The larger, more accurate English model (better on neat handwriting and poor prints). */
    static boolean bestEnglish(Context c) {
        return Prefs.get(c).getBoolean("ocr_eng_best", false) && model(c, "eng").length() > 8_000_000;
    }

    static void downloadBestEnglish(Context c, boolean oneOff) throws IOException {
        fetch(c, BEST_ENGLISH, model(c, "eng"), oneOff);
        Prefs.get(c).edit().putBoolean("ocr_eng_best", true).apply();
    }

    /** Back to the built-in fast English model (copied again from the app on next use). */
    static void removeBestEnglish(Context c) {
        //noinspection ResultOfMethodCallIgnored
        model(c, "eng").delete();
        Prefs.get(c).edit().putBoolean("ocr_eng_best", false).apply();
    }

    private static void fetch(Context c, String address, File target, boolean oneOff) throws IOException {
        if (!Prefs.internet(c) && !oneOff) throw new IOException("Internet access is off.");
        //noinspection ResultOfMethodCallIgnored
        target.getParentFile().mkdirs();
        File tmp = new File(target.getPath() + ".part");
        URL url = new URL(address);
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout(15000);
            http.setReadTimeout(30000);
            int status = http.getResponseCode();
            if (status >= 300 && status < 400) {
                String loc = http.getHeaderField("Location");
                http.disconnect();
                if (loc == null) break;
                URL next = new URL(url, loc);
                if (!"https".equals(next.getProtocol())) throw new IOException("Insecure download refused");
                url = next;
                continue;
            }
            if (status != 200) {
                http.disconnect();
                throw new IOException("Download failed (" + status + ")");
            }
            long total = 0;
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("The download was too large");
                    out.write(buf, 0, n);
                }
            } finally {
                http.disconnect();
            }
            if (total < 100_000) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("The download was incomplete");
            }
            if (!tmp.renameTo(target)) throw new IOException("Couldn't save the language");
            return;
        }
        throw new IOException("Too many redirects");
    }

    static void remove(Context c, String code) {
        //noinspection ResultOfMethodCallIgnored
        model(c, code).delete();
        setEnabled(c, code, false);
    }
}
