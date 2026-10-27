package com.anamol.pdfscan;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-text index of documents found on the phone (SQLite FTS4, private to the app).
 * Only text is stored; the files themselves are never changed, copied or uploaded.
 */
final class DeviceIndex extends SQLiteOpenHelper {
    static final int PENDING = 0;
    static final int INDEXED = 1;
    static final int FAILED = 2;
    static final int LOCKED = 3;
    static final int EMPTY = 4;

    static final char MARK_START = '\u0001';
    static final char MARK_END = '\u0002';

    /** One file that matches a search. parts are 1-based page/slide/sheet numbers. */
    static final class Hit {
        long id;
        String path;
        String name;
        String unit;
        long mtime;
        long size;
        final List<Integer> parts = new ArrayList<>();
        String snippet = "";
    }

    private static DeviceIndex instance;

    static synchronized DeviceIndex get(Context c) {
        if (instance == null) instance = new DeviceIndex(c.getApplicationContext());
        return instance;
    }

    private DeviceIndex(Context c) {
        super(c, "phone_index.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE files (id INTEGER PRIMARY KEY, path TEXT UNIQUE NOT NULL, name TEXT, "
                + "size INTEGER, mtime INTEGER, status INTEGER DEFAULT 0, unit TEXT, parts INTEGER DEFAULT 0, "
                + "seen INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX files_status ON files(status)");
        db.execSQL("CREATE VIRTUAL TABLE body USING fts4(content, file, part, notindexed=file, notindexed=part)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS files");
        db.execSQL("DROP TABLE IF EXISTS body");
        onCreate(db);
    }

    /** Records a file found during a scan. Marks it for indexing when it is new or changed. */
    void seen(String path, String name, long size, long mtime, long scan) {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT id, size, mtime FROM files WHERE path = ?", new String[]{path})) {
            if (c.moveToFirst()) {
                long id = c.getLong(0);
                ContentValues v = new ContentValues();
                v.put("seen", scan);
                if (c.getLong(1) != size || c.getLong(2) != mtime) {
                    v.put("size", size);
                    v.put("mtime", mtime);
                    v.put("status", PENDING);
                }
                db.update("files", v, "id = ?", new String[]{String.valueOf(id)});
                return;
            }
        }
        ContentValues v = new ContentValues();
        v.put("path", path);
        v.put("name", name);
        v.put("size", size);
        v.put("mtime", mtime);
        v.put("status", PENDING);
        v.put("seen", scan);
        db.insert("files", null, v);
    }

    /** Forgets files that were not found in the latest complete scan. */
    void removeUnseen(long scan) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM body WHERE file IN (SELECT id FROM files WHERE seen <> ?)", new Object[]{scan});
            db.execSQL("DELETE FROM files WHERE seen <> ?", new Object[]{scan});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Next files waiting to be indexed: {id, path}. */
    List<String[]> pending(int limit) {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, path FROM files WHERE status = ? ORDER BY mtime DESC LIMIT " + limit,
                new String[]{String.valueOf(PENDING)})) {
            while (c.moveToNext()) out.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1)});
        }
        return out;
    }

    /** Stores the text of one file (replacing any older text) and its result status. */
    void store(long id, int status, String unit, List<String> parts) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM body WHERE file = ?", new Object[]{id});
            int n = 0;
            if (parts != null) {
                for (int i = 0; i < parts.size(); i++) {
                    String t = parts.get(i);
                    if (t == null || t.trim().isEmpty()) continue;
                    ContentValues v = new ContentValues();
                    v.put("content", t);
                    v.put("file", id);
                    v.put("part", i + 1);
                    db.insert("body", null, v);
                    n++;
                }
            }
            ContentValues f = new ContentValues();
            f.put("status", n == 0 && status == INDEXED ? EMPTY : status);
            f.put("unit", unit);
            f.put("parts", parts == null ? 0 : parts.size());
            db.update("files", f, "id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** {total files, indexed or finished, waiting}. */
    int[] counts() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), SUM(CASE WHEN status <> 0 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) FROM files", null)) {
            if (c.moveToFirst()) return new int[]{c.getInt(0), c.getInt(1), c.getInt(2)};
        }
        return new int[]{0, 0, 0};
    }

    /** Drops a file (deleted from the phone) from the index. */
    void forget(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM body WHERE file = ?", new Object[]{id});
            db.execSQL("DELETE FROM files WHERE id = ?", new Object[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    String pathOf(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT path FROM files WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM body");
        db.execSQL("DELETE FROM files");
    }

    /** Turns a user query into an FTS4 query: every word must match, as a prefix. */
    static String ftsQuery(String q) {
        StringBuilder sb = new StringBuilder();
        for (String t : q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t).append('*');
        }
        return sb.toString();
    }

    List<Hit> search(String query, int limit) {
        if (hasCjk(query)) return searchByText(query, limit);
        String fts = ftsQuery(query);
        List<Hit> out = new ArrayList<>();
        if (fts.isEmpty()) return out;
        Map<Long, Hit> byFile = new LinkedHashMap<>();
        String sql = "SELECT f.id, f.path, f.name, f.unit, f.mtime, b.part, "
                + "snippet(body, '" + MARK_START + "', '" + MARK_END + "', '…', 0, 14), f.size "
                + "FROM body b JOIN files f ON f.id = b.file WHERE body MATCH ? ORDER BY f.mtime DESC, b.part LIMIT 600";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{fts})) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                Hit h = byFile.get(id);
                if (h == null) {
                    if (byFile.size() >= limit) continue;
                    h = new Hit();
                    h.id = id;
                    h.path = c.getString(1);
                    h.name = c.getString(2);
                    h.unit = c.getString(3);
                    h.mtime = c.getLong(4);
                    h.size = c.getLong(7);
                    h.snippet = c.getString(6).replaceAll("\\s+", " ").trim();
                    byFile.put(id, h);
                }
                h.parts.add(c.getInt(5));
            }
        } catch (RuntimeException e) {
            // malformed FTS query: no results
        }
        out.addAll(byFile.values());
        return out;
    }

    private static boolean hasCjk(String q) {
        for (int i = 0; i < q.length(); ) {
            int cp = q.codePointAt(i);
            if (SearchCore.isCjk(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Chinese and Japanese have no spaces, so the word index can't split them into words: search
     * the text itself for every term instead, and cut a snippet around the first match.
     */
    private List<Hit> searchByText(String query, int limit) {
        String[] terms = SearchCore.terms(SearchCore.compactCjk(query));
        List<Hit> out = new ArrayList<>();
        if (terms.length == 0) return out;
        StringBuilder where = new StringBuilder();
        String[] args = new String[terms.length];
        for (int i = 0; i < terms.length; i++) {
            if (i > 0) where.append(" AND ");
            where.append("b.content LIKE ? ESCAPE '\\'");
            args[i] = "%" + terms[i].replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        }
        Map<Long, Hit> byFile = new LinkedHashMap<>();
        String sql = "SELECT f.id, f.path, f.name, f.unit, f.mtime, b.part, b.content, f.size "
                + "FROM body b JOIN files f ON f.id = b.file WHERE " + where + " ORDER BY f.mtime DESC, b.part LIMIT 600";
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                Hit h = byFile.get(id);
                if (h == null) {
                    if (byFile.size() >= limit) continue;
                    h = new Hit();
                    h.id = id;
                    h.path = c.getString(1);
                    h.name = c.getString(2);
                    h.unit = c.getString(3);
                    h.mtime = c.getLong(4);
                    h.size = c.getLong(7);
                    String text = c.getString(6).replaceAll("\\s+", " ");
                    int at = text.indexOf(terms[0]);
                    if (at >= 0) {
                        int s = Math.max(0, at - 20);
                        int e = Math.min(text.length(), at + terms[0].length() + 30);
                        h.snippet = (s > 0 ? "\u2026" : "") + text.substring(s, at) + MARK_START + terms[0] + MARK_END
                                + text.substring(at + terms[0].length(), e) + (e < text.length() ? "\u2026" : "");
                    } else {
                        h.snippet = text.substring(0, Math.min(60, text.length()));
                    }
                    byFile.put(id, h);
                }
                h.parts.add(c.getInt(5));
            }
        } catch (RuntimeException e) {
            // no results
        }
        out.addAll(byFile.values());
        return out;
    }

    /** A document on the phone, for browsing. */
    static final class Entry {
        long id;
        String path;
        String name;
        long size;
        long mtime;
        int status;
    }

    /** Type filters for browsing: label and file extensions. */
    static final String[] TYPE_LABELS = {"All", "PDF", "Word", "Excel", "PowerPoint", "Text"};
    static final String[][] TYPE_EXTS = {
            {},
            {"pdf"},
            {"doc", "docx", "odt", "rtf"},
            {"xls", "xlsx", "ods", "csv"},
            {"ppt", "pptx", "odp"},
            {"txt", "md"}};

    static boolean matchesType(String name, int type) {
        if (type <= 0) return true;
        String e = OfficeText.extension(name);
        for (String x : TYPE_EXTS[type]) if (x.equals(e)) return true;
        return false;
    }

    private static String typeWhere(int type) {
        if (type <= 0) return "";
        StringBuilder sb = new StringBuilder(" WHERE (");
        String[] exts = TYPE_EXTS[type];
        for (int i = 0; i < exts.length; i++) {
            if (i > 0) sb.append(" OR ");
            sb.append("LOWER(name) LIKE '%.").append(exts[i]).append("'");
        }
        return sb.append(")").toString();
    }

    /** Documents on the phone, newest first. */
    List<Entry> browse(int type, int limit, int offset) {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, path, name, size, mtime, status FROM files"
                + typeWhere(type) + " ORDER BY mtime DESC LIMIT " + limit + " OFFSET " + offset, null)) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getLong(0);
                e.path = c.getString(1);
                e.name = c.getString(2);
                e.size = c.getLong(3);
                e.mtime = c.getLong(4);
                e.status = c.getInt(5);
                out.add(e);
            }
        }
        return out;
    }

    int count(int type) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM files" + typeWhere(type), null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** All indexed text of one file, with part headings. */
    String text(long id) {
        StringBuilder sb = new StringBuilder();
        String unit = "page";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT unit FROM files WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst() && c.getString(0) != null) unit = c.getString(0);
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT part, content FROM body WHERE file = ? ORDER BY part",
                new String[]{String.valueOf(id)})) {
            while (c.moveToNext()) {
                if (!unit.equals("section")) {
                    sb.append("--- ").append(Character.toUpperCase(unit.charAt(0))).append(unit.substring(1))
                            .append(' ').append(c.getInt(0)).append(" ---\n");
                }
                sb.append(c.getString(1)).append("\n\n");
            }
        }
        return sb.toString().trim();
    }
}
