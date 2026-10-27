package com.anamol.pdfscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Preview of the document as it will look in the PDF, one page at a time. Swipe between pages,
 * zoom in, and edit the page on screen: crop, filters, adjustments, clean-up, mark-up, signature,
 * rotate or delete. Pages keep their original photo, so every edit can be changed again later.
 */
public class PagePreviewActivity extends Activity implements PageSwiper.Source, PageSwiper.Listener {
    private static final String EXTRA_DIR = "dir";
    private static final String EXTRA_PAGE = "page";
    private static final int REQ_EDIT = 1;
    /** Pages are decoded at about screen size: sharp enough to zoom, small enough to keep several. */
    private static int maxSide(Context c) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return Math.min(2048, Math.round(Math.max(dm.widthPixels, dm.heightPixels) * 1.25f));
    }

    /** A third of the app's memory for page pictures (tablets get more), at most 192 MB. */
    static int cacheBytes() {
        long max = Runtime.getRuntime().maxMemory();
        return (int) Math.min(192L * 1024 * 1024, Math.max(32L * 1024 * 1024, max / 3));
    }

    private int maxSide = 2048;

    static Intent intent(Context c, File dir, int page) {
        return new Intent(c, PagePreviewActivity.class)
                .putExtra(EXTRA_DIR, dir.getAbsolutePath())
                .putExtra(EXTRA_PAGE, page);
    }

    private DocStore store;
    private File dir;
    private final List<File> pages = new ArrayList<>();
    private PageSwiper swiper;
    private TextView counter;
    private TextView hint;
    private LinearLayout filterStrip;
    private boolean busy;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Set<String> loading = new HashSet<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(cacheBytes()) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DocStore(this);
        maxSide = maxSide(this);
        dir = new File(getIntent().getStringExtra(EXTRA_DIR));
        if (!dir.isDirectory()) {
            finish();
            return;
        }
        LinearLayout root = Ui.bare(this);

        counter = Ui.title(this, "");
        counter.setMinHeight(Ui.dp(this, 48));
        counter.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageButton more = Ui.iconButton(this, R.drawable.ic_more, "More");
        more.setOnClickListener(v -> showMore());
        root.addView(Ui.topBar(this, this::finish, counter, more));

        swiper = new PageSwiper(this);
        swiper.source = this;
        swiper.listener = this;
        swiper.setContentDescription("Page preview. Swipe to change page, pinch to zoom.");
        root.addView(swiper, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        hint = Ui.meta(this, "");
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 4));
        root.addView(hint);

        filterStrip = new LinearLayout(this);
        filterStrip.setOrientation(LinearLayout.VERTICAL);
        filterStrip.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        filterStrip.setVisibility(View.GONE);
        root.addView(filterStrip);

        root.addView(Ui.hairline(this, 0));
        root.addView(toolbar());
        setContentView(root);

        reload();
        swiper.setIndex(state != null ? state.getInt("index") : getIntent().getIntExtra(EXTRA_PAGE, 0));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("index", swiper.index());
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ toolbar

    private View toolbar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 10));
        row.addView(tool(R.drawable.ic_crop, "Crop", v -> edit(PageEditorActivity.TAB_CROP, null)));
        row.addView(tool(R.drawable.ic_filter, "Filters", v -> toggleFilters()));
        row.addView(tool(R.drawable.ic_adjust, "Adjust", v -> edit(PageEditorActivity.TAB_ADJUST, null)));
        row.addView(tool(R.drawable.ic_eraser, "Clean up", v -> edit(PageEditorActivity.TAB_CLEAN, null)));
        row.addView(tool(R.drawable.ic_pen, "Mark up", v -> edit(PageEditorActivity.TAB_MARK, PageMeta.Mark.PEN)));
        row.addView(tool(R.drawable.ic_sign, "Sign", v -> edit(PageEditorActivity.TAB_MARK, PageMeta.Mark.SIGNATURE)));
        row.addView(tool(R.drawable.ic_rotate, "Rotate", v -> rotate()));
        row.addView(tool(R.drawable.ic_trash, "Delete", v -> delete()));
        scroll.addView(row);
        return scroll;
    }

    private View tool(int icon, String label, View.OnClickListener click) {
        return Ui.tool(this, icon, label, click);
    }

    // ------------------------------------------------------------------ pages

    private void reload() {
        List<File> before = new ArrayList<>(pages);
        pages.clear();
        pages.addAll(Arrays.asList(store.pages(dir)));
        if (!before.equals(pages)) swiper.forget();  // pages moved: old pictures belong elsewhere
        if (pages.isEmpty()) {
            finish();
            return;
        }
        if (swiper.index() >= pages.size()) swiper.setIndex(pages.size() - 1);
        onPageChanged(swiper.index());
        swiper.invalidate();
    }

    private File current() {
        int i = swiper.index();
        return i >= 0 && i < pages.size() ? pages.get(i) : null;
    }

    private static String key(File f) {
        return f.getAbsolutePath() + ":" + f.lastModified() + ":" + f.length();
    }

    @Override
    public int count() {
        return pages.size();
    }

    @Override
    public Bitmap page(int index) {
        if (index < 0 || index >= pages.size()) return null;
        final File f = pages.get(index);
        final String k = key(f);
        Bitmap b = cache.get(k);
        if (b != null) return b;
        synchronized (loading) {
            if (!loading.add(k)) return null;
        }
        final int side = maxSide;
        loader.execute(() -> {
            Bitmap d = decode(f, side);
            runOnUiThread(() -> {
                synchronized (loading) {
                    loading.remove(k);
                }
                if (d != null && !isFinishing()) {
                    cache.put(k, d);
                    swiper.invalidate();
                }
            });
        });
        return null;
    }

    private static Bitmap decode(File f, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int side = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (side / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
    }

    @Override
    public void onPageChanged(int index) {
        counter.setText("Page " + (index + 1) + " of " + pages.size());
        File f = current();
        if (f == null) return;
        PageMeta m = PageMeta.read(f);
        String filter = ImageOps.FILTER_LABELS[Math.max(0, Prefs.indexOf(ImageOps.FILTERS, m.filter))];
        String crop = m.corners == null ? "uncropped" : "cropped";
        int marks = m.marks.size();
        hint.setText(filter + " · " + crop + (marks > 0 ? " · " + marks + (marks == 1 ? " mark" : " marks") : "")
                + (pages.size() > 1 ? " · swipe for more pages" : ""));
        if (filterStrip.getVisibility() == View.VISIBLE) buildFilters();
        // load neighbours early so swiping is smooth
        // load the neighbours early so swiping never shows an empty page
        page(index + 1);
        page(index - 1);
        page(index + 2);
        page(index - 2);
    }

    // ------------------------------------------------------------------ actions

    private void edit(int tab, String tool) {
        File f = current();
        if (f == null || busy) return;
        startActivityForResult(PageEditorActivity.forEdit(this, dir, f, tab, tool), REQ_EDIT);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_EDIT) {
            changed();
        }
    }

    private void changed() {
        setResult(RESULT_OK);
        reload();
        Indexer.kick(this);
    }

    private void toggleFilters() {
        if (filterStrip.getVisibility() == View.VISIBLE) {
            filterStrip.setVisibility(View.GONE);
        } else {
            buildFilters();
            filterStrip.setVisibility(View.VISIBLE);
        }
    }

    private void buildFilters() {
        filterStrip.removeAllViews();
        File f = current();
        if (f == null) return;
        int sel = Prefs.indexOf(ImageOps.FILTERS, PageMeta.read(f).filter);
        Ui.chipRow(this, filterStrip, ImageOps.FILTER_LABELS, sel, i -> applyFilter(ImageOps.FILTERS[i]));
    }

    /** Applies a filter to the page straight away; the rest of its edits stay as they were. */
    private void applyFilter(final String filter) {
        final File f = current();
        if (f == null || busy) return;
        busy = true;
        hint.setText("Applying…");
        Ui.task(this, () -> {
            PageMeta m = PageMeta.read(f);
            m.filter = filter;
            ImageProcessor.renderPage(f, m);
        }, () -> {
            busy = false;
            changed();
        }, () -> busy = false);
    }

    private void rotate() {
        final File f = current();
        if (f == null || busy) return;
        busy = true;
        Ui.task(this, () -> {
            PageMeta m = PageMeta.read(f);
            m.rotateCw();
            ImageProcessor.renderPage(f, m);
        }, () -> {
            busy = false;
            swiper.resetZoom();
            changed();
        }, () -> busy = false);
    }

    private void delete() {
        final File f = current();
        if (f == null || busy) return;
        Ui.confirm(this, "Delete page " + (swiper.index() + 1) + "? It stays in the recycle bin for 30 days.", () -> Ui.task(this,
                () -> store.trashPage(f), this::changed, this::changed));
    }

    /** Undoes crop, filter, adjustments and clean-up; mark-up and signatures stay. */
    private void resetEdits() {
        final File f = current();
        if (f == null || busy) return;
        busy = true;
        Ui.task(this, () -> {
            PageMeta m = PageMeta.read(f);
            m.corners = null;
            m.filter = ImageOps.ORIGINAL;
            m.glare = false;
            m.brightness = 0;
            m.contrast = 0;
            m.sharpen = 0;
            m.erase.clear();
            ImageProcessor.renderPage(f, m);
        }, () -> {
            busy = false;
            changed();
        }, () -> busy = false);
    }

    /**
     * A photo of an open book becomes two pages: the fold is found from the shadow along the
     * spine, and each half gets its own outline (from the same original photo, so both can still
     * be adjusted). Mark-up and clean-up are cleared because they no longer line up.
     */
    private void splitSpread() {
        final File f = current();
        if (f == null || busy) return;
        busy = true;
        hint.setText("Splitting\u2026");
        final int index = swiper.index();
        Ui.task(this, () -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) throw new java.io.IOException("Cannot read the page");
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            b.recycle();
            float g = ScanMath.gutter(px, w, h);
            PageMeta m = PageMeta.read(f);
            float[][] halves = ScanMath.splitQuad(m.corners, g);
            File copy = store.copyPage(f, dir);
            PageMeta left = PageMeta.read(f);
            left.corners = halves[0];
            left.spineLeft = false;
            left.marks.clear();
            left.erase.clear();
            PageMeta right = PageMeta.read(copy);
            right.corners = halves[1];
            right.spineLeft = true;
            right.marks.clear();
            right.erase.clear();
            ImageProcessor.renderPage(f, left);
            ImageProcessor.renderPage(copy, right);
            List<File> order = new ArrayList<>(Arrays.asList(store.pages(dir)));
            order.remove(copy);
            order.add(Math.min(order.size(), index + 1), copy);
            store.reorder(order);
        }, () -> {
            busy = false;
            changed();
            Ui.toast(this, "Split into two pages. Use Adjust \u203A Book page to flatten the curve.");
        }, () -> {
            busy = false;
            changed();
        });
    }

    private void showMore() {
        final File f = current();
        if (f == null) return;
        final List<String> items = new ArrayList<>();
        items.add("Back to the original photo");
        items.add("Split book spread into two pages");
        final String text = PageMeta.read(f).text;
        if (text != null && !text.trim().isEmpty()) items.add("Copy page text");
        new AlertDialog.Builder(this)
                .setTitle("Page " + (swiper.index() + 1))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String it = items.get(which);
                    if (it.startsWith("Split")) {
                        splitSpread();
                    } else if (it.startsWith("Back")) {
                        Ui.confirm(this, "Remove the crop, filter, adjustments and clean-up from this page? "
                                + "Mark-up and signatures stay.", this::resetEdits);
                    } else {
                        ClipboardManager cm = getSystemService(ClipboardManager.class);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Page text", text));
                        Ui.toast(this, "Text copied");
                    }
                })
                .show();
    }
}
