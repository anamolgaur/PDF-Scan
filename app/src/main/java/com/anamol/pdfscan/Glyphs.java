package com.anamol.pdfscan;

import java.util.HashMap;
import java.util.Map;

/** Character tables for PDF simple fonts: base encodings and glyph names (Adobe Glyph List subset). */
final class Glyphs {
    private Glyphs() {
    }

    /** Windows-1252 0x80..0x9F (unassigned codes map to empty). */
    private static final String WIN_80 =
            "€\u0000‚ƒ„…†‡ˆ‰Š‹Œ\u0000Ž\u0000"
                    + "\u0000‘’“”•–—˜™š›œ\u0000žŸ";

    /** Mac OS Roman 0x80..0xFF. */
    private static final String MAC_80 =
            "ÄÅÇÉÑÖÜáàâäãåçéè"
                    + "êëíìîïñóòôöõúùûü"
                    + "†°¢£§•¶ß®©™´¨≠ÆØ"
                    + "∞±≤≥¥µ∂∑∏π∫ªºΩæø"
                    + "¿¡¬√ƒ≈∆«»… ÀÃÕŒœ"
                    + "–—“”‘’÷◊ÿŸ⁄€‹›ﬁﬂ"
                    + "‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔ"
                    + "ÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ";

    private static final Map<Integer, String> STANDARD_HIGH = new HashMap<>();
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        int[][] std = {
                {0xA1, 0xA1}, {0xA2, 0xA2}, {0xA3, 0xA3}, {0xA4, 0x2044}, {0xA5, 0xA5}, {0xA6, 0x192}, {0xA7, 0xA7},
                {0xA8, 0xA4}, {0xA9, 0x27}, {0xAA, 0x201C}, {0xAB, 0xAB}, {0xAC, 0x2039}, {0xAD, 0x203A},
                {0xAE, 0xFB01}, {0xAF, 0xFB02}, {0xB1, 0x2013}, {0xB2, 0x2020}, {0xB3, 0x2021}, {0xB4, 0xB7},
                {0xB6, 0xB6}, {0xB7, 0x2022}, {0xB8, 0x201A}, {0xB9, 0x201E}, {0xBA, 0x201D}, {0xBB, 0xBB},
                {0xBC, 0x2026}, {0xBD, 0x2030}, {0xBF, 0xBF}, {0xC1, 0x60}, {0xC2, 0xB4}, {0xC3, 0x2C6},
                {0xC4, 0x2DC}, {0xC5, 0xAF}, {0xC6, 0x2D8}, {0xC7, 0x2D9}, {0xC8, 0xA8}, {0xCA, 0x2DA},
                {0xCB, 0xB8}, {0xCD, 0x2DD}, {0xCE, 0x2DB}, {0xCF, 0x2C7}, {0xD0, 0x2014}, {0xE1, 0xC6},
                {0xE3, 0xAA}, {0xE8, 0x141}, {0xE9, 0xD8}, {0xEA, 0x152}, {0xEB, 0xBA}, {0xF1, 0xE6},
                {0xF5, 0x131}, {0xF8, 0x142}, {0xF9, 0xF8}, {0xFA, 0x153}, {0xFB, 0xDF}};
        for (int[] e : std) STANDARD_HIGH.put(e[0], String.valueOf((char) e[1]));

        String[][] named = {
                {"space", " "}, {"exclam", "!"}, {"quotedbl", "\""}, {"numbersign", "#"}, {"dollar", "$"},
                {"percent", "%"}, {"ampersand", "&"}, {"quotesingle", "'"}, {"quoteright", "’"},
                {"parenleft", "("}, {"parenright", ")"}, {"asterisk", "*"}, {"plus", "+"}, {"comma", ","},
                {"hyphen", "-"}, {"period", "."}, {"slash", "/"}, {"zero", "0"}, {"one", "1"}, {"two", "2"},
                {"three", "3"}, {"four", "4"}, {"five", "5"}, {"six", "6"}, {"seven", "7"}, {"eight", "8"},
                {"nine", "9"}, {"colon", ":"}, {"semicolon", ";"}, {"less", "<"}, {"equal", "="}, {"greater", ">"},
                {"question", "?"}, {"at", "@"}, {"bracketleft", "["}, {"backslash", "\\"}, {"bracketright", "]"},
                {"asciicircum", "^"}, {"underscore", "_"}, {"grave", "`"}, {"quoteleft", "‘"},
                {"braceleft", "{"}, {"bar", "|"}, {"braceright", "}"}, {"asciitilde", "~"},
                {"endash", "–"}, {"emdash", "—"}, {"bullet", "•"}, {"ellipsis", "…"},
                {"quotedblleft", "“"}, {"quotedblright", "”"}, {"quotesinglbase", "‚"},
                {"quotedblbase", "„"}, {"dagger", "†"}, {"daggerdbl", "‡"}, {"perthousand", "‰"},
                {"guilsinglleft", "‹"}, {"guilsinglright", "›"}, {"guillemotleft", "«"},
                {"guillemotright", "»"}, {"fi", "fi"}, {"fl", "fl"}, {"ff", "ff"}, {"ffi", "ffi"},
                {"ffl", "ffl"}, {"dotlessi", "ı"}, {"dotlessj", "ȷ"}, {"Euro", "€"},
                {"trademark", "™"}, {"copyright", "©"}, {"registered", "®"}, {"degree", "°"},
                {"plusminus", "±"}, {"mu", "µ"}, {"paragraph", "¶"}, {"section", "§"},
                {"cent", "¢"}, {"sterling", "£"}, {"yen", "¥"}, {"currency", "¤"},
                {"brokenbar", "¦"}, {"dieresis", "¨"}, {"macron", "¯"}, {"acute", "´"},
                {"cedilla", "¸"}, {"ordfeminine", "ª"}, {"ordmasculine", "º"},
                {"logicalnot", "¬"}, {"onehalf", "½"}, {"onequarter", "¼"},
                {"threequarters", "¾"}, {"questiondown", "¿"}, {"exclamdown", "¡"},
                {"periodcentered", "·"}, {"minus", "−"}, {"multiply", "×"}, {"divide", "÷"},
                {"florin", "ƒ"}, {"circumflex", "ˆ"}, {"tilde", "˜"}, {"fraction", "⁄"},
                {"germandbls", "ß"}, {"AE", "Æ"}, {"ae", "æ"}, {"OE", "Œ"}, {"oe", "œ"},
                {"Oslash", "Ø"}, {"oslash", "ø"}, {"Lslash", "Ł"}, {"lslash", "ł"},
                {"Eth", "Ð"}, {"eth", "ð"}, {"Thorn", "Þ"}, {"thorn", "þ"},
                {"nbspace", " "}, {"nonbreakingspace", " "}, {"sfthyphen", ""}, {"softhyphen", ""},
                {"visiblespace", " "}, {"compwordmark", ""}, {"quotedblbase", "„"}};
        for (String[] e : named) NAMES.put(e[0], e[1]);
        for (char c = 'A'; c <= 'Z'; c++) NAMES.put(String.valueOf(c), String.valueOf(c));
        for (char c = 'a'; c <= 'z'; c++) NAMES.put(String.valueOf(c), String.valueOf(c));
        // Accented Latin letters: Agrave, eacute, Ccedilla, scaron, ...
        String[][] accents = {{"WITH GRAVE", "grave"}, {"WITH ACUTE", "acute"}, {"WITH CIRCUMFLEX", "circumflex"},
                {"WITH TILDE", "tilde"}, {"WITH DIAERESIS", "dieresis"}, {"WITH RING ABOVE", "ring"},
                {"WITH CEDILLA", "cedilla"}, {"WITH CARON", "caron"}, {"WITH OGONEK", "ogonek"},
                {"WITH DOT ABOVE", "dotaccent"}, {"WITH MACRON", "macron"}, {"WITH BREVE", "breve"},
                {"WITH DOUBLE ACUTE", "hungarumlaut"}, {"WITH STROKE", "slash"}};
        for (char c = 0xC0; c <= 0x17F; c++) {
            String n;
            try {
                n = Character.getName(c);
            } catch (RuntimeException e) {
                n = null;
            }
            if (n == null || !n.startsWith("LATIN ")) continue;
            boolean small = n.startsWith("LATIN SMALL LETTER ");
            boolean cap = n.startsWith("LATIN CAPITAL LETTER ");
            if (!small && !cap) continue;
            String rest = n.substring(small ? 19 : 21);
            int sp = rest.indexOf(' ');
            if (sp != 1) continue;
            String base = rest.substring(0, 1);
            String mod = rest.substring(2);
            for (String[] a : accents) {
                if (mod.equals(a[0])) {
                    NAMES.putIfAbsent((small ? base.toLowerCase() : base) + a[1], String.valueOf(c));
                }
            }
        }
    }

    /** Character for a code in a named base encoding. */
    static String baseChar(String encoding, int code) {
        if (code < 32) return code == 9 || code == 10 || code == 13 ? " " : "";
        boolean win = "WinAnsiEncoding".equals(encoding);
        boolean mac = "MacRomanEncoding".equals(encoding);
        if (code < 127) {
            if (!win && !mac) {
                if (code == 0x27) return "’";
                if (code == 0x60) return "‘";
            }
            return String.valueOf((char) code);
        }
        if (mac && code >= 0x80) return String.valueOf(MAC_80.charAt(code - 0x80));
        if (win || "PDFDocEncoding".equals(encoding)) {
            if (code >= 0x80 && code <= 0x9F) {
                char c = WIN_80.charAt(code - 0x80);
                return c == 0 ? "" : String.valueOf(c);
            }
            return code == 0x7F ? "" : String.valueOf((char) code);
        }
        String s = STANDARD_HIGH.get(code);
        return s != null ? s : "";
    }

    /** Unicode text for a glyph name (uniXXXX, uXXXX, ligatures f_i, suffixes like .sc). */
    static String fromName(String name) {
        if (name == null || name.isEmpty()) return "";
        String direct = NAMES.get(name);
        if (direct != null) return direct;
        int dot = name.indexOf('.');
        if (dot > 0) return fromName(name.substring(0, dot));
        if (name.indexOf('_') > 0) {
            StringBuilder sb = new StringBuilder();
            for (String part : name.split("_")) sb.append(fromName(part));
            return sb.toString();
        }
        try {
            if (name.startsWith("uni") && name.length() >= 7 && (name.length() - 3) % 4 == 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 3; i + 4 <= name.length(); i += 4) sb.append((char) Integer.parseInt(name.substring(i, i + 4), 16));
                return sb.toString();
            }
            if (name.startsWith("u") && name.length() >= 5 && name.length() <= 7) {
                return new String(Character.toChars(Integer.parseInt(name.substring(1), 16)));
            }
        } catch (IllegalArgumentException ignored) {
            // not a hex code
        }
        return "";
    }
}
