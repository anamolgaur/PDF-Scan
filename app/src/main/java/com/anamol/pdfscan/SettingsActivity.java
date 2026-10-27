package com.anamol.pdfscan;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Scanning defaults, indexing, export size, and the optional internet / Google Drive link. */
public class SettingsActivity extends Activity implements StatusBus.Listener {
    private LinearLayout driveBox;
    private LinearLayout phoneBox;
    private LinearLayout trackBox;
    private TextView driveStatus;
    private LinearLayout langBox;
    private TextView spaceLine;
    private TextView binLine;
    private TextView diagLine;
    private static final int REQ_BACKUP = 61;
    private static final int REQ_RESTORE = 62;
    private static final int REQ_SAVE_MUSIC = 63;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = Ui.bare(this);
        page.addView(Ui.topBar(this, this::finish, Ui.title(this, "Settings")));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 32));
        scroll.addView(root);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(Ui.section(this, "Appearance"));
        Ui.chipRow(this, root, new String[]{"Maroon", "AMOLED black"}, Prefs.amoled(this) ? 1 : 0, i -> {
            boolean black = i == 1;
            if (black == Prefs.amoled(this)) return;
            Prefs.setAmoled(this, black);
            Ui.applyTheme(black);
            // rebuild every screen in the new look, coming back here
            startActivities(new android.content.Intent[]{
                    new android.content.Intent(this, MainActivity.class).addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    new android.content.Intent(this, SettingsActivity.class)});
            overridePendingTransition(0, 0);
        });
        root.addView(Ui.mono(this, "AMOLED black turns the background fully off on OLED screens: deep black with "
                + "cream text and maroon accents. Saves battery at night."), Ui.full(this, 4));

        root.addView(Ui.section(this, "Scanning"));
        root.addView(Ui.toggleWith(this, "Straighten and filter while capturing",
                "Off: the camera takes plain photos. Crop, filter and mark them up afterwards from the page preview.",
                Prefs.scanOnCapture(this), (b, on) -> Prefs.setScanOnCapture(this, on)));
        root.addView(Ui.mono(this, "Filter for scanned and imported pages"), Ui.full(this, 8));
        Ui.chipRow(this, root, ImageOps.FILTER_LABELS, Prefs.indexOf(ImageOps.FILTERS, Prefs.defaultFilter(this)),
                i -> Prefs.setDefaultFilter(this, ImageOps.FILTERS[i]));
        root.addView(toggle("Capture automatically when a page is steady", Prefs.autoCapture(this),
                (b, on) -> Prefs.setAutoCapture(this, on)), Ui.full(this, 8));

        root.addView(Ui.section(this, "Text"));
        root.addView(toggle("Recognise text in the background", Indexer.autoEnabled(this),
                (b, on) -> Indexer.setAutoEnabled(this, on)));
        root.addView(Ui.mono(this, "Needed for search and searchable PDFs. Runs on this phone."), Ui.full(this, 2));
        root.addView(Ui.mono(this, "Languages"), Ui.full(this, 14));
        langBox = new LinearLayout(this);
        langBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(langBox, Ui.full(this, 2));
        refreshLanguages();

        root.addView(Ui.section(this, "Library"));
        binLine = Ui.label(this, "");
        binLine.setMinHeight(Ui.dp(this, 48));
        binLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
        binLine.setOnClickListener(v -> startActivity(new android.content.Intent(this, TrashActivity.class)));
        root.addView(binLine);
        root.addView(Ui.mono(this, "Back up every scan, with its text, folders and your saved signatures, to one file. "
                + "Restoring adds the documents back; nothing in the app is replaced."), Ui.full(this, 6));
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
        root.addView(Ui.row(this, backup, restore), Ui.full(this, 8));

        root.addView(Ui.mono(this, "Original photos"), Ui.full(this, 16));
        spaceLine = Ui.meta(this, "");
        root.addView(spaceLine, Ui.full(this, 2));
        int[] keepDays = {0, 30, 90};
        int keepIdx = 0;
        for (int i = 0; i < keepDays.length; i++) if (keepDays[i] == Prefs.keepOriginalsDays(this)) keepIdx = i;
        Ui.chipRow(this, root, new String[]{"Keep always", "Remove after 30 days", "Remove after 90 days"}, keepIdx,
                i -> Prefs.setKeepOriginalsDays(this, keepDays[i]));
        root.addView(Ui.mono(this, "Each page keeps its original photo so crop and filters can be redone. "
                + "Removing old ones saves space; those pages can still be edited, starting from the finished page."),
                Ui.full(this, 4));
        Button freeUp = Ui.button(this, "Free up space now", false);
        freeUp.setOnClickListener(v -> Ui.confirm(this, "Remove the original photos of every page? Pages stay as they "
                + "are now and can still be edited, but crop and filter changes can't be undone back to the photo.", () -> {
                    final long[] freed = {0};
                    Ui.task(this, () -> freed[0] = new DocStore(this).pruneOriginals(0), () -> {
                        Ui.toast(this, "Freed " + MainActivity.size(freed[0]));
                        refreshSpace();
                    }, null);
                }));
        root.addView(freeUp, Ui.full(this, 8));

        root.addView(Ui.section(this, "Privacy"));
        root.addView(Ui.toggleWith(this, "Lock the app",
                "Unlock with your fingerprint, face or screen lock each time you open PDF Scan. "
                        + "Folders can also be locked on their own: long-press a folder on the home screen.",
                AppLock.appLockOn(this), (b, on) -> {
                    if (on == AppLock.appLockOn(this)) return;
                    AppLock.authenticate(this, on ? "Turn on the app lock" : "Turn off the app lock", null,
                            () -> AppLock.setAppLock(this, on), () -> b.setChecked(!on));
                }));
        root.addView(Ui.toggleWith(this, "Hide from recent apps and screenshots",
                "The app's screens show blank in the recent-apps view and can't be captured.",
                AppLock.secureScreens(this), (b, on) -> {
                    AppLock.setSecureScreens(this, on);
                    if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                    Ui.toast(this, "Applies to every screen from now on");
                }));

        root.addView(Ui.section(this, "Export"));
        root.addView(Ui.mono(this, "PDF size"));
        Ui.chipRow(this, root, Prefs.SIZE_LABELS, Prefs.indexOf(Prefs.SIZES, Prefs.exportSize(this)),
                i -> Prefs.setExportSize(this, Prefs.SIZES[i]));

        root.addView(Ui.section(this, DriveAccount.configured() ? "Internet and Google Drive" : "Internet"));
        root.addView(toggle("Allow internet access", Prefs.internet(this), (b, on) -> {
            Prefs.setInternet(this, on);
            refreshDrive();
        }));
        root.addView(Ui.mono(this, DriveAccount.configured()
                ? "Off: the app never connects to anything. On: used only for Google Drive, when you connect it, "
                + "and for downloading text languages."
                : "Off: the app never connects to anything. On: used only for downloading text languages."),
                Ui.full(this, 2));
        driveBox = new LinearLayout(this);
        driveBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(driveBox, Ui.full(this, 8));

        root.addView(Ui.section(this, "Phone files"));
        root.addView(toggle("Make documents on this phone searchable", PhoneFiles.enabled(this), (b, on) -> {
            if (on) {
                b.setChecked(false);
                explainPhoneIndex(b);
            } else {
                PhoneFiles.setEnabled(this, false);
                FileIndexService.stop(this);
                refreshPhone();
            }
        }));
        root.addView(Ui.mono(this, "Reads PDF, Word, Excel, PowerPoint, OpenDocument, RTF and text files so "
                + "library search finds them. Only their text is kept, privately on this phone."), Ui.full(this, 2));
        phoneBox = new LinearLayout(this);
        phoneBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(phoneBox, Ui.full(this, 8));

        root.addView(Ui.section(this, "Music"));
        root.addView(toggle("Background music", MusicPlayer.enabled(this),
                (b, on) -> MusicPlayer.setEnabled(this, on)));
        root.addView(Ui.toggleWith(this, "Keep playing after leaving the app",
                "Off: the music pauses when you leave and continues from the same spot when you come back. "
                        + "It keeps playing while you use the camera, pick files or open a document.",
                MusicPlayer.keepPlaying(this),
                (b, on) -> MusicPlayer.setKeepPlaying(this, on)));
        root.addView(Ui.toggleWith(this, "Play the whole journey",
                "Eight tracks in order, from the puzzle loop down into the castle and back, with crossfades. "
                        + "Off: repeat the chosen track", MusicPlayer.playAll(this),
                (b, on) -> MusicPlayer.setPlayAll(this, on)));

        LinearLayout vol = new LinearLayout(this);
        vol.setOrientation(LinearLayout.HORIZONTAL);
        vol.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView volLabel = Ui.body(this, "Volume", false);
        volLabel.setMinWidth(Ui.dp(this, 76));
        android.widget.SeekBar volBar = new android.widget.SeekBar(this);
        volBar.setMax(100);
        volBar.setProgress(Math.round(MusicPlayer.volume(this) * 100));
        volBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.CREAM));
        volBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.CREAM));
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
        vol.addView(volBar, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        root.addView(vol, Ui.full(this, 4));

        root.addView(Ui.meta(this, "TRACKS"), Ui.full(this, 14));
        int opens = MusicPlayer.opens(this);
        root.addView(Ui.meta(this, "Opened " + opens + (opens == 1 ? " time" : " times")
                + " \u00B7 the soundtrack carries on from where it stopped last time"), Ui.full(this, 2));
        trackBox = new LinearLayout(this);
        trackBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(trackBox, Ui.full(this, 4));
        refreshTracks();
        root.addView(Ui.meta(this, "Sixteen original tracks written for this app, 8-bit and gothic. "
                + "It pauses for calls and when another app plays sound."), Ui.full(this, 8));
        Button saveMusic = Ui.button(this, "Save the MP3s to a folder", false);
        saveMusic.setOnClickListener(v -> {
            MusicPlayer.holdForExternal();
            startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SAVE_MUSIC);
        });
        root.addView(saveMusic, Ui.full(this, 8));

        root.addView(Ui.section(this, "Diagnostics"));
        diagLine = Ui.mono(this, "");
        root.addView(diagLine);
        Button shareReport = Ui.button(this, "Share report", false);
        Button clearReport = Ui.button(this, "Clear", false);
        shareReport.setOnClickListener(v -> shareReport());
        clearReport.setOnClickListener(v -> {
            Diag.clear();
            refreshDiag();
            Ui.toast(this, "Problem log cleared");
        });
        root.addView(Ui.row(this, shareReport, clearReport), Ui.full(this, 8));

        root.addView(Ui.section(this, "About"));
        root.addView(Ui.mono(this, "PDF Scan " + BuildConfig.VERSION_NAME
                + "\nScans, text and signatures stay on this phone unless you export or upload them."
                + "\nOpen source: code under the Apache License 2.0, music under CC BY 4.0."
                + " \u00A9 2026 Anamol Gaur."));

        setContentView(page);
        refreshDrive();
        refreshPhone();
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatusBus.add(this);
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
        }, () -> spaceLine.setText("Scans use " + MainActivity.size(s[0]) + ", of which original photos "
                + MainActivity.size(s[1]) + "."), null);
    }

    private void refreshBin() {
        if (binLine == null) return;
        final DocStore store = new DocStore(this);
        int n = store.trashCount();
        binLine.setText(n == 0 ? "Recycle bin \u00B7 empty" : "Recycle bin \u00B7 " + n + (n == 1 ? " item" : " items"));
    }

    private void refreshDiag() {
        if (diagLine == null) return;
        int n = Diag.crashCount();
        diagLine.setText((n == 0 ? "No crashes recorded." : n + (n == 1 ? " crash" : " crashes") + " recorded.")
                + " Problems are logged privately on this phone. Share the report with whoever is fixing the app.");
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
            icon.setImageDrawable(Ui.tinted(this, on ? R.drawable.ic_play : R.drawable.ic_music, on ? Ui.CREAM : Ui.MUTED));
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView t = Ui.label(this, Soundtrack.TITLES[i]);
            t.setTextSize(15);
            t.setTextColor(on ? Ui.CREAM : Ui.INK);
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
