package com.anamol.pdfscan;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Documents are plain folders in the app's private storage:
 *   docs/<id>/title.txt
 *   docs/<id>/p<seq>.jpg    finished page (what you see and what is exported)
 *   docs/<id>/p<seq>.src    untouched original, so crop/rotate/filter can be redone
 *   docs/<id>/p<seq>.json   page settings and recognised text
 * Page order is the order of the file names. Nothing is kept in a database.
 */
final class DocStore {
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() * 1000L);
    /** Things in the recycle bin are deleted for good after this long. */
    static final long TRASH_DAYS = 30;
    private final File root;
    private final File trash;

    DocStore(Context context) {
        root = new File(context.getFilesDir(), "docs");
        trash = new File(context.getFilesDir(), "trash");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        trash.mkdirs();
    }

    File root() {
        return root;
    }

    // ------------------------------------------------------------------ folders and pins (info.json)

    private static org.json.JSONObject info(File dir) {
        try {
            File f = new File(dir, "info.json");
            if (f.exists()) return new org.json.JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | org.json.JSONException ignored) {
            // treat as empty
        }
        return new org.json.JSONObject();
    }

    private static void writeInfo(File dir, org.json.JSONObject o) {
        try {
            Files.write(new File(dir, "info.json").toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // cosmetic
        }
    }

    /** The folder a document is filed in, or "" for none. */
    String getFolder(File dir) {
        return info(dir).optString("folder", "");
    }

    void setFolder(File dir, String folder) {
        org.json.JSONObject o = info(dir);
        try {
            if (folder == null || folder.trim().isEmpty()) o.remove("folder");
            else o.put("folder", folder.trim());
        } catch (org.json.JSONException ignored) {
            return;
        }
        writeInfo(dir, o);
    }

    boolean isPinned(File dir) {
        return info(dir).optBoolean("pinned", false);
    }

    void setPinned(File dir, boolean pinned) {
        org.json.JSONObject o = info(dir);
        try {
            if (pinned) o.put("pinned", true);
            else o.remove("pinned");
        } catch (org.json.JSONException ignored) {
            return;
        }
        writeInfo(dir, o);
    }

    /** Records where an edited PDF came from (a file on the phone, or another app's link). */
    void setOrigin(File dir, String path, String uri, String name) {
        org.json.JSONObject o = info(dir);
        try {
            if (path != null) o.put("originPath", path);
            if (uri != null) o.put("originUri", uri);
            if (name != null) o.put("originName", name);
        } catch (org.json.JSONException ignored) {
            return;
        }
        writeInfo(dir, o);
    }

    String getOriginPath(File dir) {
        String s = info(dir).optString("originPath", "");
        return s.isEmpty() ? null : s;
    }

    String getOriginUri(File dir) {
        String s = info(dir).optString("originUri", "");
        return s.isEmpty() ? null : s;
    }

    String getOriginName(File dir) {
        String s = info(dir).optString("originName", "");
        if (!s.isEmpty()) return s;
        return getOriginPath(dir) != null ? new File(getOriginPath(dir)).getName() : null;
    }

    boolean hasOrigin(File dir) {
        return getOriginPath(dir) != null || getOriginUri(dir) != null;
    }

    /** Every folder name in use, alphabetically. */
    List<String> folders() {
        java.util.TreeSet<String> s = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (File d : listDocs()) {
            String f = getFolder(d);
            if (!f.isEmpty()) s.add(f);
        }
        return new ArrayList<>(s);
    }

    /** Total size of a document's files, in bytes. */
    static long sizeOf(File dir) {
        long n = 0;
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) n += k.isDirectory() ? sizeOf(k) : k.length();
        return n;
    }

    // ------------------------------------------------------------------ space

    /** Bytes used by all scans, and by the original photos kept for re-editing: {total, originals}. */
    long[] spaceUsed() {
        long total = 0, originals = 0;
        for (File d : listDocs()) {
            File[] files = d.listFiles(File::isFile);
            if (files == null) continue;
            for (File f : files) {
                total += f.length();
                if (f.getName().endsWith(".src")) originals += f.length();
            }
        }
        return new long[]{total, originals};
    }

    /**
     * Frees space by removing original photos of pages last edited more than `days` ago (0 = all).
     * Those pages keep their finished image and can still be edited, starting from that image.
     * Returns the bytes freed.
     */
    long pruneOriginals(int days) {
        long limit = System.currentTimeMillis() - days * 24L * 3600_000L;
        long freed = 0;
        synchronized (Indexer.LOCK) {
            for (File d : listDocs()) {
                File[] srcs = d.listFiles((dir, n) -> n.endsWith(".src"));
                if (srcs == null) continue;
                for (File s : srcs) {
                    File page = new File(d, s.getName().substring(0, s.getName().length() - 4) + ".jpg");
                    long last = Math.max(s.lastModified(), page.lastModified());
                    if (days == 0 || last < limit) {
                        long n = s.length();
                        if (s.delete()) freed += n;
                        // the finished page is now the starting point: its edits are already in it
                        File meta = metaFile(page);
                        if (meta.exists()) {
                            PageMeta m = PageMeta.read(page);
                            m.rotation = 0;
                            m.corners = null;
                            m.filter = ImageOps.ORIGINAL;
                            m.glare = false;
                            m.brightness = 0;
                            m.contrast = 0;
                            m.sharpen = 0;
                            m.curve = 0;
                            m.erase.clear();
                            m.marks.clear();
                            m.srcPdf = null;  // the finished picture is now the page
                            try {
                                m.write(page);
                            } catch (IOException ignored) {
                                // keeps old settings; next edit starts from the finished page anyway
                            }
                        }
                    }
                }
            }
        }
        return freed;
    }

    // ------------------------------------------------------------------ recycle bin

    /** One thing in the recycle bin: a whole document, or a single page. */
    static final class TrashItem {
        File dir;          // trash/<id>/
        boolean page;      // a single page rather than a document
        String title;      // document title (for a page: the document it came from)
        String from;       // for a page: folder name of the document it came from
        int position;      // for a page: its position in that document (0-based)
        long deletedAt;
        boolean file;      // a document deleted from the phone's storage
        String origin;     // for a file: where it was on the phone
    }

    private File newTrashDir() {
        long id = System.currentTimeMillis();
        File d;
        do {
            d = new File(trash, String.format(Locale.US, "%013d", id++));
        } while (d.exists());
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static void writeTrashInfo(File d, boolean page, String title, String from, int position) {
        writeTrashInfo(d, page ? "page" : "doc", title, from, position, null);
    }

    private static void writeTrashInfo(File d, String kind, String title, String from, int position, String origin) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("kind", kind);
            if (origin != null) o.put("origin", origin);
            o.put("title", title);
            if (from != null) o.put("from", from);
            o.put("position", position);
            o.put("deletedAt", System.currentTimeMillis());
            Files.write(new File(d, "trash.json").toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | org.json.JSONException ignored) {
            // the item still restores as a document
        }
    }

    /** Moves a document to the recycle bin. */
    void trashDoc(File dir) throws IOException {
        synchronized (Indexer.LOCK) {
            String title = getTitle(dir);
            File d = newTrashDir();
            File doc = new File(d, "doc");
            Files.move(dir.toPath(), doc.toPath());
            writeTrashInfo(d, false, title, null, 0);
        }
    }

    /** Moves one page (with its original and settings) to the recycle bin. */
    void trashPage(File jpg) throws IOException {
        synchronized (Indexer.LOCK) {
            File dir = jpg.getParentFile();
            int position = Arrays.asList(pages(dir)).indexOf(jpg);
            File d = newTrashDir();
            File doc = new File(d, "doc");
            //noinspection ResultOfMethodCallIgnored
            doc.mkdirs();
            movePageLocked(jpg, doc);
            writeTrashInfo(d, true, getTitle(dir), dir.getName(), Math.max(0, position));
        }
    }

    List<TrashItem> listTrash() {
        List<TrashItem> out = new ArrayList<>();
        File[] dirs = trash.listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) {
            TrashItem it = new TrashItem();
            it.dir = d;
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                File f = new File(d, "trash.json");
                if (f.exists()) o = new org.json.JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (IOException | org.json.JSONException ignored) {
                // defaults below
            }
            it.page = "page".equals(o.optString("kind"));
            it.file = "file".equals(o.optString("kind"));
            it.origin = o.optString("origin", null);
            it.title = o.optString("title", "Untitled");
            it.from = o.optString("from", null);
            it.position = o.optInt("position", 0);
            it.deletedAt = o.optLong("deletedAt", d.lastModified());
            out.add(it);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.deletedAt, a.deletedAt));
        return out;
    }

    static File trashContent(TrashItem it) {
        return new File(it.dir, "doc");
    }

    /**
     * Deletes a document from the phone's storage, keeping a copy in the recycle bin for
     * TRASH_DAYS so it can be put back where it was.
     */
    void trashPhoneFile(File f) throws IOException {
        File d = newTrashDir();
        File holder = new File(d, "file");
        //noinspection ResultOfMethodCallIgnored
        holder.mkdirs();
        File copy = new File(holder, f.getName());
        Files.copy(f.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (copy.length() != f.length() || !f.delete()) {
            deleteRecursive(d);
            throw new IOException("Android didn't allow deleting " + f.getName());
        }
        writeTrashInfo(d, "file", f.getName(), null, 0, f.getAbsolutePath());
    }

    /** Keeps a copy of a file in the recycle bin (the file itself stays), e.g. before replacing it. */
    void keepCopyInTrash(File f, String restoreTo) throws IOException {
        keepBytesInTrash(Files.readAllBytes(f.toPath()), f.getName(), restoreTo);
    }

    void keepBytesInTrash(byte[] data, String name, String restoreTo) throws IOException {
        File d = newTrashDir();
        File holder = new File(d, "file");
        //noinspection ResultOfMethodCallIgnored
        holder.mkdirs();
        Files.write(new File(holder, name).toPath(), data);
        writeTrashInfo(d, "file", name + " (before editing)", null, 0, restoreTo);
    }

    /** The saved copy of a deleted phone file. */
    static File trashFile(TrashItem it) {
        File[] f = new File(it.dir, "file").listFiles(File::isFile);
        return f != null && f.length > 0 ? f[0] : null;
    }

    /** Puts an item back. A page goes back into its document (or a new one if that is gone). Returns the document. */
    File restore(TrashItem it) throws IOException {
        if (it.file) {
            File src = trashFile(it);
            if (src == null || it.origin == null) throw new IOException("The saved copy is missing");
            File dest = new File(it.origin);
            //noinspection ResultOfMethodCallIgnored
            dest.getParentFile().mkdirs();
            if (dest.exists()) {
                String n = dest.getName();
                int dot = n.lastIndexOf('.');
                String base = dot > 0 ? n.substring(0, dot) : n;
                String ext = dot > 0 ? n.substring(dot) : "";
                dest = new File(dest.getParentFile(), base + " (restored)" + ext);
            }
            Files.copy(src.toPath(), dest.toPath());
            deleteRecursive(it.dir);
            return dest;
        }
        synchronized (Indexer.LOCK) {
            File content = trashContent(it);
            if (!it.page) {
                File dest = new File(root, it.dir.getName());
                while (dest.exists()) dest = new File(root, dest.getName() + "1");
                Files.move(content.toPath(), dest.toPath());
                deleteRecursive(it.dir);
                return dest;
            }
            File dest = it.from != null ? new File(root, it.from) : null;
            if (dest == null || !dest.isDirectory()) {
                dest = createDoc(it.title + " (restored page)");
            }
            File[] p = pages(content);
            List<File> moved = new ArrayList<>();
            for (File f : p) moved.add(movePageLocked(f, dest));
            List<File> order = new ArrayList<>(Arrays.asList(pages(dest)));
            order.removeAll(moved);
            int at = Math.min(order.size(), Math.max(0, it.position));
            order.addAll(at, moved);
            for (File f : order) movePageLocked(f, dest);
            deleteRecursive(it.dir);
            return dest;
        }
    }

    void deleteForever(TrashItem it) {
        synchronized (Indexer.LOCK) {
            deleteRecursive(it.dir);
        }
    }

    /** Deletes recycle-bin items older than TRASH_DAYS. */
    void purgeTrash() {
        long limit = System.currentTimeMillis() - TRASH_DAYS * 24L * 3600_000L;
        for (TrashItem it : listTrash()) if (it.deletedAt < limit) deleteForever(it);
    }

    int trashCount() {
        File[] dirs = trash.listFiles(File::isDirectory);
        return dirs == null ? 0 : dirs.length;
    }

    /** Newest documents first. */
    List<File> listDocs() {
        List<File> out = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) out.addAll(Arrays.asList(dirs));
        Collections.sort(out, (a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    File createDoc(String title) {
        long id = System.currentTimeMillis();
        File dir;
        do {
            dir = new File(root, String.format(Locale.US, "%013d", id++));
        } while (dir.exists());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        setTitle(dir, title);
        return dir;
    }

    String getTitle(File dir) {
        try {
            List<String> lines = Files.readAllLines(new File(dir, "title.txt").toPath(), StandardCharsets.UTF_8);
            if (!lines.isEmpty() && !lines.get(0).trim().isEmpty()) return lines.get(0).trim();
        } catch (IOException ignored) {
            // fall through to default
        }
        return "Untitled";
    }

    void setTitle(File dir, String title) {
        try {
            Files.write(new File(dir, "title.txt").toPath(), title.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // title is cosmetic; keep the document usable
        }
    }

    // ------------------------------------------------------------------ pages

    File[] pages(File dir) {
        File[] p = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (p == null) return new File[0];
        Arrays.sort(p, Comparator.comparing(File::getName));
        return p;
    }

    /** A fresh page file name that sorts after every existing page. */
    File newPage(File dir) {
        return new File(dir, String.format(Locale.US, "p%018d.jpg", SEQ.incrementAndGet()));
    }

    private static String base(File jpg) {
        String n = jpg.getName();
        return n.endsWith(".jpg") ? n.substring(0, n.length() - 4) : n;
    }

    static File srcFile(File jpg) {
        return new File(jpg.getParentFile(), base(jpg) + ".src");
    }

    static File metaFile(File jpg) {
        return new File(jpg.getParentFile(), base(jpg) + ".json");
    }

    void deletePage(File jpg) {
        synchronized (Indexer.LOCK) {
            deletePageLocked(jpg);
        }
    }

    private void deletePageLocked(File jpg) {
        //noinspection ResultOfMethodCallIgnored
        jpg.delete();
        //noinspection ResultOfMethodCallIgnored
        srcFile(jpg).delete();
        //noinspection ResultOfMethodCallIgnored
        metaFile(jpg).delete();
    }

    /** Copies a page (with original and settings) to the end of another document. */
    File copyPage(File jpg, File destDir) throws IOException {
        synchronized (Indexer.LOCK) {
            return copyPageLocked(jpg, destDir);
        }
    }

    private File copyPageLocked(File jpg, File destDir) throws IOException {
        File dst = newPage(destDir);
        Files.copy(jpg.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (srcFile(jpg).exists()) {
            Files.copy(srcFile(jpg).toPath(), srcFile(dst).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (metaFile(jpg).exists()) {
            Files.copy(metaFile(jpg).toPath(), metaFile(dst).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return dst;
    }

    /** Moves a page to the end of a document (the same one, to change its position). */
    File movePage(File jpg, File destDir) throws IOException {
        synchronized (Indexer.LOCK) {
            return movePageLocked(jpg, destDir);
        }
    }

    private File movePageLocked(File jpg, File destDir) throws IOException {
        File dst = newPage(destDir);
        if (srcFile(jpg).exists()) {
            Files.move(srcFile(jpg).toPath(), srcFile(dst).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (metaFile(jpg).exists()) {
            Files.move(metaFile(jpg).toPath(), metaFile(dst).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(jpg.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return dst;
    }

    /** Renames every page so the files sort in the given order. */
    void reorder(List<File> ordered) throws IOException {
        synchronized (Indexer.LOCK) {
            for (File f : ordered) movePageLocked(f, f.getParentFile());
        }
    }

    void deleteDoc(File dir) {
        synchronized (Indexer.LOCK) {
            deleteRecursive(dir);
        }
    }

    static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
