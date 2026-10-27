package com.anamol.pdfscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Deleted documents and pages, kept for 30 days. Restore them or delete them for good. */
public class TrashActivity extends Activity {
    private DocStore store;
    private final List<DocStore.TrashItem> items = new ArrayList<>();
    private final List<Bitmap> thumbs = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView empty;
    private Button emptyBtn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DocStore(this);
        LinearLayout root = Ui.bare(this);
        root.addView(Ui.topBar(this, this::finish, Ui.title(this, "Recycle bin")));
        TextView note = Ui.meta(this, "Deleted scans, pages and phone files stay here for " + DocStore.TRASH_DAYS
                + " days, then they are removed for good. Tap one to restore it.");
        note.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
        root.addView(note);
        root.addView(Ui.hairline(this, 0));

        ListView list = new ListView(this);
        list.setDivider(Ui.listDivider(this, 80, 20));
        list.setDividerHeight(1);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int i) {
                return items.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convertView, ViewGroup parent) {
                return row(items.get(i), thumbs.get(i));
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> itemMenu(items.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = Ui.body(this, "The recycle bin is empty.", true);
        empty.setGravity(Gravity.CENTER);
        root.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setEmptyView(empty);

        emptyBtn = Ui.button(this, "Empty recycle bin", false);
        emptyBtn.setOnClickListener(v -> Ui.confirm(this,
                "Delete everything in the recycle bin for good? This can't be undone.",
                () -> Ui.task(this, () -> {
                    for (DocStore.TrashItem it : store.listTrash()) store.deleteForever(it);
                }, this::reload, this::reload)));
        root.addView(Ui.bottomBar(this, null, emptyBtn));
        setContentView(root);
        reload();
    }

    private void reload() {
        final List<DocStore.TrashItem> found = new ArrayList<>();
        final List<Bitmap> pics = new ArrayList<>();
        Ui.task(this, () -> {
            store.purgeTrash();
            for (DocStore.TrashItem it : store.listTrash()) {
                found.add(it);
                File[] p = store.pages(DocStore.trashContent(it));
                Bitmap b = null;
                if (p.length > 0) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 8;
                    b = BitmapFactory.decodeFile(p[0].getAbsolutePath(), o);
                }
                pics.add(b);
            }
        }, () -> {
            items.clear();
            items.addAll(found);
            thumbs.clear();
            thumbs.addAll(pics);
            adapter.notifyDataSetChanged();
            emptyBtn.setEnabled(!items.isEmpty());
            emptyBtn.setAlpha(items.isEmpty() ? 0.4f : 1f);
        }, null);
    }

    private View row(DocStore.TrashItem it, Bitmap thumb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 72));
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE, 4));
        if (thumb != null) img.setImageBitmap(thumb);
        Ui.round(img, 4);
        row.addView(img, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 61)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.label(this, label(it));
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(t);
        long left = DocStore.TRASH_DAYS - (System.currentTimeMillis() - it.deletedAt) / (24L * 3600_000L);
        String kind = it.file ? "From " + MainActivity.folderOf(it.origin == null ? "" : it.origin)
                : it.page ? "Page" : "Scan";
        TextView m = Ui.meta(this, kind + " · deleted " + Ui.when(it.deletedAt)
                + " · " + Math.max(0, left) + (left == 1 ? " day left" : " days left"));
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(m, Ui.full(this, 4));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(this, 14));
        row.addView(text, tl);
        return row;
    }

    private static String label(DocStore.TrashItem it) {
        if (it.file) return it.title;
        return it.page ? "Page " + (it.position + 1) + " of " + Ui.displayTitle(it.title) : Ui.displayTitle(it.title);
    }

    private void itemMenu(final DocStore.TrashItem it) {
        new AlertDialog.Builder(this)
                .setTitle(label(it))
                .setItems(new String[]{"Restore", "Delete for good"}, (d, which) -> {
                    if (which == 0) {
                        Ui.task(this, () -> {
                            File back = store.restore(it);
                            if (it.file) PhoneFiles.restored(this, back);
                        }, () -> {
                            Ui.toast(this, it.file ? "Put back on the phone" : it.page ? "Page put back" : "Document restored");
                            Indexer.kick(this);
                            reload();
                        }, this::reload);
                    } else {
                        Ui.confirm(this, "Delete this for good? This can't be undone.",
                                () -> Ui.task(this, () -> store.deleteForever(it), this::reload, this::reload));
                    }
                })
                .show();
    }
}
