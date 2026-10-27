package com.anamol.pdfscan;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Receives PDFs and pictures shared from other apps (WhatsApp, Gmail, the gallery, a file
 * manager) or opened with PDF Scan, and adds them to Scans as a new document.
 */
public class ShareInActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.screen(this);
        root.setGravity(Gravity.CENTER);
        TextView t = Ui.heading(this, "Adding to Scans");
        TextView m = Ui.meta(this, "Copying the shared files into PDF Scan…");
        root.addView(t);
        root.addView(m, Ui.full(this, 8));
        setContentView(root);
        if (state != null) return;

        final List<Uri> uris = urisOf(getIntent());
        if (uris.isEmpty()) {
            Ui.toast(this, "Nothing to add");
            finish();
            return;
        }
        final DocStore store = new DocStore(this);
        String name = displayName(uris.get(0));
        String title;
        if (uris.size() == 1 && name != null) {
            int dot = name.lastIndexOf('.');
            title = dot > 0 ? name.substring(0, dot) : name;
        } else {
            title = "Shared " + new SimpleDateFormat("d MMM, HH:mm", Locale.US).format(new Date());
        }
        final File dir = store.createDoc(title);
        Ui.task(this, () -> ImageProcessor.importInto(getContentResolver(), uris, store, dir), () -> {
            Indexer.kick(this);
            startActivity(ViewerActivity.intent(this, dir).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        }, () -> {
            store.deleteDoc(dir);
            Ui.toast(this, "These files couldn't be added. PDF Scan takes PDFs and pictures.");
            finish();
        });
    }

    private static List<Uri> urisOf(Intent in) {
        List<Uri> out = new ArrayList<>();
        if (in == null) return out;
        String a = in.getAction();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) out.addAll(list);
        } else if (Intent.ACTION_VIEW.equals(a) && in.getData() != null) {
            out.add(in.getData());
        }
        if (out.isEmpty() && in.getClipData() != null) {
            for (int i = 0; i < in.getClipData().getItemCount(); i++) {
                Uri u = in.getClipData().getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        return out;
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall back to a dated title
        }
        String last = u.getLastPathSegment();
        return last != null && last.contains(".") ? last : null;
    }
}
