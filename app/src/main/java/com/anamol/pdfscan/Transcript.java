package com.anamol.pdfscan;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Each document keeps a plain-text transcript (transcript.txt) of all its pages.
 * It is what library search reads, and it is exported next to the PDF.
 * transcript.sig records what the transcript was built from, so it is rebuilt only when needed.
 */
final class Transcript {
    private Transcript() {
    }

    static File file(File dir) {
        return new File(dir, "transcript.txt");
    }

    private static File sigFile(File dir) {
        return new File(dir, "transcript.sig");
    }

    /** Fingerprint of the title and every page image and its settings. Cheap: file metadata only. */
    static String signature(DocStore store, File dir) {
        StringBuilder sb = new StringBuilder();
        File title = new File(dir, "title.txt");
        sb.append(title.lastModified()).append(':').append(title.length()).append('|');
        for (File p : store.pages(dir)) {
            File meta = DocStore.metaFile(p);
            sb.append(p.getName()).append(':').append(p.length()).append(':').append(p.lastModified())
                    .append(':').append(meta.lastModified()).append(':').append(meta.length()).append('|');
        }
        return sb.toString();
    }

    static boolean isCurrent(DocStore store, File dir) {
        File sig = sigFile(dir);
        if (!file(dir).exists() || !sig.exists()) return false;
        try {
            String saved = new String(Files.readAllBytes(sig.toPath()), StandardCharsets.UTF_8);
            return saved.equals(signature(store, dir));
        } catch (IOException e) {
            return false;
        }
    }

    static String build(DocStore store, File dir) {
        List<String> texts = new ArrayList<>();
        for (File p : store.pages(dir)) {
            PageMeta m = PageMeta.read(p);
            texts.add(m.hasText() ? m.text : null);
        }
        return SearchCore.build(store.getTitle(dir), texts);
    }

    /** Rebuilds transcript.txt if anything changed. Returns the current transcript text. */
    static String update(DocStore store, File dir) throws IOException {
        synchronized (Indexer.LOCK) {
            if (!dir.isDirectory()) return "";
            if (isCurrent(store, dir)) return read(dir);
            String sig = signature(store, dir);
            String text = build(store, dir);
            Files.write(file(dir).toPath(), text.getBytes(StandardCharsets.UTF_8));
            Files.write(sigFile(dir).toPath(), sig.getBytes(StandardCharsets.UTF_8));
            return text;
        }
    }

    static String read(File dir) {
        try {
            return new String(Files.readAllBytes(file(dir).toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
