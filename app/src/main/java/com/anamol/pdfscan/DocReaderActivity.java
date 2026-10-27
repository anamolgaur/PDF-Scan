package com.anamol.pdfscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens documents inside the app instead of handing them to another app. PDFs are shown page by
 * page (swipe, pinch to zoom). Word, Excel, PowerPoint, OpenDocument and text files are shown as
 * their text, with slide, sheet and page headings. Another app is still one tap away in the menu.
 */
public class DocReaderActivity extends Activity implements PageSwiper.Source, PageSwiper.Listener {
    private static final String EXTRA_ID = "id";
    private static final String EXTRA_PATH = "path";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_PAGE = "page";

    /** A document found on the phone (from the index). page is 0-based, or -1. */
    static Intent forPhone(Context c, long id, String path, String name, int page) {
        return new Intent(c, DocReaderActivity.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_PAGE, page);
    }

    private long id = -1;
    private String path;
    private Uri uri;          // set when opened from another app ("Open with PDF Scan")
    private String name;
    private boolean pdf;

    private PageSwiper swiper;
    private ScrollView textScroll;
    private TextView textView;
    private TextView meta;
    private TextView loading;
    private ParcelFileDescriptor fd;
    private PdfRenderer renderer;
    private int pageCount;
    private int renderWidth;
    private boolean showingText;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<Integer> rendering = new HashSet<>();
    private final LruCache<Integer, Bitmap> pages = new LruCache<Integer, Bitmap>(PagePreviewActivity.cacheBytes()) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        if (Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            uri = in.getData();
            name = displayName(uri);
        } else {
            id = in.getLongExtra(EXTRA_ID, -1);
            path = in.getStringExtra(EXTRA_PATH);
            name = in.getStringExtra(EXTRA_NAME);
        }
        if (name == null) name = path != null ? new File(path).getName() : "Document";
        pdf = OfficeText.extension(name).equals("pdf") || (uri != null && "application/pdf".equals(getContentResolver().getType(uri)));
        // about 1.5x the screen width: sharp when zoomed, small enough to keep several pages ready
        renderWidth = Math.min(1800, Math.round(getResources().getDisplayMetrics().widthPixels * 1.5f));

        LinearLayout root = Ui.bare(this);
        TextView title = Ui.title(this, titleOf(name));
        title.setMinHeight(Ui.dp(this, 48));
        title.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton editBtn = Ui.iconButton(this, R.drawable.ic_pen, "Edit this PDF");
        editBtn.setOnClickListener(v -> editPdf());
        ImageButton textBtn = Ui.iconButton(this, R.drawable.ic_text, "Show text");
        ImageButton more = Ui.iconButton(this, R.drawable.ic_more, "More");
        textBtn.setOnClickListener(v -> toggleText());
        more.setOnClickListener(v -> showMore());
        if (!pdf) {
            textBtn.setVisibility(View.GONE);
            editBtn.setVisibility(View.GONE);
        }
        root.addView(Ui.topBar(this, this::finish, title, editBtn, textBtn, more));
        meta = Ui.meta(this, typeOf(name));
        meta.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 6));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(meta);

        FrameLayout stage = new FrameLayout(this);
        swiper = new PageSwiper(this);
        swiper.source = this;
        swiper.listener = this;
        swiper.setVisibility(View.GONE);
        stage.addView(swiper, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        textScroll = new ScrollView(this);
        textView = Ui.body(this, "", false);
        textView.setTextIsSelectable(true);
        textView.setTextSize(16);
        textView.setLineSpacing(0, 1.6f);
        textView.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 32));
        textScroll.addView(textView);
        textScroll.setVisibility(View.GONE);
        stage.addView(textScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loading = Ui.meta(this, "Opening…");
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.gravity = Gravity.CENTER;
        stage.addView(loading, ll);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        final int startPage = state != null ? state.getInt("page", 0) : Math.max(0, in.getIntExtra(EXTRA_PAGE, 0));
        if (pdf) openPdf(startPage);
        else showText();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", swiper.index());
    }

    @Override
    protected void onDestroy() {
        worker.execute(() -> {
            try {
                if (renderer != null) renderer.close();
                if (fd != null) fd.close();
            } catch (IOException | IllegalStateException ignored) {
                // closing anyway
            }
        });
        worker.shutdown();
        super.onDestroy();
    }

    private static String titleOf(String name) {
        int dot = name.lastIndexOf('.');
        return Ui.displayTitle(dot > 0 ? name.substring(0, dot) : name);
    }

    private static String typeOf(String name) {
        String ext = OfficeText.extension(name).toUpperCase(Locale.ROOT);
        return ext.isEmpty() ? "Document" : ext;
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall through
        }
        return u.getLastPathSegment();
    }

    // ------------------------------------------------------------------ PDF pages

    private void openPdf(final int startPage) {
        worker.execute(() -> {
            String error = null;
            try {
                fd = path != null ? ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                        : getContentResolver().openFileDescriptor(uri, "r");
                if (fd == null) throw new IOException("Can't open the file");
                renderer = new PdfRenderer(fd);
                pageCount = renderer.getPageCount();
            } catch (SecurityException e) {
                error = "This PDF is password-protected, so its pages can't be shown here.";
            } catch (IOException | RuntimeException e) {
                error = "This PDF couldn't be opened: " + e.getMessage();
                Diag.log("Reader: " + name, e);
            }
            final String err = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (err != null || pageCount == 0) {
                    loading.setVisibility(View.GONE);
                    showText();
                    Ui.toast(this, err != null ? err : "This PDF has no pages");
                    return;
                }
                loading.setVisibility(View.GONE);
                swiper.setVisibility(View.VISIBLE);
                swiper.setIndex(Math.min(startPage, pageCount - 1));
            });
        });
    }

    @Override
    public int count() {
        return pageCount;
    }

    @Override
    public Bitmap page(final int index) {
        if (index < 0 || index >= pageCount) return null;
        Bitmap b = pages.get(index);
        if (b != null) return b;
        synchronized (rendering) {
            if (!rendering.add(index)) return null;
        }
        worker.execute(() -> {
            Bitmap out = null;
            try {
                if (renderer != null) {
                    try (PdfRenderer.Page p = renderer.openPage(index)) {
                        int w = renderWidth;
                        int h = Math.max(1, Math.round(w * p.getHeight() / (float) Math.max(1, p.getWidth())));
                        h = Math.min(h, w * 3);
                        out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        out.eraseColor(Color.WHITE);
                        p.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                Diag.log("Reader page " + index, e);
            }
            final Bitmap done = out;
            runOnUiThread(() -> {
                synchronized (rendering) {
                    rendering.remove(index);
                }
                if (done != null && !isFinishing()) {
                    pages.put(index, done);
                    swiper.invalidate();
                }
            });
        });
        return null;
    }

    @Override
    public void onPageChanged(int index) {
        meta.setText("PDF · page " + (index + 1) + " of " + pageCount);
        page(index + 1);
        page(index - 1);
        page(index + 2);
    }

    // ------------------------------------------------------------------ text

    private void toggleText() {
        if (showingText) {
            showingText = false;
            textScroll.setVisibility(View.GONE);
            swiper.setVisibility(pageCount > 0 ? View.VISIBLE : View.GONE);
            onPageChanged(swiper.index());
        } else {
            showText();
        }
    }

    /** The document's text: from the phone index if it was read already, otherwise read now. */
    private void showText() {
        showingText = true;
        swiper.setVisibility(View.GONE);
        textScroll.setVisibility(View.VISIBLE);
        loading.setVisibility(View.VISIBLE);
        final String[] text = {""};
        Ui.task(this, () -> {
            if (id >= 0) text[0] = DeviceIndex.get(this).text(id);
            if (text[0].isEmpty()) text[0] = readText();
        }, () -> {
            loading.setVisibility(View.GONE);
            textView.setText(text[0].isEmpty() ? "No text could be read from this document." : text[0]);
            if (!pdf) meta.setText(typeOf(name) + " · text view");
            else meta.setText("PDF · text");
        }, () -> loading.setVisibility(View.GONE));
    }

    private String readText() throws IOException {
        if (path == null) {
            if (!pdf) return "";
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return "";
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0 && b.size() < 80 * 1024 * 1024) b.write(buf, 0, n);
                return join(PdfText.extract(b.toByteArray()), "Page");
            }
        }
        File f = new File(path);
        if (pdf) return join(PdfText.extract(java.nio.file.Files.readAllBytes(f.toPath())), "Page");
        OfficeText.Result r = OfficeText.extract(f);
        String unit = r.unit == null ? "section" : r.unit;
        if (unit.equals("section")) return String.join("\n\n", r.parts).trim();
        return join(r.parts, Character.toUpperCase(unit.charAt(0)) + unit.substring(1));
    }

    private static String join(List<String> parts, String unit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i).trim();
            if (p.isEmpty()) continue;
            sb.append("--- ").append(unit).append(' ').append(i + 1).append(" ---\n").append(p).append("\n\n");
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ menu

    private Uri shareUri() {
        if (uri != null) return uri;
        return id >= 0 ? PhoneFileProvider.uriFor(id, name) : null;
    }

    private void showMore() {
        final List<String> items = new ArrayList<>();
        items.add("Open in another app");
        items.add("Share");
        items.add("Copy text");
        if (pdf) items.add("Edit this PDF");
        if (path != null && id >= 0) items.add("Delete from phone");
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String it = items.get(which);
                    switch (it) {
                        case "Open in another app":
                            openElsewhere();
                            break;
                        case "Share":
                            share();
                            break;
                        case "Copy text":
                            copyText();
                            break;
                        case "Delete from phone":
                            PhoneFiles.delete(this, id, path, this::finish);
                            break;
                        default:
                            addToScans();
                            break;
                    }
                })
                .show();
    }

    private void openElsewhere() {
        if (uri == null && id >= 0) {
            PhoneFileProvider.open(this, id, name);
            return;
        }
        Uri u = shareUri();
        if (u == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, PhoneFileProvider.mimeOf(name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            MusicPlayer.holdForExternal();
            startActivity(Intent.createChooser(i, "Open " + name));
        } catch (RuntimeException e) {
            Ui.toast(this, "No other app can open this file");
        }
    }

    private void share() {
        Uri u = shareUri();
        if (u == null) return;
        Intent send = new Intent(Intent.ACTION_SEND).setType(PhoneFileProvider.mimeOf(name))
                .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(name, u));
        MusicPlayer.holdForExternal();
        startActivity(Intent.createChooser(send, "Share " + name));
    }

    private void copyText() {
        final String[] text = {""};
        Ui.task(this, () -> {
            if (id >= 0) text[0] = DeviceIndex.get(this).text(id);
            if (text[0].isEmpty()) text[0] = readText();
        }, () -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(name, text[0]));
            Ui.toast(this, text[0].isEmpty() ? "No text in this document" : "Text copied");
        }, null);
    }

    /** Opens this PDF's pages in the editor (crop, filters, mark-up, signatures...). */
    private void editPdf() {
        Uri u = shareUri();
        if (u == null) return;
        PdfEditing.open(this, u, name, path, uri, pageCount > 0 ? swiper.index() : 0);
    }

    private void addToScans() {
        editPdf();
    }
}
