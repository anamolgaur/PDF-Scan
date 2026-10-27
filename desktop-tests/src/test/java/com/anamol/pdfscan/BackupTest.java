package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

/** Backup round trip, restoring twice, and refusing unsafe or foreign zip files. */
public class BackupTest {
    private static File tempDir() throws IOException {
        return Files.createTempDirectory("pdfscan-backup").toFile();
    }

    private static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void roundTripAddsDocumentsWithoutOverwriting() throws IOException {
        File a = tempDir();
        File docs = new File(a, "docs");
        write(new File(docs, "0000000000001/title.txt"), "Lab record");
        write(new File(docs, "0000000000001/p1.jpg"), "jpeg bytes");
        write(new File(docs, "0000000000002/title.txt"), "Notes");
        File sig = new File(a, "signatures.json");
        write(sig, "[]");
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        assertEquals(2, Backup.export(docs, sig, zip));

        File b = tempDir();
        File docs2 = new File(b, "docs");
        docs2.mkdirs();
        File sig2 = new File(b, "signatures.json");
        assertEquals(2, Backup.restore(new ByteArrayInputStream(zip.toByteArray()), docs2, sig2));
        assertEquals("Lab record", new String(Files.readAllBytes(new File(docs2, "0000000000001/title.txt").toPath()),
                StandardCharsets.UTF_8));
        assertTrue(sig2.exists());
        // the same backup again: copies alongside, nothing replaced
        assertEquals(2, Backup.restore(new ByteArrayInputStream(zip.toByteArray()), docs2, sig2));
        assertEquals(4, docs2.listFiles(File::isDirectory).length);
        assertEquals(0, b.listFiles(f -> f.getName().startsWith("restore-")).length);
    }

    @Test
    public void ignoresPathsOutsideTheLibrary() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            for (String n : new String[]{"docs/../../evil.txt", "docs/1/../../evil.txt", "/etc/evil", "docs/1/2/3.jpg",
                    Backup.MANIFEST}) {
                z.putNextEntry(new ZipEntry(n));
                z.write(1);
                z.closeEntry();
            }
        }
        File b = tempDir();
        File docs = new File(b, "docs");
        docs.mkdirs();
        assertEquals(0, Backup.restore(new ByteArrayInputStream(bo.toByteArray()), docs, null));
        assertFalse(new File(b, "evil.txt").exists());
        assertFalse(new File(b.getParentFile(), "evil.txt").exists());
    }

    @Test
    public void refusesOtherZipFiles() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("docs/1/title.txt"));
            z.write(65);
            z.closeEntry();
        }
        File docs = new File(tempDir(), "docs");
        docs.mkdirs();
        try {
            Backup.restore(new ByteArrayInputStream(bo.toByteArray()), docs, null);
            fail("should refuse");
        } catch (IOException expected) {
            assertEquals(0, docs.listFiles().length);
        }
    }
}
