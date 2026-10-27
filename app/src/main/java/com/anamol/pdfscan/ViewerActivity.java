package com.anamol.pdfscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One document: pages, scanning, editing, reordering, splitting, text, transcript, export and Drive. */
public class ViewerActivity extends Activity implements StatusBus.Listener {
    private static final String EXTRA_DIR = "dir";
    private static final String EXTRA_SCAN = "scan";
    private static final String EXTRA_PAGE = "page";
    private static final int REQ_CAMERA = 1;
    private static final int REQ_EDIT = 2;
    private static final int REQ_EXPORT_PDF = 3;
    private static final int REQ_EXPORT_TXT = 4;
    private static final int REQ_ADD_FILES = 5;
    private static final int REQ_EXPORT_FOLDER = 6;
    private static final int REQ_SYSTEM_CAMERA = 7;
    private static final int REQ_PREVIEW = 8;
    private static final int REQ_SAVE_IMAGES = 9;

    private static final String[] FORMATS = {"PDF + transcript to a folder", "PDF", "Transcript (.txt)"};

    static Intent intent(Context c, File dir) {
        return new Intent(c, ViewerActivity.class).putExtra(EXTRA_DIR, dir.getAbsolutePath());
    }

    /** Opens the document scrolled to a page (0-based); -1 for the top. */
    static Intent intentAt(Context c, File dir, int pageIndex) {
        return intent(c, dir).putExtra(EXTRA_PAGE, pageIndex);
    }

    /** Opens a new, empty document and goes straight to the camera. */
    static Intent intentScan(Context c, File dir) {
        return intent(c, dir).putExtra(EXTRA_SCAN, true);
    }

    private DocStore store;
    private File dir;
    private boolean newScan;
    private final List<File> pages = new ArrayList<>();
    private boolean[] hasText = new boolean[0];
    private BaseAdapter adapter;
    private android.widget.GridView list;
    private TextView titleView;
    private TextView countView;
    private TextView statusView;
    private Uri pendingUri;
    // selecting pages: long-press a page; drag it to move it
    private final java.util.LinkedHashSet<File> selected = new java.util.LinkedHashSet<>();
    private View normalTop;
    private View selectTop;
    private View normalBottom;
    private View selectBottom;
    private TextView selectCount;
    private int dragFrom = -1;
    private int dropTarget = -1;
    private List<File> imagesToSave;
    private int pagesBeforeCamera;

    // export choices, kept while the file picker is open
    private int exportFormat;
    private String exportSize;
    private String exportPassword;
    private boolean exportSearchable = true;
    private boolean exportDrive;

    private final LruCache<String, Bitmap> thumbs =
            new LruCache<String, Bitmap>(PagePreviewActivity.cacheBytes() / 2) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DocStore(this);
        dir = new File(getIntent().getStringExtra(EXTRA_DIR));
        newScan = getIntent().getBooleanExtra(EXTRA_SCAN, false);
        if (!dir.isDirectory()) {
            finish();
            return;
        }
        exportSize = Prefs.exportSize(this);
        if (state != null) {
            String u = state.getString("pendingUri");
            pendingUri = u != null ? Uri.parse(u) : null;
            pagesBeforeCamera = state.getInt("pagesBeforeCamera");
            exportFormat = state.getInt("exportFormat");
            exportSize = state.getString("exportSize", exportSize);
            exportPassword = state.getString("exportPassword");
            exportSearchable = state.getBoolean("exportSearchable", true);
            exportDrive = state.getBoolean("exportDrive");
        }

        LinearLayout root = Ui.bare(this);

        titleView = Ui.title(this, "");
        titleView.setOnClickListener(v -> rename());
        titleView.setMinHeight(Ui.dp(this, 48));
        titleView.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageButton transcript = Ui.iconButton(this, R.drawable.ic_text, "Transcript");
        android.widget.ImageButton more = Ui.iconButton(this, R.drawable.ic_more, "More");
        transcript.setOnClickListener(v -> showTranscript());
        more.setOnClickListener(v -> showMoreMenu());
        normalTop = Ui.topBar(this, this::finish, titleView, transcript, more);
        root.addView(normalTop);
        selectCount = Ui.title(this, "");
        selectCount.setMinHeight(Ui.dp(this, 48));
        selectCount.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button all = Ui.chip(this, "Select all");
        all.setOnClickListener(v -> {
            if (selected.size() == pages.size()) selected.clear();
            else selected.addAll(pages);
            selectionChanged();
        });
        selectTop = Ui.topBar(this, this::clearSelection, selectCount, all);
        selectTop.setVisibility(View.GONE);
        root.addView(selectTop);

        countView = Ui.meta(this, "");
        countView.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        root.addView(countView);

        statusView = Ui.meta(this, "");
        statusView.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), 0);
        statusView.setVisibility(View.GONE);
        root.addView(statusView);

        list = new android.widget.GridView(this);
        list.setNumColumns(2);
        list.setHorizontalSpacing(Ui.dp(this, 14));
        list.setVerticalSpacing(Ui.dp(this, 18));
        list.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
        list.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 20));
        list.setClipToPadding(false);
        list.setSelector(android.R.color.transparent);
        TextView empty = Ui.body(this, "No pages yet.\nTap Add page to scan one.", true);
        empty.setGravity(android.view.Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), Ui.dp(this, 40));
        list.setEmptyView(empty);

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return pages.size();
            }

            @Override
            public Object getItem(int position) {
                return pages.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Context c = ViewerActivity.this;
                File f = pages.get(position);
                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                // every cell has the same page shape, so the grid never jumps while pictures load
                ImageView img = new ImageView(c) {
                    @Override
                    protected void onMeasure(int w, int h) {
                        int width = MeasureSpec.getSize(w);
                        setMeasuredDimension(width, Math.round(width * 1.414f));
                    }
                };
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                img.setBackgroundColor(0xFFFAF8F2);
                Ui.round(img, 6);
                bindThumb(img, f);
                img.setForeground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Ui.RIPPLE_ON_ACCENT), null,
                        new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
                cell.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                boolean isSelected = selected.contains(f);
                TextView n = Ui.meta(c, (isSelected ? "\u2713 " : "") + (position + 1));
                n.setGravity(android.view.Gravity.CENTER);
                if (isSelected) n.setTextColor(Ui.INK);
                cell.addView(n, Ui.full(c, 8));
                int pad = Ui.dp(c, 3);
                cell.setPadding(pad, pad, pad, pad);
                if (isSelected) {
                    cell.setBackground(Ui.box(c, 0x00000000, Ui.INK, 8));
                    ((android.graphics.drawable.GradientDrawable) cell.getBackground()).setStroke(Ui.dp(c, 3), Ui.INK);
                } else if (position == dropTarget && dragFrom >= 0 && dropTarget != dragFrom) {
                    cell.setBackground(Ui.box(c, Ui.SURFACE, Ui.MUTED, 8));  // where the page will land
                }
                if (position == dragFrom) cell.setAlpha(0.35f);
                return cell;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((AdapterView<?> parent, View view, int pos, long id) -> {
            if (!selected.isEmpty()) toggle(pos);
            else openPreview(pos);
        });
        // long-press: select the page and pick it up; drag to move it, or let go to keep it selected
        list.setOnItemLongClickListener((AdapterView<?> parent, View view, int pos, long id) -> {
            if (pos < 0 || pos >= pages.size()) return false;
            selected.add(pages.get(pos));
            selectionChanged();
            dragFrom = pos;
            dropTarget = pos;
            view.startDragAndDrop(android.content.ClipData.newPlainText("page", String.valueOf(pos)),
                    new View.DragShadowBuilder(view), null, 0);
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            return true;
        });
        list.setOnDragListener((v, e) -> {
            switch (e.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return dragFrom >= 0;
                case android.view.DragEvent.ACTION_DRAG_LOCATION: {
                    int p = list.pointToPosition((int) e.getX(), (int) e.getY());
                    if (p != AdapterView.INVALID_POSITION && p != dropTarget) {
                        dropTarget = p;
                        adapter.notifyDataSetChanged();
                    }
                    int edge = Ui.dp(this, 72);
                    if (e.getY() < edge) list.smoothScrollBy(-Ui.dp(this, 28), 60);
                    else if (e.getY() > list.getHeight() - edge) list.smoothScrollBy(Ui.dp(this, 28), 60);
                    return true;
                }
                case android.view.DragEvent.ACTION_DROP: {
                    int p = list.pointToPosition((int) e.getX(), (int) e.getY());
                    if (p == AdapterView.INVALID_POSITION) p = dropTarget;
                    int from = dragFrom;
                    dragFrom = -1;
                    dropTarget = -1;
                    if (from >= 0 && p >= 0 && p != from) {
                        selected.clear();
                        selectionChanged();
                        movePage(from, p);
                    } else {
                        adapter.notifyDataSetChanged();
                    }
                    return true;
                }
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    if (dragFrom >= 0) {
                        dragFrom = -1;
                        dropTarget = -1;
                        adapter.notifyDataSetChanged();
                    }
                    return true;
                default:
                    return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button export = Ui.button(this, "Export", R.drawable.ic_export, false);
        Button scan = Ui.button(this, "Add page", R.drawable.ic_plus, true);
        export.setOnClickListener(v -> showExportSheet());
        scan.setOnClickListener(v -> startCamera());
        normalBottom = Ui.bottomBar(this, export, scan);
        root.addView(normalBottom);
        selectBottom = Ui.toolbar(this,
                Ui.tool(this, R.drawable.ic_rotate, "Rotate", v -> rotateSelected()),
                Ui.tool(this, R.drawable.ic_filter, "Filter", v -> filterSelected(new ArrayList<>(selected))),
                Ui.tool(this, R.drawable.ic_move, "Move", v -> moveSelected()),
                Ui.tool(this, R.drawable.ic_image, "Save images", v -> saveImages(new ArrayList<>(selected))),
                Ui.tool(this, R.drawable.ic_trash, "Delete", v -> deleteSelected()),
                Ui.tool(this, R.drawable.ic_more, "More", v -> selectionMore()));
        selectBottom.setVisibility(View.GONE);
        root.addView(selectBottom);

        setContentView(root);
        reload();

        int startPage = getIntent().getIntExtra(EXTRA_PAGE, -1);
        if (state == null && startPage > 0 && startPage < pages.size()) list.setSelection(startPage);
        if (state == null && newScan) startCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatusBus.add(this);
        onStatus(StatusBus.current());
        Indexer.kick(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!selected.isEmpty()) {
            clearSelection();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ selecting pages

    private void toggle(int pos) {
        if (pos < 0 || pos >= pages.size()) return;
        File f = pages.get(pos);
        if (!selected.remove(f)) selected.add(f);
        selectionChanged();
    }

    private void clearSelection() {
        selected.clear();
        selectionChanged();
    }

    private void selectionChanged() {
        selected.retainAll(pages);
        boolean on = !selected.isEmpty();
        normalTop.setVisibility(on ? View.GONE : View.VISIBLE);
        selectTop.setVisibility(on ? View.VISIBLE : View.GONE);
        normalBottom.setVisibility(on ? View.GONE : View.VISIBLE);
        selectBottom.setVisibility(on ? View.VISIBLE : View.GONE);
        int n = selected.size();
        selectCount.setText(n + (n == 1 ? " page selected" : " pages selected"));
        adapter.notifyDataSetChanged();
    }

    /** Selected pages in document order. */
    private List<File> selectedInOrder() {
        List<File> out = new ArrayList<>();
        for (File f : pages) if (selected.contains(f)) out.add(f);
        return out;
    }

    private void rotateSelected() {
        final List<File> sel = selectedInOrder();
        Ui.task(this, () -> {
            for (File page : sel) {
                PageMeta m = PageMeta.read(page);
                m.rotateCw();
                ImageProcessor.renderPage(page, m);
            }
        }, this::changed, this::changed);
    }

    /** One filter for several pages (or every page): the rest of each page's edits stay. */
    private void filterSelected(final List<File> targets) {
        if (targets.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(targets.size() == pages.size() ? "Filter for every page" : "Filter for " + targets.size()
                        + (targets.size() == 1 ? " page" : " pages"))
                .setItems(ImageOps.FILTER_LABELS, (d, which) -> {
                    final String filter = ImageOps.FILTERS[which];
                    final TextView msg = Ui.body(this, "Applying\u2026", false);
                    int pad = Ui.dp(this, 24);
                    msg.setPadding(pad, pad, pad, pad);
                    final AlertDialog progress = new AlertDialog.Builder(this).setView(msg).setCancelable(false).show();
                    final List<File> list = new ArrayList<>();
                    for (File f : pages) if (targets.contains(f)) list.add(f);
                    new Thread(() -> {
                        for (int i = 0; i < list.size(); i++) {
                            final int k = i;
                            runOnUiThread(() -> msg.setText("Page " + (k + 1) + " of " + list.size() + "\u2026"));
                            try {
                                PageMeta m = PageMeta.read(list.get(i));
                                m.filter = filter;
                                ImageProcessor.renderPage(list.get(i), m);
                            } catch (Exception | OutOfMemoryError e) {
                                Diag.log("Filter on page", e);
                            }
                        }
                        runOnUiThread(() -> {
                            progress.dismiss();
                            changed();
                        });
                    }).start();
                })
                .show();
    }

    private void moveSelected() {
        final List<File> sel = selectedInOrder();
        final String[] items = {"To the start", "To the end", "After page\u2026", "To another document\u2026"};
        new AlertDialog.Builder(this)
                .setTitle("Move " + sel.size() + (sel.size() == 1 ? " page" : " pages"))
                .setItems(items, (d, which) -> {
                    if (which == 0) reorderWith(sel, 0);
                    else if (which == 1) reorderWith(sel, Integer.MAX_VALUE);
                    else if (which == 2) {
                        final EditText input = Ui.input(this, "1 \u2013 " + pages.size(), false);
                        input.setInputType(InputType.TYPE_CLASS_NUMBER);
                        new AlertDialog.Builder(this).setTitle("Put them after page").setView(padded(input))
                                .setPositiveButton("Move", (d2, w) -> {
                                    try {
                                        int after = Integer.parseInt(input.getText().toString().trim());
                                        reorderWith(sel, after);
                                    } catch (NumberFormatException e) {
                                        Ui.toast(this, "Enter a page number");
                                    }
                                })
                                .setNegativeButton("Cancel", null).show();
                    } else {
                        moveToOtherDoc(sel);
                    }
                })
                .show();
    }

    /** Places the given pages as a block after `after` other pages (counting the unselected ones). */
    private void reorderWith(List<File> block, int after) {
        final List<File> rest = new ArrayList<>(pages);
        rest.removeAll(block);
        int at = Math.max(0, Math.min(rest.size(), after));
        if (after != Integer.MAX_VALUE && after > 0) {
            // "after page N" counts the page numbers as they are now
            File anchor = pages.get(Math.min(pages.size(), after) - 1);
            int idx = rest.indexOf(anchor);
            at = idx >= 0 ? idx + 1 : at;
        }
        final List<File> order = new ArrayList<>(rest);
        order.addAll(at, block);
        clearSelection();
        Ui.task(this, () -> store.reorder(order), this::changed, this::changed);
    }

    private void moveToOtherDoc(final List<File> sel) {
        final List<File> docs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        names.add("New document");
        for (File d : store.listDocs()) {
            if (d.equals(dir)) continue;
            docs.add(d);
            names.add(Ui.displayTitle(store.getTitle(d)));
        }
        new AlertDialog.Builder(this)
                .setTitle("Move to")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    final File target = which == 0 ? store.createDoc(store.getTitle(dir) + " (pages)") : docs.get(which - 1);
                    clearSelection();
                    Ui.task(this, () -> {
                        for (File f : sel) store.movePage(f, target);
                    }, () -> {
                        changed();
                        Ui.toast(this, "Moved to \u201C" + Ui.displayTitle(store.getTitle(target)) + "\u201D");
                    }, this::changed);
                })
                .show();
    }

    private void deleteSelected() {
        final List<File> sel = selectedInOrder();
        Ui.confirm(this, "Delete " + sel.size() + (sel.size() == 1 ? " page" : " pages")
                + "? They stay in the recycle bin for 30 days.", () -> {
                    clearSelection();
                    Ui.task(this, () -> {
                        for (File f : sel) store.trashPage(f);
                    }, this::changed, this::changed);
                });
    }

    private void selectionMore() {
        final List<File> sel = selectedInOrder();
        final List<String> items = new ArrayList<>();
        if (sel.size() == 1) items.add("Page options\u2026");
        items.add("Copy to a new document");
        items.add("Share these pages as a PDF");
        items.add("Print these pages");
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String it = items.get(which);
                    if (it.startsWith("Page options")) {
                        int pos = pages.indexOf(sel.get(0));
                        clearSelection();
                        showPageMenu(pos);
                    } else if (it.startsWith("Copy")) {
                        final File target = store.createDoc(store.getTitle(dir) + " (copy)");
                        clearSelection();
                        Ui.task(this, () -> {
                            for (File f : sel) store.copyPage(f, target);
                        }, () -> {
                            Indexer.kick(this);
                            startActivity(intent(this, target));
                        }, null);
                    } else if (it.startsWith("Share")) {
                        ensureTextFor(sel, () -> sharePdf(sel));
                    } else {
                        ensureTextFor(sel, () -> print(sel));
                    }
                })
                .show();
    }

    private void ensureTextFor(List<File> sel, Runnable after) {
        ensureText(after);
    }

    // ------------------------------------------------------------------ print and images

    /** Prints through Android's print screen (any printer, or Save as PDF). */
    private void print(final List<File> which) {
        final List<File> snapshot = new ArrayList<>(which);
        final String title = Ui.displayTitle(store.getTitle(dir));
        final byte[][] pdf = new byte[1][];
        Ui.task(this, () -> {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            PdfExport.write(snapshot, Prefs.SIZE_HIGH, null, title, b);
            pdf[0] = b.toByteArray();
        }, () -> {
            android.print.PrintManager pm = getSystemService(android.print.PrintManager.class);
            if (pm == null) return;
            pm.print(title, new PdfPrintAdapter(title, pdf[0], snapshot.size()), null);
        }, null);
    }

    /** Saves pages as JPEG pictures into a folder the user picks. */
    private void saveImages(List<File> which) {
        if (which.isEmpty()) return;
        imagesToSave = new ArrayList<>();
        for (File f : pages) if (which.contains(f)) imagesToSave.add(f);
        MusicPlayer.holdForExternal();
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SAVE_IMAGES);
    }

    private void writeImages(final Uri tree) {
        final List<File> list = imagesToSave != null ? imagesToSave : new ArrayList<>(pages);
        imagesToSave = null;
        final String title = safeFileName(store.getTitle(dir));
        final int[] n = {0};
        Ui.task(this, () -> {
            ContentResolver cr = getContentResolver();
            Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            for (File f : list) {
                int pageNo = pages.indexOf(f) + 1;
                Uri out = DocumentsContract.createDocument(cr, folder, "image/jpeg",
                        title + " - page " + (pageNo > 0 ? pageNo : n[0] + 1) + ".jpg");
                if (out == null) throw new IOException("Cannot create files in that folder");
                writeTo(cr, out, java.nio.file.Files.readAllBytes(f.toPath()));
                n[0]++;
            }
        }, () -> {
            clearSelection();
            Ui.toast(this, "Saved " + n[0] + (n[0] == 1 ? " picture" : " pictures"));
        }, null);
    }

    @Override
    protected void onDestroy() {
        thumbLoader.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        StatusBus.remove(this);
    }

    @Override
    public void onStatus(String text) {
        statusView.setText(text != null ? text : "");
        statusView.setVisibility(text != null ? View.VISIBLE : View.GONE);
        refreshTextFlags();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingUri != null) out.putString("pendingUri", pendingUri.toString());
        out.putInt("pagesBeforeCamera", pagesBeforeCamera);
        out.putInt("exportFormat", exportFormat);
        out.putString("exportSize", exportSize);
        out.putString("exportPassword", exportPassword);
        out.putBoolean("exportSearchable", exportSearchable);
        out.putBoolean("exportDrive", exportDrive);
    }

    // ------------------------------------------------------------------ list

    private final java.util.concurrent.ExecutorService thumbLoader = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.Set<String> thumbLoading = new java.util.HashSet<>();

    /** Shows a page picture from memory at once, or loads it in the background (never on the UI thread). */
    private void bindThumb(final ImageView img, final File f) {
        final String key = f.getAbsolutePath() + ":" + f.lastModified() + ":" + f.length();
        img.setTag(key);
        Bitmap b = thumbs.get(key);
        if (b != null) {
            img.setImageBitmap(b);
            return;
        }
        synchronized (thumbLoading) {
            if (!thumbLoading.add(key)) return;
        }
        thumbLoader.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 480) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            final Bitmap loaded = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
            runOnUiThread(() -> {
                synchronized (thumbLoading) {
                    thumbLoading.remove(key);
                }
                if (loaded == null || isFinishing()) return;
                thumbs.put(key, loaded);
                // the cell may have been reused for another page meanwhile; redraw whatever shows this one
                if (key.equals(img.getTag())) img.setImageBitmap(loaded);
                else adapter.notifyDataSetChanged();
            });
        });
    }

    private void reload() {
        pages.clear();
        pages.addAll(Arrays.asList(store.pages(dir)));
        refreshTextFlags();
        updateHeader();
    }

    private void refreshTextFlags() {
        boolean[] flags = new boolean[pages.size()];
        for (int i = 0; i < pages.size(); i++) flags[i] = PageMeta.hasTextQuick(pages.get(i));
        hasText = flags;
        adapter.notifyDataSetChanged();
        updateHeader();
    }

    /** After any change to pages: refresh, and let the indexer update text and the transcript. */
    private void changed() {
        reload();
        Indexer.kick(this);
    }

    private void updateHeader() {
        titleView.setText(Ui.displayTitle(store.getTitle(dir)));
        int n = pages.size();
        int indexed = 0;
        for (boolean b : hasText) if (b) indexed++;
        String text = n == 0 ? "" : indexed == n ? " \u00B7 searchable" : indexed > 0 ? " \u00B7 " + indexed + " indexed" : "";
        String from = store.hasOrigin(dir) ? " \u00B7 editing " + store.getOriginName(dir) : "";
        countView.setText(n + (n == 1 ? " page" : " pages") + text + from
                + (n > 0 && from.isEmpty() ? " \u00B7 tap a page to preview and edit" : ""));
    }

    private void rename() {
        Ui.promptRename(this, store, dir, () -> {
            updateHeader();
            Indexer.kick(this);
        });
    }

    /** A scan that was abandoned before any page was saved leaves no empty document behind. */
    private void dropIfEmptyScan() {
        if (newScan && store.pages(dir).length == 0) {
            Ui.task(this, () -> store.deleteDoc(dir), this::finish, this::finish);
        }
    }

    // ------------------------------------------------------------------ scanning

    private void startCamera() {
        pagesBeforeCamera = store.pages(dir).length;
        startActivityForResult(CameraActivity.intent(this, dir), REQ_CAMERA);
    }

    private void openPreview(int page) {
        if (page < 0 || page >= pages.size()) return;
        startActivityForResult(PagePreviewActivity.intent(this, dir, page), REQ_PREVIEW);
    }

    private void startSystemCamera() {
        Uri uri = Capture.start(this, REQ_SYSTEM_CAMERA);
        if (uri == null) {
            dropIfEmptyScan();
            return;
        }
        pendingUri = uri;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        final Uri dataUri = data != null ? data.getData() : null;

        if (req == REQ_CAMERA) {
            if (result == CameraActivity.RESULT_USE_SYSTEM_CAMERA) {
                startSystemCamera();
                return;
            }
            changed();
            if (result == RESULT_OK && pages.size() > pagesBeforeCamera) {
                // the new photos are plain; open the first one to crop, filter and mark it up
                openPreview(pagesBeforeCamera);
            } else if (result != RESULT_OK) {
                dropIfEmptyScan();
            }

        } else if (req == REQ_SYSTEM_CAMERA) {
            final Uri uri = pendingUri;
            pendingUri = null;
            if (result != RESULT_OK || uri == null) {
                Capture.discard(this, uri);
                dropIfEmptyScan();
                return;
            }
            final File original = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".jpg");
            Ui.task(this, () -> {
                Bitmap b = ImageProcessor.decodeScaled(getContentResolver(), uri, ImageProcessor.MAX_SIDE);
                try {
                    ImageProcessor.saveJpeg(b, original);
                } finally {
                    b.recycle();
                }
                Capture.discard(this, uri);
            }, () -> startActivityForResult(PageEditorActivity.forNew(this, dir, original, false, null), REQ_EDIT),
                    this::dropIfEmptyScan);

        } else if (req == REQ_SAVE_IMAGES) {
            if (result == RESULT_OK && dataUri != null) writeImages(dataUri);
            else imagesToSave = null;

        } else if (req == REQ_PREVIEW) {
            changed();

        } else if (req == REQ_EDIT) {
            changed();
            if (result == RESULT_OK && data != null && data.getBooleanExtra(PageEditorActivity.EXTRA_NEXT, false)) {
                startCamera();
            } else if (result != RESULT_OK) {
                dropIfEmptyScan();
            }

        } else if (req == REQ_EXPORT_PDF && result == RESULT_OK && dataUri != null) {
            if (exportSearchable) ensureText(() -> exportTo(dataUri, null));
            else exportTo(dataUri, null);

        } else if (req == REQ_EXPORT_TXT && result == RESULT_OK && dataUri != null) {
            ensureText(() -> exportTo(null, dataUri));

        } else if (req == REQ_EXPORT_FOLDER && result == RESULT_OK && dataUri != null) {
            if (exportSearchable) ensureText(() -> exportToFolder(dataUri));
            else exportToFolder(dataUri);

        } else if (req == REQ_ADD_FILES && result == RESULT_OK && data != null) {
            final List<Uri> uris = MainActivity.urisFrom(data);
            if (uris.isEmpty()) return;
            Ui.task(this, () -> ImageProcessor.importInto(getContentResolver(), uris, store, dir),
                    () -> {
                        changed();
                        Ui.toast(this, "Pages added");
                    }, this::changed);
        }
    }

    // ------------------------------------------------------------------ page actions

    private void showPageMenu(final int pos) {
        if (pos < 0 || pos >= pages.size()) return;
        final File page = pages.get(pos);
        final List<String> items = new ArrayList<>(Arrays.asList(
                "Edit page", "Rotate 90°", "Move up", "Move down", "Move to position…",
                "Split document here", "Copy to new document"));
        if (pos < hasText.length && hasText[pos]) items.add("Copy page text");
        items.add("Delete page");

        new AlertDialog.Builder(this)
                .setTitle("Page " + (pos + 1))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    switch (items.get(which)) {
                        case "Edit page":
                            startActivityForResult(PageEditorActivity.forEdit(this, dir, page), REQ_EDIT);
                            break;
                        case "Rotate 90°":
                            Ui.task(this, () -> {
                                PageMeta m = PageMeta.read(page);
                                m.rotateCw();
                                ImageProcessor.renderPage(page, m);
                            }, this::changed, null);
                            break;
                        case "Move up":
                            movePage(pos, pos - 1);
                            break;
                        case "Move down":
                            movePage(pos, pos + 1);
                            break;
                        case "Move to position…":
                            askPosition(pos);
                            break;
                        case "Split document here":
                            splitAt(pos);
                            break;
                        case "Copy to new document":
                            copyToNewDoc(pos);
                            break;
                        case "Copy page text":
                            copyToClipboard(PageMeta.read(page).text);
                            break;
                        case "Delete page":
                            Ui.confirm(this, "Delete page " + (pos + 1) + "? It stays in the recycle bin for 30 days.",
                                    () -> Ui.task(this, () -> store.trashPage(page), this::changed, this::changed));
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void movePage(int from, int to) {
        if (to < 0 || to >= pages.size() || to == from) return;
        final List<File> order = new ArrayList<>(pages);
        File f = order.remove(from);
        order.add(to, f);
        Ui.task(this, () -> store.reorder(order), this::changed, this::changed);
    }

    private void askPosition(final int from) {
        final EditText input = Ui.input(this, "1 – " + pages.size(), false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle("Move page " + (from + 1) + " to position")
                .setView(padded(input))
                .setPositiveButton("Move", (d, w) -> {
                    try {
                        int to = Integer.parseInt(input.getText().toString().trim()) - 1;
                        movePage(from, Math.max(0, Math.min(pages.size() - 1, to)));
                    } catch (NumberFormatException e) {
                        Ui.toast(this, "Enter a page number");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View padded(View v) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        wrap.setPadding(p, Ui.dp(this, 8), p, 0);
        wrap.addView(v, Ui.full(this, 0));
        return wrap;
    }

    private void splitAt(final int pos) {
        if (pos == 0) {
            Ui.toast(this, "Choose a page after the first one to split there");
            return;
        }
        final List<File> moving = new ArrayList<>(pages.subList(pos, pages.size()));
        final String newTitle = store.getTitle(dir) + " (part 2)";
        new AlertDialog.Builder(this)
                .setMessage("Move pages " + (pos + 1) + "–" + pages.size()
                        + " into a new document called “" + newTitle + "”?")
                .setPositiveButton("Split", (d, w) -> {
                    final File target = store.createDoc(newTitle);
                    Ui.task(this, () -> {
                        for (File f : moving) store.movePage(f, target);
                    }, () -> {
                        changed();
                        Ui.toast(this, "Split into “" + newTitle + "”");
                    }, this::changed);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyToNewDoc(final int pos) {
        final File page = pages.get(pos);
        final String newTitle = store.getTitle(dir) + " – page " + (pos + 1);
        final File target = store.createDoc(newTitle);
        Ui.task(this, () -> store.copyPage(page, target),
                () -> {
                    Indexer.kick(this);
                    Ui.toast(this, "Copied to “" + newTitle + "”");
                },
                () -> Ui.task(this, () -> store.deleteDoc(target), null, null));
    }

    // ------------------------------------------------------------------ document actions

    private void showMoreMenu() {
        final List<String> items = new ArrayList<>(Arrays.asList(
                "Share PDF", "Print", "Save pages as pictures", "Apply a filter to every page", "Select pages",
                "Recognise text now", "Recognise text again", "Copy all text", "Add pages from files"));
        if (DriveSync.ready(this)) items.add("Upload to Google Drive now");
        final String origin = store.hasOrigin(dir) ? store.getOriginName(dir) : null;
        if (origin != null) items.add(0, "Save over \u201C" + origin + "\u201D");
        items.addAll(Arrays.asList("Merge another document in", "Rename", "Delete document"));
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (items.get(which).startsWith("Save over")) {
                        saveOverOriginal(origin);
                        return;
                    }
                    switch (items.get(which)) {
                        case "Share PDF":
                            if (requirePages()) ensureText(() -> sharePdf(new ArrayList<>(pages)));
                            break;
                        case "Print":
                            if (requirePages()) print(new ArrayList<>(pages));
                            break;
                        case "Save pages as pictures":
                            if (requirePages()) saveImages(new ArrayList<>(pages));
                            break;
                        case "Apply a filter to every page":
                            if (requirePages()) filterSelected(new ArrayList<>(pages));
                            break;
                        case "Select pages":
                            if (requirePages()) Ui.toast(this, "Tap pages to select them; long-press and drag a page to move it");
                            if (!pages.isEmpty()) {
                                selected.add(pages.get(0));
                                selectionChanged();
                            }
                            break;
                        case "Recognise text now":
                            ensureText(() -> Ui.toast(this, "All pages are indexed"));
                            break;
                        case "Recognise text again":
                            Ui.confirm(this, "Read the text of every page again? Useful after adding a language.",
                                    () -> Ui.task(this, () -> {
                                        for (File p : pages) {
                                            synchronized (Indexer.LOCK) {
                                                PageMeta m = PageMeta.read(p);
                                                if (m.fromPdfText) continue;
                                                m.clearText();
                                                m.write(p);
                                            }
                                        }
                                    }, () -> ensureText(() -> Ui.toast(this, "Text read again")), null));
                            break;
                        case "Copy all text":
                            ensureText(() -> copyToClipboard(Transcript.read(dir)));
                            break;
                        case "Add pages from files":
                            MusicPlayer.holdForExternal();
                            startActivityForResult(MainActivity.pickFilesIntent(), REQ_ADD_FILES);
                            break;
                        case "Upload to Google Drive now":
                            if (requirePages()) {
                                ensureText(() -> Ui.task(this, () -> DriveSync.uploadDoc(this, store, dir),
                                        () -> Ui.toast(this, "Uploaded to Google Drive"), null));
                            }
                            break;
                        case "Merge another document in":
                            chooseMergeSource();
                            break;
                        case "Rename":
                            rename();
                            break;
                        default:
                            Ui.confirm(this, "Delete this document? It stays in the recycle bin for 30 days.",
                                    () -> Ui.task(this, () -> store.trashDoc(dir), this::finish, null));
                            break;
                    }
                })
                .show();
    }

    private boolean requirePages() {
        if (pages.isEmpty()) {
            Ui.toast(this, "No pages yet");
            return false;
        }
        return true;
    }

    private Intent createDocIntent(String mime, String extension) {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, safeFileName(store.getTitle(dir)) + extension);
    }

    /** Export options: format, size, searchable, password, and Drive. */
    private void showExportSheet() {
        if (!requirePages()) return;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        v.setPadding(p, Ui.dp(this, 4), p, Ui.dp(this, 4));

        v.addView(Ui.mono(this, "FORMAT"));
        exportFormat = 0;
        Ui.chipRow(this, v, FORMATS, 0, i -> exportFormat = i);
        v.addView(Ui.mono(this, "SIZE"), Ui.full(this, 12));
        exportSize = Prefs.exportSize(this);
        Ui.chipRow(this, v, Prefs.SIZE_LABELS, Prefs.indexOf(Prefs.SIZES, exportSize), i -> exportSize = Prefs.SIZES[i]);
        v.addView(Ui.mono(this, "Small turns black-and-white scans into 1-bit images: crisp and tiny."), Ui.full(this, 4));

        final Switch searchable = new Switch(this);
        searchable.setText("Searchable (recognise text first)");
        searchable.setTextColor(Ui.INK);
        searchable.setMinHeight(Ui.dp(this, 48));
        searchable.setChecked(true);
        v.addView(searchable, Ui.full(this, 8));

        v.addView(Ui.mono(this, "PASSWORD (OPTIONAL)"), Ui.full(this, 8));
        final EditText pw = Ui.input(this, "Leave empty for no password", true);
        v.addView(pw, Ui.full(this, 6));
        v.addView(Ui.mono(this, "Protects the PDF with AES-256. The .txt transcript is plain text and isn't protected."),
                Ui.full(this, 4));

        final Switch drive = new Switch(this);
        if (DriveSync.ready(this)) {
            drive.setText("Also upload to Google Drive");
            drive.setTextColor(Ui.INK);
            drive.setMinHeight(Ui.dp(this, 48));
            v.addView(drive, Ui.full(this, 8));
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        new AlertDialog.Builder(this)
                .setTitle("Export")
                .setView(sv)
                .setPositiveButton("Export", (d, w) -> {
                    exportSearchable = searchable.isChecked();
                    String pass = pw.getText().toString();
                    exportPassword = pass.isEmpty() ? null : pass;
                    exportDrive = DriveSync.ready(this) && drive.isChecked();
                    switch (exportFormat) {
                        case 0:
                            MusicPlayer.holdForExternal();
                            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_EXPORT_FOLDER);
                            break;
                        case 1:
                            MusicPlayer.holdForExternal();
                            startActivityForResult(createDocIntent("application/pdf", ".pdf"), REQ_EXPORT_PDF);
                            break;
                        default:
                            MusicPlayer.holdForExternal();
                            startActivityForResult(createDocIntent("text/plain", ".txt"), REQ_EXPORT_TXT);
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Writes the edited pages back over the PDF they came from (previous version kept in the bin). */
    private void saveOverOriginal(final String originName) {
        if (!requirePages()) return;
        Ui.confirm(this, "Replace \u201C" + originName + "\u201D with this edited version? The previous version "
                + "stays in the recycle bin for 30 days.", () -> ensureText(() -> {
                    final List<File> snapshot = new ArrayList<>(pages);
                    Ui.task(this, () -> {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        PdfExport.write(snapshot, exportSize, null, store.getTitle(dir), b);
                        PdfEditing.saveOverOriginal(this, store, dir, b.toByteArray());
                    }, () -> Ui.toast(this, "Saved over " + originName), null);
                }));
    }

    /** Makes a searchable PDF (no password, the chosen size) and opens the share sheet with it. */
    private void sharePdf(List<File> which) {
        final List<File> snapshot = new ArrayList<>(which);
        final String title = safeFileName(store.getTitle(dir));
        final File[] out = new File[1];
        Ui.task(this, () -> {
            ShareProvider.clear(this);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            PdfExport.write(snapshot, exportSize, null, store.getTitle(dir), b);
            File f = new File(ShareProvider.dir(this), title + ".pdf");
            java.nio.file.Files.write(f.toPath(), b.toByteArray());
            out[0] = f;
        }, () -> {
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(out[0]))
                    .putExtra(Intent.EXTRA_SUBJECT, Ui.displayTitle(store.getTitle(dir)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri(title, ShareProvider.uriFor(out[0])));
            MusicPlayer.holdForExternal();
            startActivity(Intent.createChooser(send, "Share " + title + ".pdf"));
        }, null);
    }

    private byte[] buildPdf(List<File> snapshot) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PdfExport.write(snapshot, exportSize, exportPassword, store.getTitle(dir), b);
        return b.toByteArray();
    }

    private static void writeTo(ContentResolver cr, Uri target, byte[] data) throws IOException {
        try (OutputStream os = cr.openOutputStream(target, "wt")) {
            if (os == null) throw new IOException("Cannot write the file");
            os.write(data);
        }
    }

    /** Writes a PDF or a transcript to a file the user picked, and optionally uploads it. */
    private void exportTo(final Uri pdfTarget, final Uri txtTarget) {
        final List<File> snapshot = new ArrayList<>(pages);
        final String title = safeFileName(store.getTitle(dir));
        final boolean toDrive = exportDrive;
        Ui.task(this, () -> {
            if (pdfTarget != null) {
                byte[] pdf = buildPdf(snapshot);
                writeTo(getContentResolver(), pdfTarget, pdf);
                if (toDrive) DriveSync.uploadFile(this, title + ".pdf", "application/pdf", pdf);
            }
            if (txtTarget != null) {
                byte[] txt = Transcript.update(store, dir).getBytes(StandardCharsets.UTF_8);
                writeTo(getContentResolver(), txtTarget, txt);
                if (toDrive) DriveSync.uploadFile(this, title + ".txt", "text/plain; charset=UTF-8", txt);
            }
        }, () -> Ui.toast(this, toDrive ? "Saved and uploaded to Google Drive" : "Saved"), null);
    }

    /** Writes Title.pdf and Title.txt side by side in a chosen folder. */
    private void exportToFolder(final Uri tree) {
        final List<File> snapshot = new ArrayList<>(pages);
        final String title = safeFileName(store.getTitle(dir));
        final boolean toDrive = exportDrive;
        Ui.task(this, () -> {
            ContentResolver cr = getContentResolver();
            Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Uri pdfUri = DocumentsContract.createDocument(cr, folder, "application/pdf", title + ".pdf");
            Uri txtUri = DocumentsContract.createDocument(cr, folder, "text/plain", title + ".txt");
            if (pdfUri == null || txtUri == null) throw new IOException("Cannot create files in that folder");
            byte[] pdf = buildPdf(snapshot);
            byte[] txt = Transcript.update(store, dir).getBytes(StandardCharsets.UTF_8);
            writeTo(cr, pdfUri, pdf);
            writeTo(cr, txtUri, txt);
            if (toDrive) {
                DriveSync.uploadFile(this, title + ".pdf", "application/pdf", pdf);
                DriveSync.uploadFile(this, title + ".txt", "text/plain; charset=UTF-8", txt);
            }
        }, () -> Ui.toast(this, "Saved " + title + ".pdf and " + title + ".txt"
                + (toDrive ? ", and uploaded to Google Drive" : "")), null);
    }

    private static String safeFileName(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return t.isEmpty() ? "Document" : t;
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null || text == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(store.getTitle(dir), text));
        Ui.toast(this, text.trim().isEmpty() ? "No text found" : "Text copied");
    }

    /** Shows the document's transcript; text can be selected and copied. */
    private void showTranscript() {
        if (!requirePages()) return;
        Ui.task(this, () -> Transcript.update(store, dir), () -> {
            int missing = 0;
            for (boolean b : hasText) if (!b) missing++;
            TextView tv = Ui.body(this, Transcript.read(dir), false);
            tv.setTextIsSelectable(true);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextSize(13);
            int pad = Ui.dp(this, 20);
            tv.setPadding(pad, pad, pad, pad);
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle(missing == 0 ? "Transcript" : "Transcript (" + missing + " page"
                            + (missing == 1 ? "" : "s") + " not indexed yet)")
                    .setView(sv)
                    .setPositiveButton("Copy", (d, w) -> copyToClipboard(Transcript.read(dir)))
                    .setNegativeButton("Close", null);
            if (missing > 0) {
                b.setNeutralButton("Index now", (d, w) -> ensureText(this::showTranscript));
            }
            b.show();
        }, null);
    }

    /**
     * Recognises text on pages that don't have it yet (with progress), updates the transcript,
     * then runs after. Shares the indexer's lock so it never clashes with background indexing.
     */
    private void ensureText(final Runnable after) {
        if (!requirePages()) return;
        final List<File> todo = new ArrayList<>();
        for (File p : pages) if (!PageMeta.hasTextQuick(p)) todo.add(p);
        if (todo.isEmpty()) {
            Ui.task(this, () -> Transcript.update(store, dir), after, null);
            return;
        }
        final TextView msg = Ui.body(this, "Preparing text recognition…", false);
        int pad = Ui.dp(this, 24);
        msg.setPadding(pad, pad, pad, pad);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Recognising text")
                .setView(msg)
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String error = null;
            try (Ocr ocr = new Ocr(this)) {
                for (int i = 0; i < todo.size(); i++) {
                    final String m = "Page " + (i + 1) + " of " + todo.size() + "…";
                    runOnUiThread(() -> msg.setText(m));
                    Indexer.recognize(ocr, todo.get(i));
                }
                Transcript.update(store, dir);
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final String err = error;
            runOnUiThread(() -> {
                dialog.dismiss();
                refreshTextFlags();
                if (err == null) after.run();
                else Ui.toast(this, "Text recognition failed: " + err);
            });
        }).start();
    }

    private void chooseMergeSource() {
        final List<File> others = new ArrayList<>();
        for (File d : store.listDocs()) {
            if (!d.equals(dir)) others.add(d);
        }
        if (others.isEmpty()) {
            Ui.toast(this, "No other documents to merge");
            return;
        }
        String[] names = new String[others.size()];
        for (int k = 0; k < names.length; k++) {
            File o = others.get(k);
            names[k] = store.getTitle(o) + " (" + store.pages(o).length + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Append pages from")
                .setItems(names, (d, which) -> {
                    final File src = others.get(which);
                    Ui.task(this, () -> {
                        for (File f : store.pages(src)) store.copyPage(f, dir);
                    }, this::changed, this::changed);
                })
                .show();
    }
}
