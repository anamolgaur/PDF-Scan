package com.anamol.pdfscan;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java transcript parsing and matching (no Android types, so it is testable on a desktop JVM).
 *
 * Transcript format, one file per document:
 * <pre>
 * Title
 * Transcript · N pages
 *
 * --- Page 1 ---
 * text of page one
 *
 * --- Page 2 ---
 * ...
 * </pre>
 */
final class SearchCore {
    static final String NOT_INDEXED = "[Text not recognised yet]";

    /** A document that matches the query. pages are 1-based. */
    static final class Hit {
        boolean titleMatch;
        final List<Integer> pages = new ArrayList<>();
        String snippet = "";
        int matchStart = -1;
        int matchLength;

        int firstPage() {
            return pages.isEmpty() ? -1 : pages.get(0);
        }
    }

    private SearchCore() {
    }

    static String pageHeader(int page) {
        return "--- Page " + page + " ---";
    }

    /** Builds transcript text from a title and the recognised text of each page (null = not recognised). */
    static String build(String title, List<String> pageTexts) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        int n = pageTexts.size();
        sb.append("Transcript · ").append(n).append(n == 1 ? " page" : " pages").append("\n\n");
        for (int i = 0; i < n; i++) {
            String t = pageTexts.get(i);
            sb.append(pageHeader(i + 1)).append('\n');
            if (t == null) sb.append(NOT_INDEXED);
            else if (!t.trim().isEmpty()) sb.append(t.trim());
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /** Splits a transcript into page texts (index 0 = page 1). Header lines before page 1 are ignored. */
    static List<String> pagesOf(String transcript) {
        List<String> pages = new ArrayList<>();
        if (transcript == null) return pages;
        StringBuilder cur = null;
        for (String line : transcript.split("\n", -1)) {
            if (line.startsWith("--- Page ") && line.endsWith(" ---")) {
                if (cur != null) pages.add(cur.toString().trim());
                cur = new StringBuilder();
            } else if (cur != null) {
                cur.append(line).append('\n');
            }
        }
        if (cur != null) pages.add(cur.toString().trim());
        for (int i = 0; i < pages.size(); i++) {
            if (NOT_INDEXED.equals(pages.get(i))) pages.set(i, "");
        }
        return pages;
    }

    /** Han, Hiragana, Katakana or Hangul: scripts written without spaces between words. */
    static boolean isCjk(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL
                || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF);
    }

    /**
     * Text recognition puts spaces between Chinese and Japanese characters. Removes spaces that
     * sit between two such characters, so a search for a word finds it. Korean keeps its spaces
     * (they separate words) and other scripts are left alone.
     */
    static String compactCjk(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' && out.length() > 0) {
                int j = i;
                while (j < n && s.charAt(j) == ' ') j++;
                int before = out.codePointBefore(out.length());
                int after = j < n ? s.codePointAt(j) : -1;
                if (after >= 0 && isCjk(before) && isCjk(after)
                        && Character.UnicodeScript.of(before) != Character.UnicodeScript.HANGUL
                        && Character.UnicodeScript.of(after) != Character.UnicodeScript.HANGUL) {
                    i = j;  // drop the spaces
                    continue;
                }
                out.append(s, i, j);
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    static String[] terms(String query) {
        if (query == null) return new String[0];
        String q = query.trim();
        return q.isEmpty() ? new String[0] : q.split("\\s+");
    }

    /** Case-insensitive indexOf that keeps positions aligned with the original string. */
    static int indexOfIgnoreCase(String hay, String needle, int from) {
        int max = hay.length() - needle.length();
        for (int i = Math.max(0, from); i <= max; i++) {
            if (hay.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static boolean containsAll(String text, String[] terms) {
        for (String t : terms) {
            if (indexOfIgnoreCase(text, t, 0) < 0) return false;
        }
        return true;
    }

    /**
     * Every term must appear somewhere in the title or text. Returns null when the document
     * does not match. Pages list those containing every term, or failing that the first term.
     */
    static Hit match(String title, List<String> pageTexts, String query) {
        String[] terms = terms(query);
        if (terms.length == 0) return null;

        StringBuilder all = new StringBuilder(title == null ? "" : title);
        for (String p : pageTexts) all.append('\n').append(p);
        if (!containsAll(all.toString(), terms)) return null;

        Hit hit = new Hit();
        hit.titleMatch = title != null && containsAll(title, terms);
        for (int i = 0; i < pageTexts.size(); i++) {
            if (containsAll(pageTexts.get(i), terms)) hit.pages.add(i + 1);
        }
        if (hit.pages.isEmpty()) {
            for (int i = 0; i < pageTexts.size(); i++) {
                if (indexOfIgnoreCase(pageTexts.get(i), terms[0], 0) >= 0) hit.pages.add(i + 1);
            }
        }
        if (!hit.pages.isEmpty()) {
            String text = pageTexts.get(hit.firstPage() - 1).replaceAll("\\s+", " ").trim();
            // Prefer the whole query as a phrase; otherwise highlight the first term.
            String needle = String.join(" ", terms);
            int idx = indexOfIgnoreCase(text, needle, 0);
            if (idx < 0) {
                needle = terms[0];
                idx = indexOfIgnoreCase(text, needle, 0);
            }
            if (idx >= 0) {
                int start = Math.max(0, idx - 40);
                int end = Math.min(text.length(), idx + needle.length() + 60);
                if (start > 0) {
                    int sp = text.indexOf(' ', start);
                    if (sp >= 0 && sp < idx) start = sp + 1;
                }
                if (end < text.length()) {
                    int sp = text.lastIndexOf(' ', end);
                    if (sp > idx + needle.length()) end = sp;
                }
                String prefix = start > 0 ? "…" : "";
                String suffix = end < text.length() ? "…" : "";
                hit.snippet = prefix + text.substring(start, end) + suffix;
                hit.matchStart = prefix.length() + (idx - start);
                hit.matchLength = needle.length();
            }
        }
        return hit;
    }
}
