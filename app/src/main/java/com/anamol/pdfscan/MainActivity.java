package com.anamol.pdfscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen with two sections: Scans (documents made in the app) and On this phone (documents
 * found on the device by the phone index). Search filters whichever section is open; both tabs
 * show how many items match.
 */
public class MainActivity extends Activity implements StatusBus.Listener {
    private static final int REQ_IMPORT = 2;
    private static final int TAB_SCANS = 0;
    private static final int TAB_PHONE = 1;
    private static final int PAGE = 100;
    private static final String[] TAB_NAMES = {"Scans", "On this phone"};

    /** One list row. */
    private static final class Row {
        String header;
        File dir;
        String title;
        int pages;
        long modified;
        Bitmap thumb;
        SearchCore.Hit hit;
        DeviceIndex.Entry entry;
        DeviceIndex.Hit phoneHit;
        String folder;
        boolean pinned;
        long size;
    }

    private static final String[] SORT_NAMES = {"Recent", "Name", "Pages", "Size"};
    private LinearLayout scanBar;
    private String folder = "";

    private DocStore store;
    private final List<Row> rows = new ArrayList<>();
    private BaseAdapter adapter;
    private ListView list;
    private LinearLayout emptyBox;
    private TextView emptyTitle;
    private TextView emptyText;
    private Button emptyButton;
    private TextView statusView;
    private LinearLayout filterBar;
    private final TextView[] tabLabels = new TextView[2];
    private final View[] tabMarks = new View[2];
    private int tab = TAB_SCANS;
    private int phoneType;
    private String query = "";
    private int generation;
    private int scanCount = -1;
    private int phoneCount = -1;
    private boolean phoneMore;
    private boolean loadingMore;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable runSearch = this::refresh;
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    /** File picker for PDFs and images; several files can be chosen at once. */
    static Intent pickFilesIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    static List<Uri> urisFrom(Intent data) {
        List<Uri> out = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        } else if (data.getData() != null) {
            out.add(data.getData());
        }
        return out;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DocStore(this);
        if (state != null) tab = state.getInt("tab", TAB_SCANS);

        LinearLayout root = Ui.bare(this);

        ImageButton settings = Ui.iconButton(this, R.drawable.ic_settings, "Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(Ui.topBar(this, null, Ui.heading(this, "PDF Scan"), settings));

        EditText search = Ui.searchField(this, "Search documents");
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                handler.removeCallbacks(runSearch);
                handler.postDelayed(runSearch, 200);
            }
        });
        LinearLayout.LayoutParams sl = Ui.full(this, 0);
        sl.setMargins(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), 0);
        root.addView(search, sl);

        root.addView(tabs(), Ui.full(this, 8));
        root.addView(Ui.hairline(this, 0));

        filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.VERTICAL);
        filterBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), 0, 0);
        Ui.chipRow(this, filterBar, DeviceIndex.TYPE_LABELS, 0, i -> {
            phoneType = i;
            refresh();
        });
        root.addView(filterBar);

        scanBar = new LinearLayout(this);
        scanBar.setOrientation(LinearLayout.VERTICAL);
        scanBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), 0, 0);
        root.addView(scanBar);

        statusView = Ui.meta(this, "");
        statusView.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), 0);
        statusView.setVisibility(View.GONE);
        root.addView(statusView);

        list = new ListView(this);
        list.setDivider(Ui.listDivider(this, 80, 20));
        list.setDividerHeight(1);
        list.setSelector(new RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), null, new ColorDrawable(0xFFFFFFFF)));
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return rows.size();
            }

            @Override
            public Object getItem(int position) {
                return rows.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return rowView(rows.get(position));
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return rows.get(position).header == null;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> open(rows.get(pos)));
        list.setOnItemLongClickListener((AdapterView<?> p, View v, int pos, long id) -> {
            menu(rows.get(pos));
            return true;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int s) {
                scrolling = s != SCROLL_STATE_IDLE;
                if (!scrolling && refreshPending) {
                    refreshPending = false;
                    refresh();
                }
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (tab == TAB_PHONE && query.isEmpty() && phoneMore && !loadingMore && total > 0
                        && first + visible >= total - 8) {
                    loadMorePhone();
                }
            }
        });

        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), Ui.dp(this, 48));
        emptyTitle = Ui.label(this, "");
        emptyTitle.setGravity(Gravity.CENTER);
        emptyText = Ui.body(this, "", true);
        emptyText.setGravity(Gravity.CENTER);
        emptyButton = Ui.button(this, "", true);
        emptyBox.addView(emptyTitle);
        emptyBox.addView(emptyText, Ui.full(this, 8));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.setMargins(0, Ui.dp(this, 20), 0, 0);
        emptyBox.addView(emptyButton, bl);
        list.setEmptyView(emptyBox);

        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(emptyBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button importBtn = Ui.button(this, "Import", R.drawable.ic_import, false);
        Button scan = Ui.button(this, "Scan", R.drawable.ic_camera, true);
        importBtn.setOnClickListener(v -> {
            MusicPlayer.holdForExternal();
            startActivityForResult(pickFilesIntent(), REQ_IMPORT);
        });
        scan.setOnClickListener(v -> startScan());
        root.addView(Ui.bottomBar(this, importBtn, scan));

        setContentView(root);
        updateTabLabels();
        selectTab(tab, false);

        // Android 13+: notifications carry the music controls and indexing progress
        if (state == null && Build.VERSION.SDK_INT >= 33 && !Prefs.get(this).getBoolean("asked_notifications", false)
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            Prefs.get(this).edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 43);
        }
    }

    /** Two equal tabs with a short cream indicator under the open one. */
    private View tabs() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), null, new ColorDrawable(0xFFFFFFFF)));
            TextView label = Ui.label(this, TAB_NAMES[i]);
            label.setTextSize(15);
            label.setGravity(Gravity.CENTER);
            label.setMinHeight(Ui.dp(this, 46));
            label.setSingleLine(true);
            View mark = new View(this);
            mark.setBackground(Ui.box(this, Ui.CREAM, 0, 2));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3));
            ml.setMargins(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
            t.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            t.addView(mark, ml);
            t.setOnClickListener(v -> selectTab(idx, true));
            tabLabels[i] = label;
            tabMarks[i] = mark;
            bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    private void selectTab(int t, boolean scrollTop) {
        tab = t;
        for (int i = 0; i < 2; i++) {
            tabMarks[i].setVisibility(i == t ? View.VISIBLE : View.INVISIBLE);
            tabLabels[i].setTextColor(i == t ? Ui.INK : Ui.MUTED);
        }
        filterBar.setVisibility(t == TAB_PHONE && PhoneFiles.enabled(this) && PhoneFiles.hasAccess(this)
                ? View.VISIBLE : View.GONE);
        scanBar.setVisibility(t == TAB_SCANS ? View.VISIBLE : View.GONE);
        if (scrollTop) {
            rows.clear();
            adapter.notifyDataSetChanged();
            list.setSelection(0);
        }
        refresh();
    }

    private void updateTabLabels() {
        int[] counts = {scanCount, phoneCount};
        for (int i = 0; i < 2; i++) {
            SpannableStringBuilder sb = new SpannableStringBuilder(TAB_NAMES[i]);
            if (counts[i] >= 0) {
                int s = sb.length();
                sb.append("  ").append(String.format(Locale.US, "%,d", counts[i]));
                sb.setSpan(new ForegroundColorSpan(Ui.MUTED), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.87f), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tabLabels[i].setText(sb);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    // ------------------------------------------------------------------ rows

    private View rowView(Row r) {
        if (r.header != null) {
            TextView h = Ui.section(this, r.header);
            h.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 4));
            return h;
        }
        if (r.entry != null) {
            DeviceIndex.Entry e = r.entry;
            String meta = folderOf(e.path) + " · " + Ui.when(e.mtime) + " · " + size(e.size);
            String note = statusNote(e.status);
            if (note != null) meta += " · " + note;
            return itemRow(cover(e.path, e.name, e.mtime, e.size), fileTitle(e.name), meta, null, fileType(e.name));
        }
        if (r.phoneHit != null) {
            DeviceIndex.Hit h = r.phoneHit;
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (h.snippet != null) {
                int start = -1;
                for (int i = 0; i < h.snippet.length(); i++) {
                    char c = h.snippet.charAt(i);
                    if (c == DeviceIndex.MARK_START) {
                        start = sb.length();
                    } else if (c == DeviceIndex.MARK_END) {
                        if (start >= 0 && start < sb.length()) mark(sb, start, sb.length());
                        start = -1;
                    } else {
                        sb.append(c);
                    }
                }
            }
            String meta = describeParts(h) + " · " + folderOf(h.path);
            return itemRow(cover(h.path, h.name, h.mtime, h.size), fileTitle(h.name), meta,
                    sb.length() > 0 ? sb : null, fileType(h.name));
        }

        View thumb;
        if (r.thumb != null) {
            ImageView img = new ImageView(this);
            img.setImageBitmap(r.thumb);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(0xFFFAF8F2);
            Ui.round(img, 4);
            thumb = img;
        } else {
            thumb = badge("");
        }
        String meta;
        CharSequence snippet = null;
        if (r.hit == null) {
            meta = r.pages + (r.pages == 1 ? " page" : " pages") + " · " + Ui.when(r.modified);
            if (folder.isEmpty() && r.folder != null && !r.folder.isEmpty()) meta = r.folder + " · " + meta;
        } else {
            meta = describePages(r.hit);
            if (!r.hit.snippet.isEmpty()) {
                SpannableString s = new SpannableString(r.hit.snippet);
                if (r.hit.matchStart >= 0 && r.hit.matchStart + r.hit.matchLength <= s.length()) {
                    mark(s, r.hit.matchStart, r.hit.matchStart + r.hit.matchLength);
                }
                snippet = s;
            }
        }
        return itemRow(thumb, Ui.displayTitle(r.title), meta, snippet, null);
    }

    /** File name without its extension, underscores as spaces (the type has its own line). */
    private static String fileTitle(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return Ui.displayTitle(base);
    }

    private static String fileType(String name) {
        String ext = OfficeText.extension(name).toLowerCase(Locale.ROOT);
        String kind;
        switch (ext) {
            case "pdf": kind = "PDF"; break;
            case "doc": case "docx": case "docm": case "odt": case "rtf": kind = "Word document"; break;
            case "xls": case "xlsx": case "xlsm": case "ods": case "csv": kind = "Spreadsheet"; break;
            case "ppt": case "pptx": case "pptm": case "ppsx": case "odp": kind = "Presentation"; break;
            case "txt": case "md": kind = "Text"; break;
            default: kind = ext.isEmpty() ? "File" : ext.toUpperCase(Locale.ROOT); break;
        }
        return "Filetype: " + kind + (ext.isEmpty() ? "" : " (." + ext + ")");
    }

    private final java.util.concurrent.ExecutorService coverBg = Executors.newFixedThreadPool(2);
    private final java.util.Set<String> coverMisses = new java.util.HashSet<>();
    private final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    /** Cover picture of a phone document (first page, title slide), with the type badge until it loads. */
    private View cover(final String path, String name, final long mtime, final long size) {
        FrameLayout f = new FrameLayout(this);
        f.addView(badge(name), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.round(img, 4);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final String key = path + ":" + mtime + ":" + size;
        img.setTag(key);
        Bitmap b = covers.get(key);
        if (b == null) {
            // already made once: read the small saved cover straight away instead of flashing the badge
            b = Covers.cached(getApplicationContext(), path, mtime, size);
            if (b != null) covers.put(key, b);
        }
        if (b != null) {
            img.setImageBitmap(b);
            img.setBackgroundColor(0xFFFAF8F2);
            return f;
        }
        synchronized (coverMisses) {
            if (coverMisses.contains(key)) return f;
        }
        coverBg.execute(() -> {
            if (isFinishing()) return;
            Bitmap c = Covers.get(getApplicationContext(), path, mtime, size);
            runOnUiThread(() -> {
                if (c == null) {
                    synchronized (coverMisses) {
                        coverMisses.add(key);
                    }
                    return;
                }
                covers.put(key, c);
                if (key.equals(img.getTag())) {
                    img.setImageBitmap(c);
                    img.setBackgroundColor(0xFFFAF8F2);
                }
            });
        });
        return f;
    }

    /** Thumbnail or type badge on the left; title, type, meta and an optional snippet on the right. */
    private View itemRow(View thumb, String title, String meta, CharSequence snippet, String type) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 72));
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 61)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.label(this, title);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(t);
        if (type != null) {
            TextView ty = Ui.meta(this, type);
            ty.setSingleLine(true);
            ty.setEllipsize(TextUtils.TruncateAt.END);
            ty.setTextColor(Ui.CREAM);
            text.addView(ty, Ui.full(this, 4));
        }
        TextView m = Ui.meta(this, meta);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        text.addView(m, Ui.full(this, 4));
        if (snippet != null) {
            TextView sn = Ui.body(this, "", false);
            sn.setTextSize(14);
            sn.setText(snippet);
            sn.setMaxLines(3);
            sn.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(sn, Ui.full(this, 6));
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(this, 14));
        row.addView(text, tl);
        return row;
    }

    /** File-type badge in place of a thumbnail: the extension on a document-shaped tile. */
    private TextView badge(String fileName) {
        String ext = fileName.isEmpty() ? "" : OfficeText.extension(fileName).toUpperCase(Locale.ROOT);
        TextView b = new TextView(this);
        b.setText(ext);
        b.setTypeface(Ui.medium(this));
        b.setTextSize(ext.length() > 3 ? 9.5f : 10.5f);
        b.setLetterSpacing(0.06f);
        b.setTextColor(Ui.CREAM);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.box(this, Ui.SURFACE, Ui.LINE, 4));
        return b;
    }

    private static void mark(Spannable s, int a, int b) {
        s.setSpan(new ForegroundColorSpan(Ui.CREAM), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new BackgroundColorSpan(0x40F4EBDD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String statusNote(int status) {
        switch (status) {
            case DeviceIndex.PENDING: return "waiting to index";
            case DeviceIndex.LOCKED: return "password-protected";
            case DeviceIndex.EMPTY: return "no text";
            case DeviceIndex.FAILED: return "unreadable";
            default: return null;
        }
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String describeParts(DeviceIndex.Hit h) {
        String unit = h.unit == null ? "page" : h.unit;
        if (unit.equals("section")) return Ui.when(h.mtime);
        String label = Character.toUpperCase(unit.charAt(0)) + unit.substring(1);
        StringBuilder sb = new StringBuilder(h.parts.size() == 1 ? label + " " : label + "s ");
        int shown = Math.min(4, h.parts.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append(h.parts.get(i));
        }
        if (h.parts.size() > shown) sb.append(" +").append(h.parts.size() - shown);
        return sb.toString();
    }

    static String folderOf(String path) {
        File parent = new File(path).getParentFile();
        if (parent == null) return "";
        String p = parent.getAbsolutePath();
        String root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        if (p.equals(root)) return "Phone storage";
        if (p.startsWith(root + "/")) return p.substring(root.length() + 1);
        return p;
    }

    private static String describePages(SearchCore.Hit hit) {
        if (hit.pages.isEmpty()) return "Title match";
        StringBuilder sb = new StringBuilder(hit.pages.size() == 1 ? "Page " : "Pages ");
        int shown = Math.min(5, hit.pages.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append(hit.pages.get(i));
        }
        if (hit.pages.size() > shown) sb.append(" and ").append(hit.pages.size() - shown).append(" more");
        return sb.toString();
    }

    // ------------------------------------------------------------------ actions

    private void open(Row r) {
        if (r.header != null) return;
        if (r.entry != null) {
            startActivity(DocReaderActivity.forPhone(this, r.entry.id, r.entry.path, r.entry.name, 0));
        } else if (r.phoneHit != null) {
            DeviceIndex.Hit h = r.phoneHit;
            int page = h.parts.isEmpty() || !"page".equals(h.unit) ? 0 : h.parts.get(0) - 1;
            startActivity(DocReaderActivity.forPhone(this, h.id, h.path, h.name, page));
        } else if (r.dir != null) {
            int page = r.hit != null ? r.hit.firstPage() - 1 : -1;
            startActivity(ViewerActivity.intentAt(this, r.dir, page));
        }
    }

    private void menu(Row r) {
        if (r.dir != null) {
            showDocMenu(r.dir);
            return;
        }
        final long id = r.entry != null ? r.entry.id : r.phoneHit != null ? r.phoneHit.id : -1;
        final String name = r.entry != null ? r.entry.name : r.phoneHit != null ? r.phoneHit.name : null;
        final String path = r.entry != null ? r.entry.path : r.phoneHit != null ? r.phoneHit.path : null;
        if (id < 0 || name == null) return;
        final List<String> items = new ArrayList<>();
        items.add("Open");
        items.add("View text");
        items.add("Open in another app");
        if (OfficeText.extension(name).equals("pdf")) items.add("Edit this PDF");
        if (path != null) items.add("Delete from phone");
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    switch (items.get(which)) {
                        case "Open":
                            startActivity(DocReaderActivity.forPhone(this, id, path, name, 0));
                            break;
                        case "View text":
                            showText(id, name);
                            break;
                        case "Open in another app":
                            PhoneFileProvider.open(this, id, name);
                            break;
                        case "Delete from phone":
                            PhoneFiles.delete(this, id, path, this::refresh);
                            break;
                        default:
                            PdfEditing.open(this, PhoneFileProvider.uriFor(id, name), name, path, null, 0);
                            break;
                    }
                })
                .show();
    }

    private void showText(final long id, final String name) {
        final String[] text = {""};
        Ui.task(this, () -> text[0] = DeviceIndex.get(this).text(id), () -> {
            TextView tv = Ui.body(this, text[0].isEmpty() ? "No text has been read from this file yet." : text[0], false);
            tv.setTextIsSelectable(true);
            tv.setTextSize(14);
            int pad = Ui.dp(this, 20);
            tv.setPadding(pad, pad, pad, pad);
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            new AlertDialog.Builder(this).setTitle(name).setView(sv).setPositiveButton("Close", null).show();
        }, null);
    }

    private void importPhoneFile(long id, String name) {
        int dot = name.lastIndexOf('.');
        final File dir = store.createDoc(dot > 0 ? name.substring(0, dot) : name);
        final List<Uri> uris = new ArrayList<>();
        uris.add(PhoneFileProvider.uriFor(id, name));
        Ui.toast(this, "Copying into Scans…");
        Ui.task(this, () -> ImageProcessor.importInto(getContentResolver(), uris, store, dir),
                () -> startActivity(ViewerActivity.intent(this, dir)),
                () -> Ui.task(this, () -> store.deleteDoc(dir), this::refresh, null));
    }

    private void showDocMenu(final File dir) {
        final boolean pinned = store.isPinned(dir);
        final String[] items = {"Open", "Rename", pinned ? "Unpin" : "Pin to top", "Move to folder\u2026",
                "Combine with other scans\u2026", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(Ui.displayTitle(store.getTitle(dir)))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            startActivity(ViewerActivity.intent(this, dir));
                            break;
                        case 1:
                            Ui.promptRename(this, store, dir, () -> {
                                refresh();
                                Indexer.kick(this);
                            });
                            break;
                        case 2:
                            store.setPinned(dir, !pinned);
                            refresh();
                            break;
                        case 3:
                            chooseFolder(dir);
                            break;
                        case 4:
                            combine(dir);
                            break;
                        default:
                            Ui.confirm(this, "Delete this document? It stays in the recycle bin for 30 days.",
                                    () -> Ui.task(this, () -> store.trashDoc(dir), this::refresh, null));
                            break;
                    }
                })
                .show();
    }

    /** Makes one new document from this scan and others, in the order they are ticked. */
    private void combine(final File first) {
        final List<File> docs = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (File d : store.listDocs()) {
            if (d.equals(first) || !AppLock.folderOpen(this, store.getFolder(d))) continue;
            docs.add(d);
            names.add(Ui.displayTitle(store.getTitle(d)) + "  (" + store.pages(d).length + ")");
        }
        if (docs.isEmpty()) {
            Ui.toast(this, "There are no other scans to combine with");
            return;
        }
        final List<File> picked = new ArrayList<>();
        new AlertDialog.Builder(this)
                .setTitle("Add after \u201C" + Ui.displayTitle(store.getTitle(first)) + "\u201D")
                .setMultiChoiceItems(names.toArray(new String[0]), null, (d, which, checked) -> {
                    if (checked) picked.add(docs.get(which));
                    else picked.remove(docs.get(which));
                })
                .setPositiveButton("Combine", (d, w) -> {
                    if (picked.isEmpty()) return;
                    final File target = store.createDoc(store.getTitle(first) + " (combined)");
                    final List<File> order = new ArrayList<>();
                    order.add(first);
                    order.addAll(picked);
                    Ui.task(this, () -> {
                        for (File src : order) for (File p : store.pages(src)) store.copyPage(p, target);
                    }, () -> {
                        Indexer.kick(this);
                        refresh();
                        startActivity(ViewerActivity.intent(this, target));
                        Ui.toast(this, "Combined into a new scan; the originals are unchanged");
                    }, () -> store.deleteDoc(target));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Long-press on a folder chip: lock or unlock it, or rename it. */
    private void folderMenu(final String name) {
        final boolean locked = AppLock.folderLocked(this, name);
        final String[] items = {locked ? "Remove the lock" : "Lock this folder", "Rename folder"};
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        String why = locked ? "Remove the lock from \u201C" + name + "\u201D" : "Lock \u201C" + name + "\u201D";
                        AppLock.authenticate(this, why, "Its scans will " + (locked ? "show everywhere again" :
                                "only show after unlocking with your fingerprint, face or screen lock"), () -> {
                            AppLock.setFolderLocked(this, name, !locked);
                            if (!locked && name.equalsIgnoreCase(folder)) folder = "";
                            scanBarKey = "";
                            refresh();
                        }, null);
                    } else {
                        renameFolder(name);
                    }
                })
                .show();
    }

    private void renameFolder(final String oldName) {
        final EditText input = Ui.input(this, "Folder name", false);
        input.setText(oldName);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(input, Ui.full(this, 0));
        new AlertDialog.Builder(this)
                .setTitle("Rename folder")
                .setView(box)
                .setPositiveButton("Rename", (d, w) -> {
                    final String n = input.getText().toString().trim();
                    if (n.isEmpty() || n.equals(oldName)) return;
                    final boolean wasLocked = AppLock.folderLocked(this, oldName);
                    Ui.task(this, () -> {
                        for (File doc : store.listDocs()) if (oldName.equalsIgnoreCase(store.getFolder(doc))) store.setFolder(doc, n);
                    }, () -> {
                        if (wasLocked) {
                            AppLock.setFolderLocked(this, oldName, false);
                            AppLock.setFolderLocked(this, n, true);
                            AppLock.markFolderOpen(n);
                        }
                        if (oldName.equalsIgnoreCase(folder)) folder = n;
                        scanBarKey = "";
                        refresh();
                    }, null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Files a document in an existing folder, a new one, or none. */
    private void chooseFolder(final File dir) {
        final List<String> names = store.folders();
        final String current = store.getFolder(dir);
        final List<String> items = new ArrayList<>(names);
        items.add("New folder\u2026");
        if (!current.isEmpty()) items.add("Take out of \u201C" + current + "\u201D");
        new AlertDialog.Builder(this)
                .setTitle("Move to folder")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which < names.size()) {
                        store.setFolder(dir, names.get(which));
                        refresh();
                    } else if (which == names.size()) {
                        final EditText name = Ui.input(this, "Folder name, e.g. Lab records", false);
                        LinearLayout box = new LinearLayout(this);
                        box.setOrientation(LinearLayout.VERTICAL);
                        int pad = Ui.dp(this, 20);
                        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
                        box.addView(name, Ui.full(this, 0));
                        new AlertDialog.Builder(this)
                                .setTitle("New folder")
                                .setView(box)
                                .setPositiveButton("Move", (d2, w2) -> {
                                    String n = name.getText().toString().trim();
                                    if (n.isEmpty()) return;
                                    store.setFolder(dir, n);
                                    refresh();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        store.setFolder(dir, "");
                        refresh();
                    }
                })
                .show();
    }

    /** What a list shows, as one string: equal strings mean nothing on screen would change. */
    private static String keyOf(List<Row> list) {
        StringBuilder sb = new StringBuilder();
        for (Row r : list) {
            if (r.header != null) sb.append('#').append(r.header);
            else if (r.dir != null) sb.append(r.dir.getName()).append(':').append(r.title).append(':').append(r.pages)
                    .append(':').append(r.modified).append(':').append(r.pinned).append(':').append(r.folder)
                    .append(':').append(r.thumb == null ? 0 : r.thumb.getGenerationId())
                    .append(':').append(r.hit == null ? "" : r.hit.snippet);
            else if (r.entry != null) sb.append(r.entry.id).append(':').append(r.entry.mtime).append(':').append(r.entry.status);
            else if (r.phoneHit != null) sb.append(r.phoneHit.id).append(':').append(r.phoneHit.snippet).append(':')
                    .append(r.phoneHit.parts);
            sb.append('|');
        }
        return sb.toString();
    }

    /** Scans tab: sort, then folder filters, then the recycle bin. */
    private void buildScanBar(List<String> folderNames, int inBin, int sortBy) {
        if (!folder.isEmpty() && !folderNames.contains(folder)) folder = "";
        StringBuilder lockState = new StringBuilder();
        for (String f : folderNames) lockState.append(AppLock.folderOpen(this, f) ? 'o' : 'l');
        String key = folderNames + "|" + inBin + "|" + sortBy + "|" + folder + "|" + lockState;
        if (key.equals(scanBarKey)) return;  // unchanged: keep the chips as they are
        scanBarKey = key;
        scanBar.removeAllViews();
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button sort = Ui.chip(this, "Sort: " + SORT_NAMES[sortBy]);
        sort.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Sort scans by")
                .setSingleChoiceItems(SORT_NAMES, sortBy, (d, which) -> {
                    Prefs.get(this).edit().putInt("scan_sort", which).apply();
                    d.dismiss();
                    refresh();
                })
                .show());
        addChip(row, sort);
        List<String> labels = new ArrayList<>();
        labels.add("All");
        labels.addAll(folderNames);
        for (final String f : labels) {
            final String value = f.equals("All") && labels.indexOf(f) == 0 ? "" : f;
            final boolean locked = !value.isEmpty() && AppLock.folderLocked(this, value);
            final boolean open = !locked || AppLock.folderOpen(this, value);
            Button c = Ui.chip(this, locked ? f + (open ? " \u00B7 unlocked" : " \u00B7 locked") : f);
            Ui.setChipSelected(this, c, value.equalsIgnoreCase(folder));
            c.setOnClickListener(v -> {
                if (!open) {
                    AppLock.authenticate(this, "Unlock \u201C" + value + "\u201D", null, () -> {
                        AppLock.markFolderOpen(value);
                        folder = value;
                        scanBarKey = "";
                        refresh();
                    }, null);
                    return;
                }
                folder = value;
                refresh();
            });
            if (!value.isEmpty()) {
                c.setOnLongClickListener(v -> {
                    folderMenu(value);
                    return true;
                });
            }
            addChip(row, c);
        }
        if (inBin > 0) {
            Button bin = Ui.chip(this, "Recycle bin \u00B7 " + inBin);
            bin.setOnClickListener(v -> startActivity(new Intent(this, TrashActivity.class)));
            addChip(row, bin);
        }
        scroll.addView(row);
        scanBar.addView(scroll, Ui.full(this, 4));
    }

    private void addChip(LinearLayout row, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 8));
        row.addView(b, lp);
    }

    private void startScan() {
        File dir = store.createDoc("Scan " + new SimpleDateFormat("d MMM, HH:mm", Locale.US).format(new Date()));
        startActivity(ViewerActivity.intentScan(this, dir));
    }

    // ------------------------------------------------------------------ lifecycle and status

    @Override
    protected void onResume() {
        super.onResume();
        StatusBus.add(this);
        updateStatus(StatusBus.current());
        selectTab(tab, false);
        Indexer.kick(this);
        FileIndexService.startIfNeeded(this, false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        StatusBus.remove(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdown();
        coverBg.shutdownNow();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PhoneFiles.REQ_STORAGE && PhoneFiles.hasAccess(this)) {
            FileIndexService.startIfNeeded(this, true);
            selectTab(tab, false);
        }
    }

    private long lastLiveRefresh;
    /** Lists are not rebuilt under a moving finger: changes wait until scrolling stops. */
    private boolean scrolling;
    private boolean refreshPending;
    private String shownKey = "";
    private String scanBarKey = "";

    @Override
    public void onStatus(String status) {
        updateStatus(status);
        long now = System.currentTimeMillis();
        boolean live = tab == TAB_PHONE && query.isEmpty() && rows.size() < PAGE && now - lastLiveRefresh > 1500;
        if (status == null || live) {
            lastLiveRefresh = now;
            if (scrolling) refreshPending = true;
            else refresh();
        }
    }

    private void updateStatus(String status) {
        statusView.setText(status != null ? status : "");
        statusView.setVisibility(status != null ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ data

    private Bitmap thumbFor(File dir) {
        File[] pages = store.pages(dir);
        if (pages.length == 0) return null;
        File f = pages[0];
        String key = f.getAbsolutePath() + ":" + f.lastModified() + ":" + f.length();
        Bitmap b = thumbs.get(key);
        if (b == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b != null) thumbs.put(key, b);
        }
        return b;
    }

    /** Loads the open section, and both tab counts, off the UI thread. */
    private void refresh() {
        final int gen = ++generation;
        final String q = query;
        final int t = tab;
        final int type = phoneType;
        final boolean phoneOn = PhoneFiles.enabled(this) && PhoneFiles.hasAccess(this);
        final String inFolder = folder;
        final int sortBy = Prefs.get(this).getInt("scan_sort", 0);
        bg.execute(() -> {
            List<Row> scans = new ArrayList<>();
            List<Row> titleHits = new ArrayList<>();
            int all = 0;
            for (File dir : store.listDocs()) {
                all++;
                Row r = new Row();
                r.dir = dir;
                r.title = store.getTitle(dir);
                r.folder = store.getFolder(dir);
                r.pinned = store.isPinned(dir);
                if (!inFolder.isEmpty() && !inFolder.equalsIgnoreCase(r.folder)) continue;
                // documents in a locked folder stay hidden (also from search) until it is unlocked
                if (!AppLock.folderOpen(this, r.folder)) continue;
                if (!q.isEmpty()) {
                    r.hit = SearchCore.match(r.title, SearchCore.pagesOf(Transcript.read(dir)), q);
                    if (r.hit == null) continue;
                }
                if (t == TAB_SCANS) {
                    r.pages = store.pages(dir).length;
                    r.modified = dir.lastModified();
                    r.thumb = thumbFor(dir);
                    if (sortBy == 3) r.size = DocStore.sizeOf(dir);
                }
                if (r.hit != null && r.hit.titleMatch) titleHits.add(r);
                else scans.add(r);
            }
            if (q.isEmpty() && t == TAB_SCANS) {
                java.util.Comparator<Row> cmp;
                switch (sortBy) {
                    case 1: cmp = (a, b) -> a.title.compareToIgnoreCase(b.title); break;
                    case 2: cmp = (a, b) -> Integer.compare(b.pages, a.pages); break;
                    case 3: cmp = (a, b) -> Long.compare(b.size, a.size); break;
                    default: cmp = (a, b) -> Long.compare(b.modified, a.modified); break;
                }
                java.util.Collections.sort(scans, cmp);
                List<Row> pinned = new ArrayList<>();
                for (Row r : scans) if (r.pinned) pinned.add(r);
                if (!pinned.isEmpty()) {
                    scans.removeAll(pinned);
                    Row h1 = new Row();
                    h1.header = "Pinned";
                    Row h2 = new Row();
                    h2.header = inFolder.isEmpty() ? "Documents" : inFolder;
                    List<Row> grouped = new ArrayList<>();
                    grouped.add(h1);
                    grouped.addAll(pinned);
                    if (!scans.isEmpty()) {
                        grouped.add(h2);
                        grouped.addAll(scans);
                    }
                    scans = grouped;
                }
            }
            scans.addAll(0, titleHits);
            final int nScans = all;
            final List<String> folderNames = store.folders();
            final int inBin = store.trashCount();

            List<Row> phone = new ArrayList<>();
            int nPhone = -1;
            boolean more = false;
            if (phoneOn) {
                DeviceIndex db = DeviceIndex.get(this);
                if (q.isEmpty()) {
                    nPhone = db.count(type);
                    if (t == TAB_PHONE) {
                        for (DeviceIndex.Entry e : db.browse(type, PAGE, 0)) {
                            Row r = new Row();
                            r.entry = e;
                            phone.add(r);
                        }
                        more = phone.size() < nPhone;
                    }
                } else {
                    for (DeviceIndex.Hit h : db.search(q, 200)) {
                        if (!DeviceIndex.matchesType(h.name, type)) continue;
                        Row r = new Row();
                        r.phoneHit = h;
                        phone.add(r);
                    }
                    nPhone = phone.size();
                }
            }
            final List<Row> shown = t == TAB_SCANS ? scans : phone;
            final int np = nPhone;
            final boolean hasMore = more;
            runOnUiThread(() -> {
                if (gen != generation) return;
                scanCount = nScans;
                buildScanBar(folderNames, inBin, sortBy);
                phoneCount = np;
                phoneMore = hasMore;
                updateTabLabels();
                // only redraw the list when something in it actually changed (no flicker while indexing)
                String key = keyOf(shown);
                if (!key.equals(shownKey) || rows.size() != shown.size()) {
                    shownKey = key;
                    rows.clear();
                    rows.addAll(shown);
                    adapter.notifyDataSetChanged();
                }
                updateEmpty(q);
            });
        });
    }

    private void loadMorePhone() {
        loadingMore = true;
        final int gen = generation;
        final int type = phoneType;
        final int have = rows.size();
        bg.execute(() -> {
            List<DeviceIndex.Entry> next = DeviceIndex.get(this).browse(type, PAGE, have);
            runOnUiThread(() -> {
                loadingMore = false;
                if (gen != generation || tab != TAB_PHONE) return;
                for (DeviceIndex.Entry e : next) {
                    Row r = new Row();
                    r.entry = e;
                    rows.add(r);
                }
                phoneMore = next.size() == PAGE;
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void updateEmpty(String q) {
        emptyButton.setVisibility(View.GONE);
        emptyButton.setOnClickListener(null);
        if (tab == TAB_SCANS) {
            if (q.isEmpty()) {
                emptyTitle.setText("No scans yet");
                emptyText.setText("Scan a page with the camera, or import a PDF or photos.");
            } else {
                emptyTitle.setText("No matching scans");
                emptyText.setText("Nothing in your scans mentions “" + q + "”. "
                        + "Pages still being indexed can't be found yet.");
            }
            return;
        }
        if (!PhoneFiles.enabled(this)) {
            emptyTitle.setText("Documents on this phone");
            emptyText.setText("Browse and search the PDF, Word, Excel and PowerPoint files already on your phone. "
                    + "Their text is indexed privately; the files are never changed or uploaded.");
            emptyButton.setText("Turn on");
            emptyButton.setVisibility(View.VISIBLE);
            emptyButton.setOnClickListener(v -> PhoneFiles.askToEnable(this, () -> selectTab(TAB_PHONE, false)));
        } else if (!PhoneFiles.hasAccess(this)) {
            emptyTitle.setText("Allow file access");
            emptyText.setText("PDF Scan needs permission to read files before it can list them here.");
            emptyButton.setText("Allow access");
            emptyButton.setVisibility(View.VISIBLE);
            emptyButton.setOnClickListener(v -> PhoneFiles.requestAccess(this));
        } else if (!q.isEmpty()) {
            emptyTitle.setText("No matching documents");
            emptyText.setText("No document on this phone mentions “" + q + "”"
                    + (FileIndexService.isRunning() ? " so far. Indexing is still running." : "."));
        } else if (FileIndexService.isRunning()) {
            emptyTitle.setText("Looking for documents…");
            emptyText.setText("They'll appear here as they're found.");
        } else {
            emptyTitle.setText("No documents found");
            emptyText.setText(phoneType == 0 ? "There are no supported documents on this phone yet."
                    : "No " + DeviceIndex.TYPE_LABELS[phoneType] + " files on this phone.");
            emptyButton.setText("Look again");
            emptyButton.setVisibility(View.VISIBLE);
            emptyButton.setOnClickListener(v -> FileIndexService.startIfNeeded(this, true));
        }
    }

    // ------------------------------------------------------------------ import

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_IMPORT || result != RESULT_OK || data == null) return;
        final List<Uri> uris = urisFrom(data);
        if (uris.isEmpty()) return;
        final File dir = store.createDoc(displayName(uris.get(0)) + (uris.size() > 1 ? " +" + (uris.size() - 1) : ""));
        Ui.toast(this, "Importing…");
        Ui.task(this, () -> ImageProcessor.importInto(getContentResolver(), uris, store, dir),
                () -> startActivity(ViewerActivity.intent(this, dir)),
                () -> Ui.task(this, () -> store.deleteDoc(dir), this::refresh, null));
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
            // fall back to a generic title
        }
        if (name == null) return "Imported";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
