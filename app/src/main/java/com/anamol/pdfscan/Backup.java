package com.anamol.pdfscan;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The whole library as one zip file: every document (pages, originals, settings, text,
 * transcript, folder and pin) and the saved signatures. Restoring adds the documents to
 * whatever is already in the app; nothing is overwritten. Pure Java so it can be tested.
 */
final class Backup {
    static final String MANIFEST = "pdfscan-backup.json";
    private static final Pattern DOC_ID = Pattern.compile("[0-9A-Za-z_-]{1,40}");
    private static final Pattern FILE_NAME = Pattern.compile("[0-9A-Za-z_.-]{1,80}");

    private Backup() {
    }

    /** Writes the library to out. Returns the number of documents. */
    static int export(File docsRoot, File signatures, OutputStream out) throws IOException {
        int docs = 0;
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.setLevel(1);  // pages are JPEGs already; don't spend time compressing them
            File[] dirs = docsRoot.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File d : dirs) {
                    File[] files = d.listFiles(File::isFile);
                    if (files == null) continue;
                    docs++;
                    for (File f : files) put(z, "docs/" + d.getName() + "/" + f.getName(), f);
                }
            }
            if (signatures != null && signatures.isFile()) put(z, "signatures.json", signatures);
            z.putNextEntry(new ZipEntry(MANIFEST));
            String m = "{\"app\":\"PDF Scan\",\"version\":1,\"documents\":" + docs
                    + ",\"created\":" + System.currentTimeMillis() + "}";
            z.write(m.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return docs;
    }

    private static void put(ZipOutputStream z, String name, File f) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
        }
        z.closeEntry();
    }

    /**
     * Adds the documents in a backup to the library. A document whose folder name is taken gets a
     * new one, so restoring the same backup twice gives copies rather than mixing pages.
     * Signatures are restored only if none are saved yet. Returns the number of documents added.
     */
    static int restore(InputStream in, File docsRoot, File signatures) throws IOException {
        Map<String, File> mapped = new HashMap<>();
        boolean sawManifest = false;
        File staging = new File(docsRoot.getParentFile(), "restore-" + System.currentTimeMillis());
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.equals(MANIFEST)) {
                    sawManifest = true;
                    continue;
                }
                if (name.equals("signatures.json")) {
                    if (signatures != null && !signatures.exists()) copy(z, signatures);
                    continue;
                }
                String[] parts = name.split("/");
                // only docs/<id>/<file>, with safe names: nothing can be written outside the library
                if (parts.length != 3 || !parts[0].equals("docs") || !DOC_ID.matcher(parts[1]).matches()
                        || !FILE_NAME.matcher(parts[2]).matches() || parts[2].startsWith(".")) {
                    continue;
                }
                File dir = mapped.get(parts[1]);
                if (dir == null) {
                    dir = new File(staging, parts[1]);
                    if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Cannot unpack the backup");
                    mapped.put(parts[1], dir);
                }
                copy(z, new File(dir, parts[2]));
            }
        }
        if (!sawManifest) {
            deleteAll(staging);
            throw new IOException("This file isn't a PDF Scan backup");
        }
        int added = 0;
        File[] dirs = staging.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                File dest = new File(docsRoot, d.getName());
                int k = 1;
                while (dest.exists()) dest = new File(docsRoot, d.getName() + "-" + (k++));
                if (!d.renameTo(dest)) throw new IOException("Cannot add a restored document");
                added++;
            }
        }
        deleteAll(staging);
        return added;
    }

    private static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void copy(InputStream in, File to) throws IOException {
        try (OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
