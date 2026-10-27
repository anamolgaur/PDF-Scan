package com.anamol.pdfscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Editing an existing PDF with the scanner's tools. The PDF's pages become an editable document
 * (crop, filters, adjust, clean up, mark-up, signatures, rotate, reorder...), its own text is
 * kept for search, and the document remembers where it came from, so the result can be saved
 * back over the original PDF (whose previous version goes to the recycle bin) or as a new file.
 */
final class PdfEditing {
    private PdfEditing() {
    }

    /**
     * Opens a PDF for editing. originPath is the file on the phone when known (so it can be
     * replaced later); otherwise originUri, for a PDF opened from another app.
     */
    static void open(final Activity a, final Uri source, final String name, final String originPath,
                     final Uri originUri, final int startPage) {
        final DocStore store = new DocStore(a);
        String title = name;
        int dot = title.lastIndexOf('.');
        if (dot > 0) title = title.substring(0, dot);
        final File dir = store.createDoc(Ui.displayTitle(title));
        final TextView msg = Ui.body(a, "Preparing the pages for editing…", false);
        int pad = Ui.dp(a, 24);
        msg.setPadding(pad, pad, pad, pad);
        final AlertDialog dialog = new AlertDialog.Builder(a).setTitle("Edit " + name).setView(msg)
                .setCancelable(false).show();
        final List<Uri> uris = new ArrayList<>();
        uris.add(source);
        Ui.task(a, () -> {
            ImageProcessor.importInto(a.getContentResolver(), uris, store, dir, true);
            store.setOrigin(dir, originPath, originUri != null ? originUri.toString() : null, name);
        }, () -> {
            dialog.dismiss();
            Indexer.kick(a);
            // the document's page grid sits behind the editor, so Back leads to Export / Save
            a.startActivities(new Intent[]{ViewerActivity.intent(a, dir),
                    PagePreviewActivity.intent(a, dir, Math.max(0, startPage))});
        }, () -> {
            dialog.dismiss();
            store.deleteDoc(dir);
        });
    }

    /** Where an edited PDF came from, for "Save over the original". */
    static String originLabel(DocStore store, File dir) {
        String name = store.getOriginName(dir);
        return name == null ? null : name;
    }

    /**
     * Replaces the original PDF with the edited document (a searchable PDF). The previous version
     * is kept in the recycle bin for 30 days. Call off the main thread.
     */
    static void saveOverOriginal(Context c, DocStore store, File dir, byte[] pdf) throws IOException {
        String path = store.getOriginPath(dir);
        String uri = store.getOriginUri(dir);
        String name = store.getOriginName(dir);
        if (path != null && new File(path).isFile()) {
            File f = new File(path);
            store.keepCopyInTrash(f, f.getAbsolutePath());
            File tmp = new File(f.getParentFile(), "." + f.getName() + ".pdfscan-tmp");
            Files.write(tmp.toPath(), pdf);
            if (!tmp.renameTo(f)) {
                // some storage won't rename over a file: write in place instead
                Files.write(f.toPath(), pdf);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            PhoneFiles.restored(c, f);
            MediaScannerConnection.scanFile(c.getApplicationContext(), new String[]{f.getAbsolutePath()}, null, null);
            return;
        }
        if (uri != null) {
            Uri u = Uri.parse(uri);
            byte[] old = read(c, u);
            if (old != null) {
                File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                store.keepBytesInTrash(old, name != null ? name : "document.pdf",
                        new File(downloads, name != null ? name : "document.pdf").getAbsolutePath());
            }
            try (OutputStream out = c.getContentResolver().openOutputStream(u, "wt")) {
                if (out == null) throw new IOException("The original can't be changed; export a copy instead");
                out.write(pdf);
            } catch (SecurityException e) {
                throw new IOException("The app that shared this PDF doesn't allow changing it; export a copy instead");
            }
            return;
        }
        throw new IOException("The original PDF is no longer there; export a copy instead");
    }

    private static byte[] read(Context c, Uri u) {
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
