package com.anamol.pdfscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Edits one page in four steps: Crop (corners, rotate), Adjust (filter, glare, brightness,
 * contrast, sharpen), Clean up (eraser) and Mark up (pen, highlighter, shapes, signatures).
 * A new page arrives as an original photo file; an existing page is re-rendered from its original.
 */
public class PageEditorActivity extends Activity implements PreviewView.Listener {
    static final String EXTRA_NEXT = "next";
    private static final String EXTRA_DIR = "dir";
    private static final String EXTRA_PAGE = "page";
    private static final String EXTRA_ORIGINAL = "original";
    private static final String EXTRA_FROM_CAMERA = "camera";
    private static final String EXTRA_HINT = "hint";
    static final String EXTRA_TAB = "tab";
    static final String EXTRA_TOOL = "tool";
    static final int TAB_CROP = 0, TAB_ADJUST = 1, TAB_CLEAN = 2, TAB_MARK = 3;
    private static final int PREVIEW_MAX = 1300;

    private static final String[] TABS = {"Crop", "Adjust", "Clean up", "Mark up"};
    private static final String[] TOOLS = {PageMeta.Mark.PEN, PageMeta.Mark.HIGHLIGHT, PageMeta.Mark.RECT,
            PageMeta.Mark.OVAL, PageMeta.Mark.ARROW, PageMeta.Mark.SIGNATURE};
    private static final String[] TOOL_LABELS = {"Pen", "Highlight", "Box", "Circle", "Arrow", "Signature"};
    private static final int[] COLORS = {0xFF111111, 0xFF1F4FB5, 0xFFC0262D};
    private static final String[] COLOR_LABELS = {"Black", "Blue", "Red"};

    static Intent forNew(Context c, File dir, File original, boolean fromCamera, float[] hintCorners) {
        Intent i = new Intent(c, PageEditorActivity.class)
                .putExtra(EXTRA_DIR, dir.getAbsolutePath())
                .putExtra(EXTRA_ORIGINAL, original.getAbsolutePath())
                .putExtra(EXTRA_FROM_CAMERA, fromCamera);
        if (hintCorners != null) i.putExtra(EXTRA_HINT, hintCorners);
        return i;
    }

    /** Opens a page at one tab, optionally with a mark-up tool picked (e.g. the signature). */
    static Intent forEdit(Context c, File dir, File page, int tab, String tool) {
        Intent i = forEdit(c, dir, page).putExtra(EXTRA_TAB, tab);
        if (tool != null) i.putExtra(EXTRA_TOOL, tool);
        return i;
    }

    static Intent forEdit(Context c, File dir, File page) {
        return new Intent(c, PageEditorActivity.class)
                .putExtra(EXTRA_DIR, dir.getAbsolutePath())
                .putExtra(EXTRA_PAGE, page.getAbsolutePath());
    }

    private DocStore store;
    private File dir;
    private File page;
    private File original;
    private PageMeta meta;
    private Bitmap base;          // original scaled for screen, rotated by meta.rotation
    private Bitmap geom;          // base cropped and flattened (preview size)
    private boolean geomStale = true;
    private boolean busy = true;
    private int tab;
    private int generation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable renderLook = this::renderLook;
    private final Runnable renderGeometry = this::rebuildGeometry;

    private CropView cropView;
    private PreviewView preview;
    private LinearLayout controls;
    private Button saveBtn;
    private Button nextBtn;
    private Button edgesBtn;
    private TextView working;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DocStore(this);
        Intent in = getIntent();
        dir = new File(in.getStringExtra(EXTRA_DIR));
        String p = in.getStringExtra(EXTRA_PAGE);
        String o = in.getStringExtra(EXTRA_ORIGINAL);
        page = p != null ? new File(p) : null;
        original = o != null ? new File(o) : null;
        boolean fromCamera = in.getBooleanExtra(EXTRA_FROM_CAMERA, false);
        final float[] hint = in.getFloatArrayExtra(EXTRA_HINT);
        final int startTab = Math.max(0, Math.min(TABS.length - 1, in.getIntExtra(EXTRA_TAB, 0)));
        final String startTool = in.getStringExtra(EXTRA_TOOL);

        LinearLayout root = Ui.screen(this);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));

        Button cancel = Ui.button(this, "Cancel", false);
        saveBtn = Ui.button(this, "Save", true);
        cancel.setOnClickListener(v -> cancel());
        saveBtn.setOnClickListener(v -> save(false));
        if (fromCamera) {
            nextBtn = Ui.button(this, "Save + next", false);
            nextBtn.setOnClickListener(v -> save(true));
            root.addView(Ui.row(this, cancel, nextBtn, saveBtn));
        } else {
            root.addView(Ui.row(this, cancel, saveBtn));
        }

        Ui.chipRow(this, root, TABS, startTab, this::showTab);

        FrameLayout stage = new FrameLayout(this);
        cropView = new CropView(this);
        preview = new PreviewView(this);
        preview.listener = this;
        preview.setVisibility(View.GONE);
        working = Ui.mono(this, "Loading…");
        stage.addView(cropView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.gravity = android.view.Gravity.CENTER;
        stage.addView(working, wl);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        root.addView(stage, sl);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        // the tools scroll when there are many, so the page always keeps most of the screen
        final int maxControls = Math.round(getResources().getDisplayMetrics().heightPixels * 0.42f);
        android.widget.ScrollView controlsScroll = new android.widget.ScrollView(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxControls, MeasureSpec.AT_MOST));
            }
        };
        controlsScroll.addView(controls);
        root.addView(controlsScroll, Ui.full(this, 0));
        setContentView(root);
        setButtonsEnabled(false);

        Ui.task(this, () -> {
            File source = page != null ? DocStore.srcFile(page) : original;
            if (page != null && !source.exists()) source = page;  // page without an original yet
            Bitmap b = ImageProcessor.decodeFile(source, 1600);
            PageMeta m;
            if (page != null) {
                m = PageMeta.read(page);
            } else {
                m = new PageMeta();
                m.filter = Prefs.defaultFilter(this);
                m.corners = ImageProcessor.detectCorners(b);
                if (m.corners == null) m.corners = hint;
            }
            Bitmap shown = rotated(b, m.rotation);
            if (shown != b) b.recycle();
            meta = m;
            base = shown;
        }, () -> {
            busy = false;
            working.setVisibility(View.GONE);
            cropView.setImage(base, meta.corners);
            cropView.setOnClickListener(v -> {
                geomStale = true;
                updateEdgesLabel();
            });
            preview.erase = meta.erase;
            preview.marks = meta.marks;
            setButtonsEnabled(true);
            if (startTool != null) preview.tool = startTool;
            showTab(startTab);
            if (startTab == TAB_MARK && PageMeta.Mark.SIGNATURE.equals(startTool)) chooseSignature();
        }, this::cancel);
    }

    private void setButtonsEnabled(boolean on) {
        saveBtn.setEnabled(on);
        if (nextBtn != null) nextBtn.setEnabled(on);
    }

    private static Bitmap rotated(Bitmap b, int degrees) {
        if (degrees % 360 == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    // ------------------------------------------------------------------ tabs

    private void showTab(int t) {
        if (meta == null) return;
        if (tab == 0 && t != 0) {
            float[] c = cropView.getCorners();
            meta.corners = ScanMath.isFull(c) ? null : c;
        }
        tab = t;
        controls.removeAllViews();
        cropView.setVisibility(t == 0 ? View.VISIBLE : View.GONE);
        preview.setVisibility(t == 0 ? View.GONE : View.VISIBLE);
        preview.mode = t == 2 ? PreviewView.MODE_ERASE : (t == 3 ? PreviewView.MODE_MARK : PreviewView.MODE_VIEW);
        preview.clearSelection();
        switch (t) {
            case 0:
                buildCrop();
                break;
            case 1:
                buildAdjust();
                break;
            case 2:
                buildCleanup();
                break;
            default:
                buildMarkup();
                break;
        }
        if (t != 0) {
            if (geomStale || geom == null) rebuildGeometry();
            else scheduleLook(0);
        }
    }

    private void buildCrop() {
        controls.addView(Ui.mono(this, "Drag the corners to the edges of the page."), Ui.full(this, 4));
        Button rotate = Ui.button(this, "Rotate", false);
        edgesBtn = Ui.button(this, "Full page", false);
        rotate.setOnClickListener(v -> rotate());
        edgesBtn.setOnClickListener(v -> toggleEdges());
        controls.addView(Ui.row(this, rotate, edgesBtn), Ui.full(this, 8));
        updateEdgesLabel();
    }

    private void buildAdjust() {
        int sel = Prefs.indexOf(ImageOps.FILTERS, meta.filter);
        Ui.chipRow(this, controls, ImageOps.FILTER_LABELS, sel, i -> {
            meta.filter = ImageOps.FILTERS[i];
            scheduleLook(0);
        });
        controls.addView(slider("Brightness", meta.brightness, true, v -> meta.brightness = v), Ui.full(this, 6));
        controls.addView(slider("Contrast", meta.contrast, true, v -> meta.contrast = v), Ui.full(this, 2));
        controls.addView(slider("Sharpen", meta.sharpen, false, v -> meta.sharpen = v), Ui.full(this, 2));
        Switch glare = new Switch(this);
        glare.setText("Reduce glare");
        glare.setTextColor(Ui.INK);
        glare.setTextSize(15);
        glare.setMinHeight(Ui.dp(this, 48));
        glare.setChecked(meta.glare);
        glare.setOnCheckedChangeListener((b, on) -> {
            meta.glare = on;
            scheduleLook(0);
        });
        Button reset = Ui.chip(this, "Reset");
        reset.setOnClickListener(v -> {
            meta.brightness = 0;
            meta.contrast = 0;
            meta.sharpen = 0;
            meta.glare = false;
            showTab(1);
        });
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.addView(glare, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(reset);
        controls.addView(r, Ui.full(this, 2));

        // pages photographed in an open book bend near the binding
        controls.addView(Ui.mono(this, "Book page"), Ui.full(this, 10));
        controls.addView(slider("Curve", meta.curve, true, v -> {
            meta.curve = v;
            handler.removeCallbacks(renderGeometry);
            handler.postDelayed(renderGeometry, 250);
        }), Ui.full(this, 2));
        Ui.chipRow(this, controls, new String[]{"Spine on the right", "Spine on the left"}, meta.spineLeft ? 1 : 0, i -> {
            meta.spineLeft = i == 1;
            if (meta.curve != 0f) {
                handler.removeCallbacks(renderGeometry);
                handler.post(renderGeometry);
            }
        });
    }

    interface FloatSetter {
        void set(float v);
    }

    /** A labelled slider. Two-sided sliders run -1..1 (centre = unchanged); one-sided run 0..1. */
    private View slider(String name, float value, boolean twoSided, final FloatSetter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView label = Ui.mono(this, name);
        label.setMinWidth(Ui.dp(this, 92));
        final TextView val = Ui.mono(this, "");
        val.setMinWidth(Ui.dp(this, 36));
        val.setGravity(android.view.Gravity.END);
        SeekBar bar = new SeekBar(this);
        bar.setMax(200);
        bar.setMinimumHeight(Ui.dp(this, 44));
        int progress = twoSided ? Math.round((value + 1f) * 100f) : Math.round(value * 200f);
        bar.setProgress(progress);
        val.setText(display(progress, twoSided));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                setter.set(twoSided ? p / 100f - 1f : p / 200f);
                val.setText(display(p, twoSided));
                scheduleLook(120);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        row.addView(label);
        row.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        row.addView(val);
        return row;
    }

    private static String display(int progress, boolean twoSided) {
        int v = twoSided ? progress - 100 : progress / 2;
        return (twoSided && v > 0 ? "+" : "") + v;
    }

    private void buildCleanup() {
        controls.addView(Ui.mono(this, "Paint over smudges, stains or marks to erase them."), Ui.full(this, 4));
        controls.addView(slider("Brush", (preview.brush - 0.01f) / 0.11f, false, v -> {
            preview.brush = 0.01f + v * 0.11f;
        }), Ui.full(this, 2));
        Button undo = Ui.button(this, "Undo", false);
        Button clear = Ui.button(this, "Clear all", false);
        undo.setOnClickListener(v -> {
            if (!meta.erase.isEmpty()) {
                meta.erase.remove(meta.erase.size() - 1);
                scheduleLook(0);
            }
        });
        clear.setOnClickListener(v -> {
            meta.erase.clear();
            scheduleLook(0);
        });
        controls.addView(Ui.row(this, undo, clear), Ui.full(this, 6));
    }

    private void buildMarkup() {
        int toolIdx = Prefs.indexOf(TOOLS, preview.tool);
        Ui.chipRow(this, controls, TOOL_LABELS, toolIdx, i -> {
            preview.tool = TOOLS[i];
            preview.clearSelection();
            if (PageMeta.Mark.SIGNATURE.equals(TOOLS[i])) chooseSignature();
        });
        int colorIdx = 0;
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == preview.color) colorIdx = i;
        LinearLayout rowWrap = new LinearLayout(this);
        rowWrap.setOrientation(LinearLayout.VERTICAL);
        Ui.chipRow(this, rowWrap, new String[]{COLOR_LABELS[0], COLOR_LABELS[1], COLOR_LABELS[2]}, colorIdx,
                i -> preview.color = COLORS[i]);
        Ui.chipRow(this, rowWrap, new String[]{"Thin", "Medium", "Thick"},
                preview.penWidth < 0.003f ? 0 : (preview.penWidth > 0.005f ? 2 : 1),
                i -> preview.penWidth = i == 0 ? 0.0022f : (i == 1 ? 0.004f : 0.007f));
        controls.addView(rowWrap);
        Button undo = Ui.button(this, "Undo", false);
        Button clear = Ui.button(this, "Clear all", false);
        undo.setOnClickListener(v -> {
            if (!meta.marks.isEmpty()) {
                meta.marks.remove(meta.marks.size() - 1);
                preview.clearSelection();
                preview.invalidate();
            }
        });
        clear.setOnClickListener(v -> {
            meta.marks.clear();
            preview.clearSelection();
            preview.invalidate();
        });
        controls.addView(Ui.row(this, undo, clear), Ui.full(this, 6));
    }

    // ------------------------------------------------------------------ signatures

    private void chooseSignature() {
        final List<Signatures.Sig> saved = Signatures.load(this);
        if (saved.isEmpty()) {
            drawSignature(saved);
            return;
        }
        List<String> items = new ArrayList<>();
        for (int i = 0; i < saved.size(); i++) items.add("Signature " + (i + 1));
        items.add("Draw a new signature");
        items.add("Delete saved signatures");
        new AlertDialog.Builder(this)
                .setTitle("Signature")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which < saved.size()) {
                        preview.pendingSignature = saved.get(which);
                        Ui.toast(this, "Tap the page to place it. Drag to move, pinch to resize.");
                    } else if (which == saved.size()) {
                        drawSignature(saved);
                    } else {
                        Ui.confirm(this, "Delete all saved signatures from this phone?", () -> {
                            try {
                                Signatures.save(this, new ArrayList<>());
                            } catch (java.io.IOException e) {
                                Ui.toast(this, "Couldn't delete: " + e.getMessage());
                            }
                        });
                    }
                })
                .show();
    }

    private void drawSignature(final List<Signatures.Sig> saved) {
        final SignaturePad pad = new SignaturePad(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        wrap.setPadding(p, Ui.dp(this, 8), p, 0);
        wrap.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 200)));
        wrap.addView(Ui.mono(this, "Sign above the line. Saved only on this phone."), Ui.full(this, 8));
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Draw your signature")
                .setView(wrap)
                .setPositiveButton("Use", null)
                .setNeutralButton("Clear", null)
                .setNegativeButton("Cancel", null)
                .show();
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> pad.clear());
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Signatures.Sig sig = Signatures.fromStrokes(pad.strokes());
            if (sig == null) {
                Ui.toast(this, "Draw your signature first");
                return;
            }
            saved.add(sig);
            try {
                Signatures.save(this, saved);
            } catch (java.io.IOException e) {
                Ui.toast(this, "Couldn't save the signature: " + e.getMessage());
            }
            preview.pendingSignature = sig;
            Ui.toast(this, "Tap the page to place it. Drag to move, pinch to resize.");
            d.dismiss();
        });
    }

    // ------------------------------------------------------------------ crop actions

    private void updateEdgesLabel() {
        if (edgesBtn != null) edgesBtn.setText(ScanMath.isFull(cropView.getCorners()) ? "Detect edges" : "Full page");
    }

    private void toggleEdges() {
        if (busy || base == null) return;
        if (ScanMath.isFull(cropView.getCorners())) {
            float[] c = ImageProcessor.detectCorners(base);
            if (c == null) Ui.toast(this, "Couldn't find the page edges. Drag the corners instead.");
            cropView.setCorners(c);
        } else {
            cropView.setCorners(null);
        }
        geomStale = true;
        updateEdgesLabel();
    }

    private void rotate() {
        if (busy || base == null) return;
        meta.corners = cropView.getCorners();
        meta.rotateCw();
        Bitmap r = rotated(base, 90);
        base.recycle();
        base = r;
        cropView.setImage(base, meta.corners);
        geomStale = true;
        updateEdgesLabel();
    }

    // ------------------------------------------------------------------ preview rendering

    private void rebuildGeometry() {
        final int gen = ++generation;
        final PageMeta m = snapshotGeometry();
        final Bitmap src = base;
        working.setText("Working…");
        working.setVisibility(View.VISIBLE);
        new Thread(() -> {
            Bitmap g = ImageProcessor.geometry(src, m);
            if (g == src) g = src.copy(Bitmap.Config.ARGB_8888, false);
            float s = PREVIEW_MAX / (float) Math.max(g.getWidth(), g.getHeight());
            if (s < 1f) {
                Bitmap sm = Bitmap.createScaledBitmap(g, Math.round(g.getWidth() * s), Math.round(g.getHeight() * s), true);
                g.recycle();
                g = sm;
            }
            final Bitmap result = g;
            runOnUiThread(() -> {
                if (gen != generation || isFinishing()) {
                    result.recycle();
                    return;
                }
                geom = result;
                geomStale = false;
                renderLook();
            });
        }).start();
    }

    private PageMeta snapshotGeometry() {
        PageMeta m = new PageMeta();
        m.rotation = 0;  // base is already rotated
        m.corners = meta.corners == null ? null : meta.corners.clone();
        m.curve = meta.curve;
        m.spineLeft = meta.spineLeft;
        return m;
    }

    private void scheduleLook(long delayMs) {
        handler.removeCallbacks(renderLook);
        handler.postDelayed(renderLook, delayMs);
    }

    @Override
    public void onEraseAdded() {
        scheduleLook(0);
    }

    @Override
    public void onMarksChanged() {
        // marks are drawn live by the preview; nothing to re-render
    }

    private void renderLook() {
        if (geom == null || tab == 0) return;
        final int gen = ++generation;
        final Bitmap g = geom;
        final PageMeta m = new PageMeta();
        m.setAdjust(meta.adjust());
        m.erase = new ArrayList<>(meta.erase);
        new Thread(() -> {
            Bitmap out = ImageProcessor.look(g, m, false);
            runOnUiThread(() -> {
                if (gen != generation || isFinishing()) {
                    out.recycle();
                    return;
                }
                working.setVisibility(View.GONE);
                preview.setBitmap(out);
            });
        }).start();
    }

    // ------------------------------------------------------------------ save / cancel

    private void save(final boolean next) {
        if (busy || meta == null) return;
        busy = true;
        setButtonsEnabled(false);
        if (tab == 0) {
            float[] c = cropView.getCorners();
            meta.corners = ScanMath.isFull(c) ? null : c;
        }
        working.setText("Saving…");
        working.setVisibility(View.VISIBLE);
        final PageMeta m = meta;
        Ui.task(this, () -> {
            if (page != null) {
                ImageProcessor.renderPage(page, m);
            } else {
                ImageProcessor.createPage(store, dir, original, m);
            }
        }, () -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_NEXT, next));
            finish();
        }, () -> {
            busy = false;
            working.setVisibility(View.GONE);
            setButtonsEnabled(true);
        });
    }

    private void cancel() {
        if (original != null && original.exists()) {
            //noinspection ResultOfMethodCallIgnored
            original.delete();
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        cancel();
    }
}
