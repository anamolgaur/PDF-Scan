package com.anamol.pdfscan;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings, laid out like Vault's: a short main page of categories (each showing its current state
 * underneath), and one page per category. Every page is this same activity, opened with
 * EXTRA_PAGE naming the category; with no extra it shows the main page.
 *
 * Inside a page, settings sit in panels (see Ui.Panel): switches with a one-line explanation,
 * choices with the current value on the right and the options wrapping below, and plain rows.
 */
public class SettingsActivity extends Activity implements StatusBus.Listener {
    static final String EXTRA_PAGE = "page";
    private static final String P_APPEARANCE = "appearance", P_SCANNING = "scanning", P_TEXT = "text",
            P_EXPORT = "export", P_LIBRARY = "library", P_PHONE = "phone", P_DRIVE = "drive",
            P_PRIVACY = "privacy", P_MUSIC = "music", P_ABOUT = "about";

    private LinearLayout driveBox;
    private LinearLayout phoneBox;
    private LinearLayout trackBox;
    private TextView driveStatus;
    private LinearLayout langBox;
    private TextView spaceLine;
    private LinearLayout spaceMeterBox;
    private TextView binLine;
    private TextView diagLine;
    private String page;
    /** Main page: the state line under each category, refreshed in onResume. */
    private final java.util.Map<String, TextView> summaries = new java.util.HashMap<>();
    /** Main page: each category row's words, for the search box. */
    private final java.util.Map<View, String> searchable = new java.util.LinkedHashMap<>();
    private static final int REQ_BACKUP = 61;
    private static final int REQ_RESTORE = 62;
    private static final int REQ_SAVE_MUSIC = 63;

    static android.content.Intent open(android.content.Context c, String page) {
        return new android.content.Intent(c, SettingsActivity.class).putExtra(EXTRA_PAGE, page);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        page = getIntent().getStringExtra(EXTRA_PAGE);
        LinearLayout screen = Ui.bare(this);
        screen.addView(Ui.topBar(this, this::finish, Ui.title(this, titleOf(page))));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 32));
        scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (page == null) buildHome(root);
        else if (page.equals(P_APPEARANCE)) buildAppearance(root);
        else if (page.equals(P_SCANNING)) buildScanning(root);
        else if (page.equals(P_TEXT)) buildText(root);
        else if (page.equals(P_EXPORT)) buildExport(root);
        else if (page.equals(P_LIBRARY)) buildLibrary(root);
        else if (page.equals(P_PHONE)) buildPhone(root);
        else if (page.equals(P_DRIVE)) buildDrive(root);
        else if (page.equals(P_PRIVACY)) buildPrivacy(root);
        else if (page.equals(P_MUSIC)) buildMusic(root);
        else buildAbout(root);

        setContentView(screen);
        refreshDrive();
        refreshPhone();
    }

    private static String titleOf(String p) {
        if (p == null) return "Settings";
        switch (p) {
            case P_APPEARANCE: return "Appearance";
            case P_SCANNING: return "Scanning";
            case P_TEXT: return "Text recognition";
            case P_EXPORT: return "Export";
            case P_LIBRARY: return "Library and storage";
            case P_PHONE: return "Phone search";
            case P_DRIVE: return "Google Drive";
            case P_PRIVACY: return "Lock and privacy";
            case P_MUSIC: return "Music";
            default: return "About and help";
        }
    }

    /** Adds a panel to the page under an optional group title, and returns it for rows. */
    private Ui.Panel panel(LinearLayout root, String title) {
        if (title != null) root.addView(Ui.groupTitle(this, title));
        Ui.Panel p = new Ui.Panel(this);
        root.addView(p, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return p;
    }

    // ------------------------------------------------------------------ main page

    private void buildHome(LinearLayout root) {
        // Search: hides category rows whose words don't match, and whole groups left empty.
        EditText search = Ui.searchField(this, "Search settings");
        LinearLayout.LayoutParams sl = Ui.full(this, 4);
        sl.bottomMargin = Ui.dp(this, 4);
        root.addView(search, sl);

        Ui.Panel general = panel(root, "General");
        nav(general, P_APPEARANCE, R.drawable.ic_palette, "theme accent colour dark light amoled text size font corners compact");
        nav(general, P_SCANNING, R.drawable.ic_frame, "camera auto capture smart page finding ai straighten filter photo scan id qr");
        nav(general, P_TEXT, R.drawable.ic_text, "ocr languages hindi english handwriting recognise");
        nav(general, P_EXPORT, R.drawable.ic_export, "pdf size quality small balanced");
        Ui.Panel docs = panel(root, "Your documents");
        nav(docs, P_LIBRARY, R.drawable.ic_folder, "backup restore recycle bin trash storage space original photos");
        nav(docs, P_PHONE, R.drawable.ic_search, "phone files index word excel powerpoint search");
        if (DriveAccount.configured()) nav(docs, P_DRIVE, R.drawable.ic_cloud, "google drive upload sync cloud");
        Ui.Panel privacy = panel(root, "Privacy");
        nav(privacy, P_PRIVACY, R.drawable.ic_lock, "lock fingerprint pin recent screenshots internet network");
        Ui.Panel more = panel(root, "More");
        nav(more, P_MUSIC, R.drawable.ic_music, "music soundtrack volume tracks");
        nav(more, P_ABOUT, R.drawable.ic_info, "about version licence diagnostics crash report help");

        final Ui.Panel[] panels = {general, docs, privacy, more};
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (java.util.Map.Entry<View, String> en : searchable.entrySet()) {
                    en.getKey().setVisibility(q.isEmpty() || en.getValue().contains(q) ? View.VISIBLE : View.GONE);
                }
                // a panel's rules sit between rows: show a rule only when a visible row follows a
                // visible row, and hide a group whose rows are all hidden
                for (Ui.Panel p : panels) {
                    boolean any = false;
                    View lastRule = null;
                    for (int i = 0; i < p.getChildCount(); i++) {
                        View ch = p.getChildAt(i);
                        if (!searchable.containsKey(ch)) {
                            lastRule = ch;
                            ch.setVisibility(View.GONE);
                            continue;
                        }
                        if (ch.getVisibility() == View.VISIBLE) {
                            if (any && lastRule != null) lastRule.setVisibility(View.VISIBLE);
                            any = true;
                        }
                    }
                    p.setVisibility(any ? View.VISIBLE : View.GONE);
                    int idx = root.indexOfChild(p);
                    if (idx > 0) root.getChildAt(idx - 1).setVisibility(any ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    private void nav(Ui.Panel panel, String p, int icon, String keywords) {
        String title = titleOf(p);
        TextView sub = Ui.navRow(this, panel, icon, title, v -> startActivity(open(this, p)));
        summaries.put(p, sub);
        searchable.put(panel.getChildAt(panel.getChildCount() - 1), (title + " " + keywords).toLowerCase(java.util.Locale.ROOT));
    }

    /** The one-line state under each category on the main page. */
    private void refreshSummaries() {
        if (summaries.isEmpty()) return;
        set(P_APPEARANCE, Ui.THEME_LABELS[Math.max(0, Prefs.indexOf(Ui.THEMES, Prefs.theme(this)))] + ", "
                + Ui.ACCENT_LABELS[Math.max(0, Prefs.indexOf(Ui.ACCENTS, Prefs.accent(this)))] + " accent");
        set(P_SCANNING, "Auto capture " + (Prefs.autoCapture(this) ? "on" : "off")
                + ", smart page finding " + (Prefs.smartDetect(this) ? "on" : "off"));
        set(P_TEXT, String.join(", ", Languages.activeNames(this)));
        set(P_EXPORT, Prefs.SIZE_LABELS[Math.max(0, Prefs.indexOf(Prefs.SIZES, Prefs.exportSize(this)))] + ", searchable PDF");
        int keep = Prefs.keepOriginalsDays(this);
        final String keepText = keep == 0 ? "originals kept" : "originals kept " + keep + " days";
        set(P_LIBRARY, keepText);
        Ui.task(this, () -> {
            long[] r = new DocStore(this).spaceUsed();
            runOnUiThread(() -> set(P_LIBRARY, MainActivity.size(r[0]) + " used, " + keepText));
        }, null, null);
        set(P_PHONE, PhoneFiles.enabled(this) ? "On" : "Off");
        set(P_DRIVE, DriveAccount.connected(this) ? "Connected" : "Not connected");
        set(P_PRIVACY, "App lock " + (AppLock.appLockOn(this) ? "on" : "off")
                + ", internet " + (Prefs.internet(this) ? "on" : "off"));
        set(P_MUSIC, MusicPlayer.enabled(this) ? "On, " + Soundtrack.TITLES[Math.max(0, Math.min(Soundtrack.count() - 1,
                MusicService.currentTrack() >= 0 ? MusicService.currentTrack() : MusicPlayer.track(this)))] : "Off");
        set(P_ABOUT, "Version " + BuildConfig.VERSION_NAME + ", licences, diagnostics");
    }

    private void set(String p, String text) {
        TextView t = summaries.get(p);
        if (t != null) {
            t.setText(text);
            t.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ appearance

    private void buildAppearance(LinearLayout root) {
        // Theme: one small preview tile per theme ("Aa" in its ink on its paper)
        Ui.Panel themes = panel(root, "Theme");
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        int[][] look = {{0, 0}, {0xFF3A0200, 0xFFF4EBDD}, {0xFFF7F1E6, 0xFF6B0A0A}, {0xFFFFFFFF, 0xFF6B0A0A}, {0xFF000000, 0xFFEDEDED}};
        String current = Prefs.theme(this);
        for (int i = 0; i < Ui.THEMES.length; i++) {
            final String t = Ui.THEMES[i];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cell.setPadding(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 6));
            boolean sel = t.equals(current);
            android.graphics.drawable.GradientDrawable frame = Ui.box(this, 0x00000000, sel ? Ui.INK : Ui.LINE, Ui.RADIUS);
            if (sel) frame.setStroke(Ui.dp(this, 2), Ui.INK);
            cell.setBackground(frame);
            TextView chip = new TextView(this);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setTypeface(Ui.display(this));
            chip.setTextSize(Ui.sp(14));
            if (i == 0) {
                // Auto: half cream, half maroon
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFFF7F1E6, 0xFF3A0200});
                g.setCornerRadius(Ui.dp(this, Math.max(2, Ui.RADIUS - 2)));
                chip.setBackground(g);
            } else {
                chip.setText("Aa");
                chip.setTextColor(look[i][1]);
                chip.setBackground(Ui.box(this, look[i][0], Ui.LINE, Math.max(2, Ui.RADIUS - 2)));
            }
            cell.addView(chip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
            TextView name = Ui.meta(this, Ui.THEME_LABELS[i]);
            name.setTextColor(Ui.INK);
            name.setSingleLine(true);
            name.setPadding(0, Ui.dp(this, 6), 0, 0);
            cell.addView(name);
            cell.setContentDescription(Ui.THEME_LABELS[i] + " theme" + (sel ? ", selected" : ""));
            cell.setOnClickListener(v -> {
                if (t.equals(Prefs.theme(this))) return;
                Prefs.setTheme(this, t);
                restyle();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            tiles.addView(cell, lp);
        }
        themes.add(tiles);
        root.addView(Ui.panelNote(this, "Auto uses Cream by day and Maroon when your phone is in dark mode. "
                + "AMOLED turns the background fully off on OLED screens."));

        // Accent: colour dots, the selected one ringed; the name shows on the right
        Ui.Panel accents = panel(root, "Accent");
        Ui.Flow dots = new Ui.Flow(this, Ui.dp(this, 10), Ui.dp(this, 10));
        dots.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 12));
        int[] colors = {Ui.INK, 0xFF7A1C1C, 0xFFC62828, 0xFFD2561E, 0xFFC9971C, 0xFF2E7D4F, 0xFF2F5FA8, 0xFF6A4BAE};
        String acc = Prefs.accent(this);
        for (int i = 0; i < Ui.ACCENTS.length; i++) {
            final String a = Ui.ACCENTS[i];
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(colors[i]);
            boolean sel = a.equals(acc);
            g.setStroke(Ui.dp(this, sel ? 3 : 1), sel ? Ui.BG : Ui.LINE);
            // the ring around the selected dot: an outer oval in the ink colour
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            ring.setColor(sel ? Ui.INK : 0x00000000);
            dot.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{ring,
                    new android.graphics.drawable.InsetDrawable(g, Ui.dp(this, 2))}));
            dot.setContentDescription(Ui.ACCENT_LABELS[i] + " accent" + (sel ? ", selected" : ""));
            dot.setOnClickListener(v -> {
                if (a.equals(Prefs.accent(this))) return;
                Prefs.setAccent(this, a);
                restyle();
            });
            dots.addView(dot, new ViewGroup.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        }
        accents.add(dots);
        LinearLayout accRow = new LinearLayout(this);
        accRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        accRow.addView(Ui.label(this, "Accent"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        accRow.addView(Ui.meta(this, Ui.ACCENT_LABELS[Math.max(0, Prefs.indexOf(Ui.ACCENTS, acc))]));
        Ui.contentRow(this, accents, accRow);

        // Text and layout: each change rebuilds the screens so it shows everywhere at once
        Ui.Panel layout = panel(root, "Text and layout");
        Ui.choiceRow(this, layout, "Text size", Ui.TEXT_SIZE_LABELS, Math.max(0, Prefs.indexOf(Ui.TEXT_SIZES, Prefs.textSize(this))), i -> {
            Prefs.setTextSize(this, Ui.TEXT_SIZES[i]);
            restyle();
        });
        Ui.choiceRow(this, layout, "Font", Ui.FONT_LABELS, Math.max(0, Prefs.indexOf(Ui.FONTS, Prefs.font(this))), i -> {
            Prefs.setFont(this, Ui.FONTS[i]);
            restyle();
        });
        Ui.choiceRow(this, layout, "Corners", Ui.CORNER_LABELS, Math.max(0, Prefs.indexOf(Ui.CORNERS, Prefs.corners(this))), i -> {
            Prefs.setCorners(this, Ui.CORNERS[i]);
            restyle();
        });
        Ui.switchRow(this, layout, "Compact lists", "Fit more documents on the home screen", Prefs.compact(this), (b, on) -> {
            Prefs.setCompact(this, on);
            Ui.applyLayout(Prefs.textSize(this), Prefs.font(this), Prefs.corners(this), on);
        });
        root.addView(Ui.panelNote(this, "System, Serif and Mono use your phone's own fonts, which also cover every language."));
    }

    // ------------------------------------------------------------------ scanning

    private void buildScanning(LinearLayout root) {
        Ui.Panel camera = panel(root, "Camera");
        Ui.switchRow(this, camera, "Capture automatically", "Takes the photo when the page is steady",
                Prefs.autoCapture(this), (b, on) -> Prefs.setAutoCapture(this, on));
        Ui.switchRow(this, camera, "Smart page finding", "On-device AI finds the corners. Nothing is sent anywhere",
                Prefs.smartDetect(this), (b, on) -> Prefs.setSmartDetect(this, on));
        Ui.switchRow(this, camera, "Straighten while capturing", "Off: plain photos, crop and filter later",
                Prefs.scanOnCapture(this), (b, on) -> Prefs.setScanOnCapture(this, on));
        String[] modes = {"Photo", "Scan", "ID card", "QR"};
        int[] modeIds = {Prefs.CAMERA_PHOTO, Prefs.CAMERA_SCAN, Prefs.CAMERA_ID, Prefs.CAMERA_QR};
        int cur = 0;
        for (int i = 0; i < modeIds.length; i++) if (modeIds[i] == Prefs.cameraMode(this)) cur = i;
        Ui.choiceRow(this, camera, "Camera opens in", modes, cur, i -> Prefs.setCameraMode(this, modeIds[i]));

        Ui.Panel pages = panel(root, "New pages");
        Ui.choiceRow(this, pages, "Filter", ImageOps.FILTER_LABELS,
                Math.max(0, Prefs.indexOf(ImageOps.FILTERS, Prefs.defaultFilter(this))),
                i -> Prefs.setDefaultFilter(this, ImageOps.FILTERS[i]));
        Ui.switchRow(this, pages, "Recognise text in the background", "Makes new scans searchable. Runs on this phone",
                Indexer.autoEnabled(this), (b, on) -> Indexer.setAutoEnabled(this, on));
    }

    // ------------------------------------------------------------------ text recognition

    private void buildText(LinearLayout root) {
        Ui.Panel p = panel(root, "Languages");
        langBox = new LinearLayout(this);
        langBox.setOrientation(LinearLayout.VERTICAL);
        Ui.contentRow(this, p, langBox);
        refreshLanguages();
        root.addView(Ui.panelNote(this, "Indian languages, Chinese, Japanese, Korean and European languages are "
                + "one-time downloads. Higher-accuracy English for handwriting is in Choose languages."));
    }

    // ------------------------------------------------------------------ export

    private void buildExport(LinearLayout root) {
        Ui.Panel p = panel(root, "PDF");
        Ui.choiceRow(this, p, "Size", Prefs.SIZE_LABELS, Math.max(0, Prefs.indexOf(Prefs.SIZES, Prefs.exportSize(this))),
                i -> Prefs.setExportSize(this, Prefs.SIZES[i]));
        root.addView(Ui.panelNote(this, "High quality keeps your pages as they are. Balanced and Small make smaller "
                + "files; Small stores black-and-white pages as crisp 1-bit images. Every PDF has a searchable text layer."));
    }

    // ------------------------------------------------------------------ library and storage

    private void buildLibrary(LinearLayout root) {
        Ui.Panel lib = panel(root, "Library");
        binLine = Ui.label(this, "");
        TextView bin = Ui.navRow(this, lib, R.drawable.ic_trash, "Recycle bin",
                v -> startActivity(new android.content.Intent(this, TrashActivity.class)));
        binLine = bin;
        Button backup = Ui.button(this, "Back up", false);
        Button restore = Ui.button(this, "Restore", false);
        backup.setOnClickListener(v -> {
            String name = "PDF Scan backup " + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date()) + ".zip";
            MusicPlayer.holdForExternal();
            startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(android.content.Intent.EXTRA_TITLE, name), REQ_BACKUP);
        });
        restore.setOnClickListener(v -> {
            MusicPlayer.holdForExternal();
            startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[]{"application/zip",
                            "application/x-zip-compressed", "application/octet-stream"}), REQ_RESTORE);
        });
        LinearLayout backupBox = new LinearLayout(this);
        backupBox.setOrientation(LinearLayout.VERTICAL);
        backupBox.addView(Ui.label(this, "Backup"));
        TextView bsub = Ui.meta(this, "Every scan, its text, folders and your saved signatures in one file. "
                + "Restoring adds documents back; nothing is replaced.");
        backupBox.addView(bsub, Ui.full(this, 3));
        backupBox.addView(Ui.row(this, backup, restore), Ui.full(this, 10));
        Ui.contentRow(this, lib, backupBox);

        Ui.Panel storage = panel(root, "Storage");
        LinearLayout space = new LinearLayout(this);
        space.setOrientation(LinearLayout.VERTICAL);
        space.addView(Ui.label(this, "Original photos"));
        spaceLine = Ui.meta(this, "");
        space.addView(spaceLine, Ui.full(this, 3));
        spaceMeterBox = new LinearLayout(this);
        space.addView(spaceMeterBox, Ui.full(this, 8));
        Ui.contentRow(this, storage, space);
        int[] keepDays = {0, 30, 90};
        int keepIdx = 0;
        for (int i = 0; i < keepDays.length; i++) if (keepDays[i] == Prefs.keepOriginalsDays(this)) keepIdx = i;
        Ui.choiceRow(this, storage, "Keep originals", new String[]{"Always", "30 days", "90 days"}, keepIdx,
                i -> Prefs.setKeepOriginalsDays(this, keepDays[i]));
        Button freeUp = Ui.button(this, "Free up space now", false);
        freeUp.setOnClickListener(v -> Ui.confirm(this, "Remove the original photos of every page? Pages stay as they "
                + "are now and can still be edited, but crop and filter changes can't be undone back to the photo.", () -> {
                    final long[] freed = {0};
                    Ui.task(this, () -> freed[0] = new DocStore(this).pruneOriginals(0), () -> {
                        Ui.toast(this, "Freed " + MainActivity.size(freed[0]));
                        refreshSpace();
                    }, null);
                }));
        LinearLayout fb = new LinearLayout(this);
        fb.addView(freeUp);
        Ui.contentRow(this, storage, fb);
        root.addView(Ui.panelNote(this, "Each page keeps its original photo so crop and filters can be redone. "
                + "Removing old ones saves space; those pages can still be edited, starting from the finished page."));
    }

    // ------------------------------------------------------------------ phone search

    private void buildPhone(LinearLayout root) {
        Ui.Panel p = panel(root, null);
        Ui.switchRow(this, p, "Make documents on this phone searchable",
                "PDF, Word, Excel, PowerPoint, OpenDocument, RTF and text files. Only their text is kept, privately",
                PhoneFiles.enabled(this), (b, on) -> {
                    if (on) {
                        b.setChecked(false);
                        explainPhoneIndex(b);
                    } else {
                        PhoneFiles.setEnabled(this, false);
                        FileIndexService.stop(this);
                        refreshPhone();
                    }
                });
        phoneBox = new LinearLayout(this);
        phoneBox.setOrientation(LinearLayout.VERTICAL);
        Ui.contentRow(this, p, phoneBox);
    }

    // ------------------------------------------------------------------ google drive

    private void buildDrive(LinearLayout root) {
        Ui.Panel p = panel(root, null);
        driveBox = new LinearLayout(this);
        driveBox.setOrientation(LinearLayout.VERTICAL);
        Ui.contentRow(this, p, driveBox);
        root.addView(Ui.panelNote(this, "Google Drive needs internet access, which you can turn on in Lock and privacy."));
    }

    // ------------------------------------------------------------------ lock and privacy

    private void buildPrivacy(LinearLayout root) {
        Ui.Panel lock = panel(root, "Lock");
        Ui.switchRow(this, lock, "Lock the app",
                "Fingerprint, face or screen lock each time you open PDF Scan. Lock single folders by long-pressing them",
                AppLock.appLockOn(this), (b, on) -> {
                    if (on == AppLock.appLockOn(this)) return;
                    AppLock.authenticate(this, on ? "Turn on the app lock" : "Turn off the app lock", null,
                            () -> AppLock.setAppLock(this, on), () -> b.setChecked(!on));
                });
        Ui.switchRow(this, lock, "Hide from recent apps and screenshots",
                "Screens show blank in recent apps and can't be captured",
                AppLock.secureScreens(this), (b, on) -> {
                    AppLock.setSecureScreens(this, on);
                    if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    Ui.toast(this, "Applies to every screen from now on");
                });
        Ui.Panel net = panel(root, "Internet");
        Ui.switchRow(this, net, "Allow internet access", DriveAccount.configured()
                        ? "Off: the app never connects to anything. On: only for Google Drive and language downloads"
                        : "Off: the app never connects to anything. On: only for language downloads",
                Prefs.internet(this), (b, on) -> Prefs.setInternet(this, on));
        root.addView(Ui.panelNote(this, "No ads, no analytics, no crash uploads. Your scans stay on this phone "
                + "unless you export or upload them."));
    }

    // ------------------------------------------------------------------ music

    private void buildMusic(LinearLayout root) {
        Ui.Panel play = panel(root, "Playback");
        Ui.switchRow(this, play, "Background music", "Sixteen original tracks, 8-bit and gothic",
                MusicPlayer.enabled(this), (b, on) -> MusicPlayer.setEnabled(this, on));
        Ui.switchRow(this, play, "Keep playing after leaving the app",
                "Off: pauses when you leave and carries on from the same spot",
                MusicPlayer.keepPlaying(this), (b, on) -> MusicPlayer.setKeepPlaying(this, on));
        Ui.switchRow(this, play, "Play the whole journey", "Off: repeat the chosen track",
                MusicPlayer.playAll(this), (b, on) -> MusicPlayer.setPlayAll(this, on));
        LinearLayout vol = new LinearLayout(this);
        vol.setOrientation(LinearLayout.HORIZONTAL);
        vol.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView volLabel = Ui.label(this, "Volume");
        volLabel.setMinWidth(Ui.dp(this, 76));
        android.widget.SeekBar volBar = new android.widget.SeekBar(this);
        volBar.setMax(100);
        volBar.setProgress(Math.round(MusicPlayer.volume(this) * 100));
        volBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        volBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        volBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.LINE));
        volBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                MusicPlayer.setVolume(SettingsActivity.this, p / 100f);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
            }
        });
        vol.addView(volLabel);
        vol.addView(volBar, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        Ui.contentRow(this, play, vol);

        Ui.Panel tracks = panel(root, "Tracks");
        trackBox = new LinearLayout(this);
        trackBox.setOrientation(LinearLayout.VERTICAL);
        Ui.contentRow(this, tracks, trackBox);
        refreshTracks();
        int opens = MusicPlayer.opens(this);
        root.addView(Ui.panelNote(this, "Opened " + opens + (opens == 1 ? " time" : " times")
                + ". The soundtrack carries on from where it stopped, and pauses for calls and other apps' sound."));
        Button saveMusic = Ui.button(this, "Save the MP3s to a folder", false);
        saveMusic.setOnClickListener(v -> {
            MusicPlayer.holdForExternal();
            startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SAVE_MUSIC);
        });
        LinearLayout.LayoutParams sl = Ui.full(this, 12);
        sl.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        root.addView(saveMusic, sl);
    }

    // ------------------------------------------------------------------ about and help

    private void buildAbout(LinearLayout root) {
        Ui.Panel about = panel(root, "PDF Scan");
        LinearLayout ab = new LinearLayout(this);
        ab.setOrientation(LinearLayout.VERTICAL);
        ab.addView(Ui.label(this, "Version " + BuildConfig.VERSION_NAME));
        ab.addView(Ui.meta(this, "Scans, text and signatures stay on this phone unless you export or upload them. "
                + "Open source: code under the Apache License 2.0, music under CC BY 4.0. © 2026 Anamol Gaur."), Ui.full(this, 4));
        Ui.contentRow(this, about, ab);

        Ui.Panel diag = panel(root, "Diagnostics");
        LinearLayout db = new LinearLayout(this);
        db.setOrientation(LinearLayout.VERTICAL);
        diagLine = Ui.meta(this, "");
        db.addView(diagLine);
        Button shareReport = Ui.button(this, "Share report", false);
        Button clearReport = Ui.button(this, "Clear", false);
        shareReport.setOnClickListener(v -> shareReport());
        clearReport.setOnClickListener(v -> {
            Diag.clear();
            refreshDiag();
            Ui.toast(this, "Problem log cleared");
        });
        db.addView(Ui.row(this, shareReport, clearReport), Ui.full(this, 10));
        Ui.contentRow(this, diag, db);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatusBus.add(this);
        refreshSummaries();
        refreshLanguages();
        refreshBin();
        refreshSpace();
        refreshDiag();
        refreshDrive();
        if (PhoneFiles.enabled(this) && PhoneFiles.hasAccess(this)) FileIndexService.startIfNeeded(this, false);
        refreshPhone();
    }

    @Override
    protected void onPause() {
        super.onPause();
        StatusBus.remove(this);
    }

    @Override
    public void onStatus(String text) {
        if (driveStatus != null) driveStatus.setText(text != null ? text : "");
        refreshPhone();
        refreshTracks();
    }

    /** Rebuilds every screen in the new look, coming back here. */
    private void restyle() {
        Ui.applyTheme(Prefs.theme(this), Prefs.accent(this), Prefs.systemNight(this));
        Ui.applyLayout(Prefs.textSize(this), Prefs.font(this), Prefs.corners(this), Prefs.compact(this));
        startActivities(new android.content.Intent[]{
                new android.content.Intent(this, MainActivity.class).addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
                new android.content.Intent(this, SettingsActivity.class),
                open(this, page)});
        overridePendingTransition(0, 0);
    }

    private Switch toggle(String text, boolean on, CompoundButton.OnCheckedChangeListener l) {
        Switch s = Ui.toggle(this, text, null, on);
        s.setOnCheckedChangeListener(l);
        return s;
    }

    private void refreshDrive() {
        if (driveBox == null) return;
        driveBox.removeAllViews();
        if (!Prefs.internet(this)) {
            if (DriveAccount.connected(this)) {
                driveBox.addView(Ui.body(this, "Google Drive is connected as " + nameOf()
                        + " but paused while internet access is off.", true));
            }
            return;
        }
        if (!DriveAccount.configured()) return;  // this build has no Drive link: nothing to show
        if (!DriveAccount.connected(this)) {
            Button connect = Ui.button(this, "Connect Google Drive", true);
            connect.setOnClickListener(v -> {
                connect.setEnabled(false);
                connect.setText("Waiting for Google sign-in…");
                DriveAccount.connect(this, (email, error) -> {
                    if (error != null) Ui.toast(this, error);
                    else Ui.toast(this, "Connected" + (email != null ? " as " + email : ""));
                    refreshDrive();
                });
            });
            driveBox.addView(connect, Ui.full(this, 0));
            driveBox.addView(Ui.mono(this, "Opens Google sign-in in your browser. PDF Scan can only see the "
                    + "files it creates, in a folder called “PDF Scan”."), Ui.full(this, 6));
            return;
        }
        driveBox.addView(Ui.label(this, "Connected as " + nameOf()));
        driveBox.addView(toggle("Upload documents automatically", Prefs.driveAutoUpload(this), (b, on) -> {
            Prefs.setDriveAutoUpload(this, on);
            if (on) DriveSync.kickIfAuto(this);
        }), Ui.full(this, 6));
        driveBox.addView(toggle("Only on Wi-Fi or unmetered networks", Prefs.driveWifiOnly(this),
                (b, on) -> Prefs.setDriveWifiOnly(this, on)));
        driveBox.addView(Ui.mono(this, "Each document is uploaded as a searchable PDF plus its .txt "
                + "transcript, and updated when it changes."), Ui.full(this, 2));
        Button upload = Ui.button(this, "Upload all now", false);
        Button disconnect = Ui.button(this, "Disconnect", false);
        upload.setOnClickListener(v -> {
            DriveSync.kick(this, true);
            Ui.toast(this, "Uploading in the background");
        });
        disconnect.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("Disconnect Google Drive? Files already uploaded stay in your Drive.")
                .setPositiveButton("Disconnect", (d, w) -> {
                    DriveAccount.disconnect(this);
                    refreshDrive();
                })
                .setNegativeButton("Cancel", null)
                .show());
        driveBox.addView(Ui.row(this, upload, disconnect), Ui.full(this, 10));
        driveStatus = Ui.mono(this, StatusBus.current() != null ? StatusBus.current() : "");
        driveBox.addView(driveStatus, Ui.full(this, 6));
    }

    /** Explains what phone indexing does before asking Android for file access. */
    private void explainPhoneIndex(final CompoundButton toggle) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Search documents on this phone?")
                .setMessage("PDF Scan will read the PDF, Word, Excel, PowerPoint and text files stored on this "
                        + "phone and keep their text in a private index, so you can search them from the library. "
                        + "Files are never changed, moved or uploaded.\n\nAndroid will ask you to allow access "
                        + "to all files. Indexing then runs in the background, with a notification, until it is done.")
                .setPositiveButton("Continue", (d, w) -> {
                    PhoneFiles.setEnabled(this, true);
                    toggle.setOnCheckedChangeListener(null);
                    toggle.setChecked(true);
                    toggle.setOnCheckedChangeListener((b, on) -> {
                        if (!on) {
                            PhoneFiles.setEnabled(this, false);
                            FileIndexService.stop(this);
                            refreshPhone();
                        } else {
                            b.setChecked(false);
                            explainPhoneIndex(b);
                        }
                    });
                    if (android.os.Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 42);
                    }
                    if (PhoneFiles.hasAccess(this)) FileIndexService.startIfNeeded(this, true);
                    else PhoneFiles.requestAccess(this);
                    refreshPhone();
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void refreshSpace() {
        if (spaceLine == null) return;
        final long[] s = new long[2];
        Ui.task(this, () -> {
            long[] r = new DocStore(this).spaceUsed();
            s[0] = r[0];
            s[1] = r[1];
        }, () -> {
            spaceLine.setText(MainActivity.size(s[1]) + " of " + MainActivity.size(s[0]) + " used by scans");
            if (spaceMeterBox != null) {
                spaceMeterBox.removeAllViews();
                spaceMeterBox.addView(Ui.meter(this, s[0] > 0 ? s[1] / (float) s[0] : 0f),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6)));
            }
        }, null);
    }

    private void refreshBin() {
        if (binLine == null) return;
        final DocStore store = new DocStore(this);
        int n = store.trashCount();
        binLine.setText(n == 0 ? "Empty" : n + (n == 1 ? " item, kept 30 days" : " items, kept 30 days"));
        binLine.setVisibility(View.VISIBLE);
    }

    private void refreshDiag() {
        if (diagLine == null) return;
        int n = Diag.crashCount();
        diagLine.setText((n == 0 ? "No crashes recorded." : n + (n == 1 ? " crash" : " crashes") + " recorded.")
                + " Problems are logged privately on this phone, without file names. Share the report only if you want to.");
    }

    private void shareReport() {
        try {
            ShareProvider.clear(this);
            java.io.File f = new java.io.File(ShareProvider.dir(this), "pdf-scan-report.txt");
            java.nio.file.Files.write(f.toPath(), Diag.report(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_STREAM, ShareProvider.uriFor(f))
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "PDF Scan problem report")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri("report", ShareProvider.uriFor(f)));
            MusicPlayer.holdForExternal();
            startActivity(android.content.Intent.createChooser(send, "Share report"));
        } catch (java.io.IOException e) {
            Ui.toast(this, "Couldn't make the report: " + e.getMessage());
        }
    }

    /** Which languages pages are read in, and the way to change them. */
    private void refreshLanguages() {
        if (langBox == null) return;
        langBox.removeAllViews();
        TextView line = Ui.label(this, "Reading in: " + String.join(", ", Languages.activeNames(this)));
        line.setMinHeight(Ui.dp(this, 48));
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        langBox.addView(line);
        langBox.addView(Ui.meta(this, "Indian languages, Chinese, Japanese, Korean and European languages "
                + "are one-time downloads."));
        Button manage = Ui.button(this, "Choose languages", false);
        manage.setOnClickListener(v -> startActivity(new android.content.Intent(this, LanguagesActivity.class)));
        langBox.addView(manage, Ui.full(this, 6));
    }

    @Override
    protected void onActivityResult(int req, int result, android.content.Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        final DocStore store = new DocStore(this);
        final java.io.File sig = new java.io.File(getFilesDir(), "signatures.json");
        final int[] n = {0};
        if (req == REQ_SAVE_MUSIC) {
            Ui.task(this, () -> {
                android.content.ContentResolver cr = getContentResolver();
                android.net.Uri folder = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri,
                        android.provider.DocumentsContract.getTreeDocumentId(uri));
                for (int i = 0; i < Mp3Loops.count(this); i++) {
                    String name = Mp3Loops.file(this, i);
                    android.net.Uri f = android.provider.DocumentsContract.createDocument(cr, folder, "audio/mpeg", name);
                    if (f == null) throw new java.io.IOException("Cannot create files in that folder");
                    try (java.io.InputStream in = getAssets().open("music/" + name);
                         java.io.OutputStream out = cr.openOutputStream(f, "wt")) {
                        if (out == null) throw new java.io.IOException("Cannot write there");
                        byte[] buf = new byte[65536];
                        int k;
                        while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                    }
                    n[0]++;
                }
            }, () -> Ui.toast(this, "Saved " + n[0] + " tracks"), null);
        } else if (req == REQ_BACKUP) {
            Ui.toast(this, "Backing up\u2026");
            Ui.task(this, () -> {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new java.io.IOException("Cannot write there");
                    synchronized (Indexer.LOCK) {
                        n[0] = Backup.export(store.root(), sig, out);
                    }
                }
            }, () -> Ui.toast(this, "Backed up " + n[0] + (n[0] == 1 ? " document" : " documents")), null);
        } else if (req == REQ_RESTORE) {
            Ui.toast(this, "Restoring\u2026");
            Ui.task(this, () -> {
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("Cannot read that file");
                    synchronized (Indexer.LOCK) {
                        n[0] = Backup.restore(in, store.root(), sig);
                    }
                }
            }, () -> {
                Indexer.kick(this);
                Ui.toast(this, "Restored " + n[0] + (n[0] == 1 ? " document" : " documents"));
            }, null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PhoneFiles.REQ_STORAGE && PhoneFiles.hasAccess(this)) FileIndexService.startIfNeeded(this, true);
        refreshPhone();
    }

    private void refreshPhone() {
        if (phoneBox == null) return;
        phoneBox.removeAllViews();
        if (!PhoneFiles.enabled(this)) return;
        if (!PhoneFiles.hasAccess(this)) {
            phoneBox.addView(Ui.body(this, "Waiting for file access.", true));
            Button allow = Ui.button(this, "Allow access", true);
            allow.setOnClickListener(v -> PhoneFiles.requestAccess(this));
            phoneBox.addView(allow, Ui.full(this, 6));
            return;
        }
        int[] c = DeviceIndex.get(this).counts();
        String line = String.format(java.util.Locale.US, "%,d documents found \u00B7 %,d indexed", c[0], c[1])
                + (c[2] > 0 ? String.format(java.util.Locale.US, " \u00B7 %,d waiting", c[2]) : "");
        phoneBox.addView(Ui.mono(this, line));
        boolean running = FileIndexService.isRunning();
        Button go = Ui.button(this, running ? "Stop" : "Index now", !running);
        go.setOnClickListener(v -> {
            if (FileIndexService.isRunning()) FileIndexService.stop(this);
            else FileIndexService.startIfNeeded(this, true);
            phoneBox.postDelayed(this::refreshPhone, 600);
        });
        Button clear = Ui.button(this, "Clear index", false);
        clear.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("Remove all phone documents from search? Your files stay where they are.")
                .setPositiveButton("Clear", (d, w) -> {
                    FileIndexService.stop(this);
                    new Thread(() -> {
                        DeviceIndex.get(this).clear();
                        Prefs.get(this).edit().remove("phone_index_scan").apply();
                        runOnUiThread(this::refreshPhone);
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show());
        phoneBox.addView(Ui.row(this, go, clear), Ui.full(this, 8));
    }

    private void refreshTracks() {
        if (trackBox == null) return;
        trackBox.removeAllViews();
        int current = MusicService.currentTrack() >= 0 ? MusicService.currentTrack() : MusicPlayer.track(this);
        for (int i = 0; i < Soundtrack.count(); i++) {
            final int idx = i;
            boolean on = i == current;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(this, 56));
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Ui.RIPPLE), null,
                    new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageDrawable(Ui.tinted(this, on ? R.drawable.ic_play : R.drawable.ic_music, on ? Ui.ACCENT : Ui.MUTED));
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView t = Ui.label(this, Soundtrack.TITLES[i]);
            t.setTextSize(15);
            t.setTextColor(on ? Ui.ACCENT : Ui.INK);
            text.addView(t);
            text.addView(Ui.meta(this, Soundtrack.MOODS[i]), Ui.full(this, 2));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tl.setMarginStart(Ui.dp(this, 14));
            row.addView(text, tl);
            row.setOnClickListener(v -> {
                if (!MusicPlayer.enabled(this)) MusicPlayer.setEnabled(this, true);
                MusicPlayer.play(this, idx);
                Prefs.get(this).edit().putInt("music_track", idx).apply();
                trackBox.postDelayed(this::refreshTracks, 150);
            });
            trackBox.addView(row);
        }
    }

    private String nameOf() {
        String e = DriveAccount.email(this);
        return e != null ? e : "your Google account";
    }
}
