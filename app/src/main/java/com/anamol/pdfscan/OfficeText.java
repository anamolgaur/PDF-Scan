package com.anamol.pdfscan;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Text from office and plain-text documents (pure Java; unit-tested with LibreOffice output).
 * Word, Excel and PowerPoint (2007+ and OpenDocument) are read properly; RTF is converted;
 * old binary .doc/.xls/.ppt files get a best-effort text scan.
 */
final class OfficeText {
    /** Extracted text split into units: pages, slides or sheets. */
    static final class Result {
        final List<String> parts = new ArrayList<>();
        String unit = "page";   // page, slide, sheet, section
    }

    static final String[] EXTENSIONS = {
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "csv", "md"};

    private static final int MAX_CHARS = 2_000_000;

    private OfficeText() {
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean supported(String name) {
        String e = extension(name);
        for (String s : EXTENSIONS) if (s.equals(e)) return true;
        return false;
    }

    /** Extracts text from any supported file except PDF (see PdfText). */
    static Result extract(File f) throws IOException {
        String ext = extension(f.getName());
        switch (ext) {
            case "docx":
                return docx(f);
            case "xlsx":
                return xlsx(f);
            case "pptx":
                return pptx(f);
            case "odt":
            case "ods":
            case "odp":
                return odf(f, ext);
            case "rtf":
                return single(rtf(readText(f)));
            case "doc":
            case "xls":
            case "ppt":
                return single(binaryScan(Files.readAllBytes(f.toPath())));
            default:
                return single(readText(f));
        }
    }

    private static Result single(String s) {
        Result r = new Result();
        r.unit = "section";
        r.parts.add(cap(s.trim()));
        return r;
    }

    private static String cap(String s) {
        return s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) : s;
    }

    // ------------------------------------------------------------------ plain text

    static String readText(File f) throws IOException {
        byte[] b = Files.readAllBytes(f.toPath());
        return decodeText(b);
    }

    static String decodeText(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        int start = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b, start, b.length - start)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, start, b.length - start, StandardCharsets.ISO_8859_1);
        }
    }

    // ------------------------------------------------------------------ XML text scanning

    static String entity(String name) {
        switch (name) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            default:
                try {
                    if (name.startsWith("#x") || name.startsWith("#X")) return new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
                    if (name.startsWith("#")) return new String(Character.toChars(Integer.parseInt(name.substring(1))));
                } catch (IllegalArgumentException ignored) {
                    // unknown entity
                }
                return "";
        }
    }

    static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i < 12) {
                    sb.append(entity(s.substring(i + 1, semi)));
                    i = semi + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    interface TagHandler {
        /** Called for each tag; name without '<' and attributes; closing tags start with '/'. */
        void tag(String name, String raw, StringBuilder out);
    }

    /**
     * Streams through XML: text inside elements named in textTags is copied out; the handler
     * decides what each tag adds (line breaks, tabs).
     */
    static String scanXml(String xml, String[] textTags, TagHandler handler) {
        StringBuilder out = new StringBuilder();
        int depthInText = 0;
        int i = 0;
        int n = xml.length();
        while (i < n && out.length() < MAX_CHARS) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) lt = n;
            if (depthInText > 0 && lt > i) out.append(unescape(xml.substring(i, lt)));
            if (lt >= n) break;
            if (xml.startsWith("<!--", lt)) {
                int e = xml.indexOf("-->", lt);
                i = e < 0 ? n : e + 3;
                continue;
            }
            if (xml.startsWith("<![CDATA[", lt)) {
                int e = xml.indexOf("]]>", lt);
                if (depthInText > 0) out.append(xml, lt + 9, e < 0 ? n : e);
                i = e < 0 ? n : e + 3;
                continue;
            }
            int gt = xml.indexOf('>', lt);
            if (gt < 0) break;
            String raw = xml.substring(lt + 1, gt);
            boolean selfClose = raw.endsWith("/");
            String body = selfClose ? raw.substring(0, raw.length() - 1) : raw;
            int sp = 0;
            while (sp < body.length() && !Character.isWhitespace(body.charAt(sp))) sp++;
            String name = body.substring(0, sp);
            if (!name.startsWith("?") && !name.startsWith("!")) {
                boolean closing = name.startsWith("/");
                String bare = closing ? name.substring(1) : name;
                boolean isText = false;
                for (String t : textTags) {
                    if (t.equals(bare)) {
                        isText = true;
                        break;
                    }
                }
                if (isText && !selfClose) depthInText += closing ? -1 : 1;
                if (depthInText < 0) depthInText = 0;
                handler.tag(name, raw, out);
            }
            i = gt + 1;
        }
        return out.toString();
    }

    static void newline(StringBuilder out) {
        int n = out.length();
        while (n > 0 && (out.charAt(n - 1) == ' ' || out.charAt(n - 1) == '\t')) out.setLength(--n);
        if (n > 0 && out.charAt(n - 1) != '\n') out.append('\n');
    }

    static String tidy(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\n")) {
            String t = line.replace('\u00A0', ' ').replaceAll("[ ]{2,}", " ").replaceAll("\\t+$", "").trim();
            if (!t.isEmpty()) out.append(t).append('\n');
        }
        return out.toString().trim();
    }

    private static String entryText(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        if (e.getSize() > 200L * 1024 * 1024) throw new IOException("Document part too large");
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream((int) Math.max(1024, e.getSize()));
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                b.write(buf, 0, r);
                if (b.size() > 200 * 1024 * 1024) break;
            }
            return b.toString("UTF-8");
        }
    }

    /** Entries matching prefix + number + suffix, in numeric order. */
    private static List<String> numbered(ZipFile z, String prefix, String suffix) {
        List<int[]> found = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Pattern p = Pattern.compile(Pattern.quote(prefix) + "(\\d+)" + Pattern.quote(suffix));
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            Matcher m = p.matcher(n);
            if (m.matches()) {
                found.add(new int[]{Integer.parseInt(m.group(1)), names.size()});
                names.add(n);
            }
        }
        Collections.sort(found, (a, b) -> Integer.compare(a[0], b[0]));
        List<String> out = new ArrayList<>();
        for (int[] f : found) out.add(names.get(f[1]));
        return out;
    }

    // ------------------------------------------------------------------ Word

    static String wordXml(String xml) {
        return tidy(scanXml(xml, new String[]{"w:t"}, (name, raw, out) -> {
            switch (name) {
                case "/w:p":
                case "w:br":
                case "w:cr":
                    newline(out);
                    break;
                case "w:tab":
                case "/w:tc":
                    out.append('\t');
                    break;
                default:
                    break;
            }
        }));
    }

    static Result docx(File f) throws IOException {
        Result r = new Result();
        r.unit = "section";
        try (ZipFile z = new ZipFile(f)) {
            StringBuilder all = new StringBuilder();
            String body = entryText(z, "word/document.xml");
            if (body == null) throw new IOException("Not a Word document");
            all.append(wordXml(body));
            for (String part : new String[]{"word/footnotes.xml", "word/endnotes.xml"}) {
                String x = entryText(z, part);
                if (x != null) all.append('\n').append(wordXml(x));
            }
            for (String h : numbered(z, "word/header", ".xml")) all.append('\n').append(wordXml(entryText(z, h)));
            for (String h : numbered(z, "word/footer", ".xml")) all.append('\n').append(wordXml(entryText(z, h)));
            r.parts.add(cap(tidy(all.toString())));
        }
        return r;
    }

    // ------------------------------------------------------------------ PowerPoint

    static Result pptx(File f) throws IOException {
        Result r = new Result();
        r.unit = "slide";
        try (ZipFile z = new ZipFile(f)) {
            for (String slide : numbered(z, "ppt/slides/slide", ".xml")) {
                String x = entryText(z, slide);
                r.parts.add(tidy(scanXml(x, new String[]{"a:t"}, (name, raw, out) -> {
                    if (name.equals("/a:p") || name.equals("a:br")) newline(out);
                    else if (name.equals("/a:tc")) out.append('\t');
                })));
            }
            if (r.parts.isEmpty()) throw new IOException("No slides found");
        }
        return r;
    }

    // ------------------------------------------------------------------ Excel

    private static final Pattern ATTR_T = Pattern.compile("\\bt=\"([^\"]*)\"");

    static Result xlsx(File f) throws IOException {
        Result r = new Result();
        r.unit = "sheet";
        try (ZipFile z = new ZipFile(f)) {
            final List<String> shared = new ArrayList<>();
            String ss = entryText(z, "xl/sharedStrings.xml");
            if (ss != null) {
                scanXml(ss, new String[]{"t"}, (name, raw, out) -> {
                    if (name.equals("si")) out.setLength(0);
                    if (name.equals("/si")) {
                        shared.add(out.toString());
                        out.setLength(0);
                    }
                });
            }
            for (String sheet : numbered(z, "xl/worksheets/sheet", ".xml")) {
                String x = entryText(z, sheet);
                final String[] type = {""};
                String text = scanXml(x, new String[]{"v", "t"}, new TagHandler() {
                    int mark;

                    @Override
                    public void tag(String name, String raw, StringBuilder out) {
                        switch (name) {
                            case "c": {
                                Matcher m = ATTR_T.matcher(raw);
                                type[0] = m.find() ? m.group(1) : "";
                                break;
                            }
                            case "v":
                                mark = out.length();
                                break;
                            case "/v":
                                if (type[0].equals("s")) {
                                    String idx = out.substring(mark).trim();
                                    out.setLength(mark);
                                    try {
                                        int k = Integer.parseInt(idx);
                                        if (k >= 0 && k < shared.size()) out.append(shared.get(k));
                                    } catch (NumberFormatException ignored) {
                                        // bad index
                                    }
                                }
                                break;
                            case "/c":
                                out.append('\t');
                                break;
                            case "/row":
                                newline(out);
                                break;
                            case "f":
                            case "/f":
                                break;
                            default:
                                break;
                        }
                    }
                });
                r.parts.add(tidy(text));
            }
            if (r.parts.isEmpty() && !shared.isEmpty()) r.parts.add(String.join("\n", shared));
        }
        return r;
    }

    // ------------------------------------------------------------------ OpenDocument

    static Result odf(File f, String ext) throws IOException {
        Result r = new Result();
        r.unit = ext.equals("odp") ? "slide" : ext.equals("ods") ? "sheet" : "section";
        try (ZipFile z = new ZipFile(f)) {
            String x = entryText(z, "content.xml");
            if (x == null) throw new IOException("Not an OpenDocument file");
            final List<String> parts = r.parts;
            final String splitTag = ext.equals("odp") ? "/draw:page" : ext.equals("ods") ? "/table:table" : null;
            String rest = scanXml(x, new String[]{"text:p", "text:h", "text:span", "text:a"}, (name, raw, out) -> {
                switch (name) {
                    case "/text:p":
                    case "/text:h":
                    case "text:line-break":
                        newline(out);
                        break;
                    case "text:s":
                        out.append(' ');
                        break;
                    case "text:tab":
                    case "/table:table-cell":
                        out.append('\t');
                        break;
                    case "/table:table-row":
                        newline(out);
                        break;
                    default:
                        if (name.equals(splitTag)) {
                            parts.add(tidy(out.toString()));
                            out.setLength(0);
                        }
                        break;
                }
            });
            if (!tidy(rest).isEmpty() || parts.isEmpty()) parts.add(tidy(rest));
        }
        return r;
    }

    // ------------------------------------------------------------------ RTF

    static String rtf(String s) {
        StringBuilder out = new StringBuilder();
        java.util.ArrayDeque<Boolean> skip = new java.util.ArrayDeque<>();
        boolean skipping = false;
        int ucSkip = 1;
        int pendingSkip = 0;
        int i = 0;
        int n = s.length();
        while (i < n && out.length() < MAX_CHARS) {
            char c = s.charAt(i);
            if (c == '{') {
                skip.push(skipping);
                i++;
                // destinations that are not body text
                if (i < n && s.startsWith("\\*", i)) skipping = true;
                else {
                    for (String d : new String[]{"\\fonttbl", "\\colortbl", "\\stylesheet", "\\info", "\\pict",
                            "\\header", "\\footer", "\\listtable", "\\listoverridetable", "\\themedata",
                            "\\datastore", "\\xmlnstbl", "\\rsidtbl", "\\generator", "\\latentstyles", "\\object"}) {
                        if (s.startsWith(d, i)) {
                            skipping = true;
                            break;
                        }
                    }
                }
                continue;
            }
            if (c == '}') {
                skipping = skip.isEmpty() ? false : skip.pop();
                i++;
                continue;
            }
            if (c == '\\') {
                if (i + 1 >= n) break;
                char d = s.charAt(i + 1);
                if (d == '\\' || d == '{' || d == '}') {
                    if (!skipping) out.append(d);
                    i += 2;
                    continue;
                }
                if (d == '\'') {
                    if (i + 3 < n && !skipping) {
                        try {
                            int v = Integer.parseInt(s.substring(i + 2, i + 4), 16);
                            if (pendingSkip > 0) pendingSkip--;
                            else out.append(Glyphs.baseChar("WinAnsiEncoding", v));
                        } catch (NumberFormatException ignored) {
                            // malformed hex
                        }
                    }
                    i += 4;
                    continue;
                }
                if (d == '~') {
                    if (!skipping) out.append(' ');
                    i += 2;
                    continue;
                }
                int j = i + 1;
                while (j < n && Character.isLetter(s.charAt(j))) j++;
                String word = s.substring(i + 1, j);
                int k = j;
                if (k < n && (s.charAt(k) == '-' || Character.isDigit(s.charAt(k)))) {
                    k++;
                    while (k < n && Character.isDigit(s.charAt(k))) k++;
                }
                String arg = s.substring(j, k);
                if (k < n && s.charAt(k) == ' ') k++;
                i = Math.max(k, i + 2);
                if (skipping) continue;
                switch (word) {
                    case "par":
                    case "line":
                    case "row":
                    case "sect":
                    case "page":
                        newline(out);
                        break;
                    case "tab":
                    case "cell":
                        out.append('\t');
                        break;
                    case "uc":
                        try {
                            ucSkip = Integer.parseInt(arg);
                        } catch (NumberFormatException ignored) {
                            ucSkip = 1;
                        }
                        break;
                    case "u":
                        try {
                            int v = Integer.parseInt(arg);
                            if (v < 0) v += 65536;
                            out.append((char) v);
                            pendingSkip = ucSkip;
                        } catch (NumberFormatException ignored) {
                            // malformed
                        }
                        break;
                    case "emdash": out.append('\u2014'); break;
                    case "endash": out.append('\u2013'); break;
                    case "lquote": out.append('\u2018'); break;
                    case "rquote": out.append('\u2019'); break;
                    case "ldblquote": out.append('\u201C'); break;
                    case "rdblquote": out.append('\u201D'); break;
                    case "bullet": out.append('\u2022'); break;
                    default:
                        break;
                }
                continue;
            }
            if (c == '\r' || c == '\n') {
                i++;
                continue;
            }
            if (!skipping) {
                if (pendingSkip > 0) pendingSkip--;
                else out.append(c);
            }
            i++;
        }
        return tidy(out.toString());
    }

    // ------------------------------------------------------------------ old binary Office files

    /**
     * Best-effort text from .doc/.xls/.ppt: collects readable runs of UTF-16LE and 8-bit text.
     * Good enough to find words; not a faithful conversion.
     */
    static String binaryScan(byte[] b) {
        StringBuilder out = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // UTF-16LE runs
        StringBuilder run = new StringBuilder();
        for (int i = 0; i + 1 < b.length; i += 2) {
            int c = (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
            if (readable(c)) {
                run.append((char) c);
            } else {
                flush(run, out, seen, 4);
            }
        }
        flush(run, out, seen, 4);
        // odd-aligned UTF-16LE runs
        for (int i = 1; i + 1 < b.length; i += 2) {
            int c = (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
            if (readable(c)) run.append((char) c);
            else flush(run, out, seen, 4);
        }
        flush(run, out, seen, 4);
        // 8-bit runs (cp1252)
        for (byte x : b) {
            int c = x & 0xFF;
            if (c >= 0x20 && c < 0x7F || c >= 0xA0 || c == '\t') {
                run.append(c >= 0xA0 ? Glyphs.baseChar("WinAnsiEncoding", c) : String.valueOf((char) c));
            } else {
                flush(run, out, seen, 6);
            }
        }
        flush(run, out, seen, 6);
        return tidy(out.toString());
    }

    private static boolean readable(int c) {
        return (c >= 0x20 && c < 0x7F) || c == '\t' || (c >= 0xA0 && c < 0x2000 && Character.isLetterOrDigit(c))
                || (c >= 0x2010 && c <= 0x2027);
    }

    private static void flush(StringBuilder run, StringBuilder out, java.util.Set<String> seen, int minLen) {
        String t = run.toString().trim();
        run.setLength(0);
        if (t.length() < minLen || out.length() > MAX_CHARS) return;
        int letters = 0;
        int spaces = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) letters++;
            else if (c == ' ') spaces++;
        }
        // mostly letters, with word-like spacing; skip binary noise and internal names
        if (letters < t.length() * 0.6 || (t.length() > 12 && spaces == 0 && !t.matches(".*[a-z].*"))) return;
        if (t.length() > 20 && spaces == 0) return;
        if (seen.add(t)) out.append(t).append('\n');
    }
}
