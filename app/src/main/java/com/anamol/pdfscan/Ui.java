package com.anamol.pdfscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Visual language: deep maroon ground, cream type, no boxes. Hierarchy comes from type (Sora for
 * titles, DM Sans for everything else), spacing and hairlines. One cream pill per screen marks the
 * main action; everything else is quiet. Touch targets are at least 48dp (44dp for chips).
 */
final class Ui {
    // palette
    static final int MAROON = 0xFF4A1521;
    static final int CREAM = 0xFFF4EBDD;
    // roles (set by applyTheme: maroon, or AMOLED black with maroon and cream type)
    static int BG = MAROON;
    static int SURFACE = 0xFF5A1D2A;
    static int LINE = 0xFF6B2F3B;
    static int INK = CREAM;
    static int MUTED = 0xFFC8ADA0;
    static int ACCENT = CREAM;
    static int ON_ACCENT = MAROON;
    static int RIPPLE = 0x33F4EBDD;
    static int RIPPLE_ON_ACCENT = 0x334A1521;
    static boolean amoled;

    /**
     * Two looks, one design: maroon ground with cream type, or true black (pixels off on AMOLED
     * screens, saving battery) with cream type, maroon secondary text and maroon buttons.
     * Call before any screen is built.
     */
    static void applyTheme(boolean black) {
        amoled = black;
        if (black) {
            BG = 0xFF000000;
            SURFACE = 0xFF160A0D;
            LINE = 0xFF3F1A23;
            INK = CREAM;
            MUTED = 0xFFC25468;          // maroon, light enough to read on black (4.8:1)
            ACCENT = 0xFF7A2234;         // maroon pill with cream text (8:1)
            ON_ACCENT = CREAM;
            RIPPLE = 0x33F4EBDD;
            RIPPLE_ON_ACCENT = 0x33F4EBDD;
        } else {
            BG = MAROON;
            SURFACE = 0xFF5A1D2A;
            LINE = 0xFF6B2F3B;
            INK = CREAM;
            MUTED = 0xFFC8ADA0;
            ACCENT = CREAM;
            ON_ACCENT = MAROON;
            RIPPLE = 0x33F4EBDD;
            RIPPLE_ON_ACCENT = 0x334A1521;
        }
    }

    interface Job {
        void run() throws Exception;
    }

    interface OnPick {
        void picked(int index);
    }

    private static Typeface regular;
    private static Typeface medium;
    private static Typeface display;

    private Ui() {
    }

    // ------------------------------------------------------------------ type

    private static Typeface font(Context c, int id, Typeface fallback) {
        try {
            return c.getResources().getFont(id);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static Typeface regular(Context c) {
        if (regular == null) regular = font(c, R.font.dm_sans_regular, Typeface.DEFAULT);
        return regular;
    }

    static Typeface medium(Context c) {
        if (medium == null) medium = font(c, R.font.dm_sans_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return medium;
    }

    static Typeface display(Context c) {
        if (display == null) display = font(c, R.font.sora_semibold, Typeface.DEFAULT_BOLD);
        return display;
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    private static TextView text(Context c, String s, Typeface tf, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Screen and section titles. */
    static TextView heading(Context c, String s) {
        TextView t = text(c, s, display(c), 22, INK);
        t.setLetterSpacing(-0.02f);
        return t;
    }

    /** A document title in a top bar: one line, ellipsized. */
    static TextView title(Context c, String s) {
        TextView t = text(c, s, display(c), 19, INK);
        t.setLetterSpacing(-0.01f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** List item titles. */
    static TextView label(Context c, String s) {
        TextView t = text(c, s, medium(c), 16, INK);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    static TextView body(Context c, String s, boolean muted) {
        TextView t = text(c, s, regular(c), 15, muted ? MUTED : INK);
        t.setLineSpacing(0, 1.45f);
        return t;
    }

    /** Small secondary text: counts, dates, hints. */
    static TextView meta(Context c, String s) {
        TextView t = text(c, s, regular(c), 13, MUTED);
        t.setLineSpacing(0, 1.35f);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    /** Kept for older callers: same as meta. */
    static TextView mono(Context c, String s) {
        return meta(c, s);
    }

    /** Small uppercase label above a group. */
    static TextView section(Context c, String s) {
        TextView t = text(c, s.toUpperCase(Locale.ROOT), medium(c), 11.5f, MUTED);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, dp(c, 24), 0, dp(c, 8));
        return t;
    }

    /** Document titles from file names read better with spaces than underscores. */
    static String displayTitle(String title) {
        return title == null ? "" : title.replace('_', ' ').trim();
    }

    /** "Today 14:09", "Yesterday 09:30", "25 Oct", or "25 Oct 2025". */
    static String when(long millis) {
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(millis);
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(millis));
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR)) {
            int d = now.get(Calendar.DAY_OF_YEAR) - t.get(Calendar.DAY_OF_YEAR);
            if (d == 0) return "Today " + time;
            if (d == 1) return "Yesterday " + time;
            return new SimpleDateFormat("d MMM", Locale.US).format(new Date(millis));
        }
        return new SimpleDateFormat("d MMM yyyy", Locale.US).format(new Date(millis));
    }

    // ------------------------------------------------------------------ surfaces

    static GradientDrawable box(Context c, int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    private static Drawable ripple(Drawable content, int radiusPx, int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    /** Rounds a view's corners (e.g. page thumbnails). */
    static void round(View v, int radiusDp) {
        final float r = dp(v.getContext(), radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    static View hairline(Context c, int insetStartDp) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(c, insetStartDp));
        v.setLayoutParams(lp);
        return v;
    }

    /** A divider for lists: a 1px line inset from the start. */
    static Drawable listDivider(Context c, int insetStartDp, int insetEndDp) {
        return new InsetDrawable(new ColorDrawable(LINE), dp(c, insetStartDp), 0, dp(c, insetEndDp), 0);
    }

    static Drawable tinted(Context c, int res, int color) {
        Drawable d = c.getDrawable(res);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        return d;
    }

    // ------------------------------------------------------------------ controls

    /**
     * Primary = the one cream pill on a screen. Secondary = quiet text button with a soft press
     * effect and no outline. Both 48dp or taller.
     */
    static Button button(Context c, String text, boolean primary) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTypeface(medium(c));
        b.setTextSize(primary ? 16 : 15);
        b.setTextColor(primary ? ON_ACCENT : INK);
        b.setElevation(0f);
        b.setStateListAnimator(null);
        int h = dp(c, primary ? 52 : 48);
        b.setMinHeight(h);
        b.setMinimumHeight(h);
        b.setMinWidth(dp(c, 48));
        b.setMinimumWidth(dp(c, 48));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        b.setGravity(Gravity.CENTER);
        int radius = h / 2;
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(ACCENT);
        fill.setCornerRadius(radius);
        b.setBackground(primary ? ripple(fill, radius, RIPPLE_ON_ACCENT) : ripple(null, radius, RIPPLE));
        // setBackground can replace the padding with the drawable's own: put it back
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return b;
    }

    /**
     * Makes text shrink (down to 11sp) instead of overflowing when its view has a fixed width,
     * such as buttons sharing a row. One line, never cut off at the edges.
     */
    static void fit(View v) {
        if (!(v instanceof TextView)) return;
        TextView t = (TextView) v;
        float sp = t.getTextSize() / t.getResources().getDisplayMetrics().scaledDensity;
        int max = Math.max(12, Math.round(sp));
        t.setEllipsize(null);
        t.setMaxLines(1);
        t.setAutoSizeTextTypeUniformWithConfiguration(11, max, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    }

    /** A button with a leading line icon. */
    static Button button(Context c, String text, int iconRes, boolean primary) {
        Button b = button(c, text, primary);
        Drawable d = tinted(c, iconRes, primary ? ON_ACCENT : INK);
        if (d != null) {
            int s = dp(c, 22);
            d.setBounds(0, 0, s, s);
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(dp(c, 8));
        }
        return b;
    }

    /** A 48dp round icon button with a borderless press effect. */
    static ImageButton iconButton(Context c, int iconRes, String description) {
        ImageButton b = new ImageButton(c);
        b.setImageDrawable(tinted(c, iconRes, INK));
        b.setContentDescription(description);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null));
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        b.setMinimumWidth(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        return b;
    }

    /** A toolbar tool: line icon over a short label, 68dp wide (page preview, page selection). */
    static View tool(Context c, int icon, String label, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setMinimumWidth(dp(c, 68));
        t.setMinimumHeight(dp(c, 60));
        t.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, box(c, 0xFFFFFFFF, 0, 10)));
        t.setClickable(true);
        t.setFocusable(true);
        t.setContentDescription(label);
        t.setOnClickListener(click);
        android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setImageDrawable(tinted(c, icon, INK));
        int s = dp(c, 22);
        t.addView(iv, new LinearLayout.LayoutParams(s, s));
        TextView tv = meta(c, label);
        tv.setTextColor(INK);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 4);
        t.addView(tv, lp);
        return t;
    }

    /** A horizontally scrolling row of tools with a hairline above it. */
    static LinearLayout toolbar(Context c, View... tools) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(hairline(c, 0));
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 10));
        for (View v : tools) row.addView(v);
        scroll.addView(row);
        wrap.addView(scroll);
        return wrap;
    }

    /** Selectable option (filters, tools, sizes): hairline pill, cream when selected. */
    static Button chip(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTypeface(medium(c));
        b.setTextSize(14);
        b.setElevation(0f);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setMinWidth(dp(c, 48));
        b.setMinimumWidth(dp(c, 48));
        b.setSingleLine(true);
        setChipSelected(c, b, false);
        return b;
    }

    static void setChipSelected(Context c, Button b, boolean selected) {
        b.setSelected(selected);
        b.setTextColor(selected ? ON_ACCENT : INK);
        int r = dp(c, 22);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(r);
        // inset so the 44dp touch area holds a 36dp visual pill
        if (selected) {
            g.setColor(ACCENT);
        } else {
            g.setColor(0x00000000);
            g.setStroke(dp(c, 1), LINE);
        }
        Drawable inset = new InsetDrawable(g, 0, dp(c, 4), 0, dp(c, 4));
        b.setBackground(ripple(inset, r, selected ? RIPPLE_ON_ACCENT : RIPPLE));
        // the inset background brings its own (zero) side padding; keep the text clear of the outline
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
    }

    /** A horizontal, scrollable row of mutually exclusive chips. Returns the chips in order. */
    static List<Button> chipRow(Context c, LinearLayout parent, String[] labels, int selected, final OnPick onPick) {
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final List<Button> chips = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Button b = chip(c, labels[i]);
            setChipSelected(c, b, i == selected);
            b.setOnClickListener(v -> {
                for (int k = 0; k < chips.size(); k++) setChipSelected(c, chips.get(k), k == idx);
                onPick.picked(idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(c, 8), 0);
            row.addView(b, lp);
            chips.add(b);
        }
        scroll.addView(row);
        parent.addView(scroll, full(c, 4));
        return chips;
    }

    /** A pill-shaped text field on a slightly lighter surface, with an optional leading icon. */
    static EditText input(Context c, String hint, boolean password) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(INK);
        e.setTypeface(regular(c));
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        e.setMinHeight(dp(c, 48));
        e.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        e.setBackground(box(c, SURFACE, 0, 24));
        return e;
    }

    static EditText searchField(Context c, String hint) {
        EditText e = input(c, hint, false);
        Drawable d = tinted(c, R.drawable.ic_search, MUTED);
        if (d != null) {
            int s = dp(c, 20);
            d.setBounds(0, 0, s, s);
            e.setCompoundDrawablesRelative(d, null, null, null);
            e.setCompoundDrawablePadding(dp(c, 12));
        }
        e.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return e;
    }

    /** Switch with a two-line label: title and a quieter description. */
    static Switch toggle(Context c, String title, String description, boolean on) {
        Switch s = new Switch(c);
        s.setTypeface(regular(c));
        s.setTextSize(15);
        s.setTextColor(INK);
        if (description != null) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(title);
            int start = sb.length();
            sb.append('\n').append(description);
            sb.setSpan(new android.text.style.ForegroundColorSpan(MUTED), start, sb.length(), 0);
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(13, true), start, sb.length(), 0);
            s.setText(sb);
        } else {
            s.setText(title);
        }
        s.setLineSpacing(0, 1.3f);
        s.setMinHeight(dp(c, 56));
        s.setPadding(0, dp(c, 8), 0, dp(c, 8));
        s.setSwitchPadding(dp(c, 16));
        s.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{CREAM, MUTED}));
        s.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0x99F4EBDD, LINE}));
        s.setChecked(on);
        return s;
    }

    static Switch toggleWith(Context c, String title, String description, boolean on,
                             android.widget.CompoundButton.OnCheckedChangeListener l) {
        Switch s = toggle(c, title, description, on);
        s.setOnCheckedChangeListener(l);
        return s;
    }

    // ------------------------------------------------------------------ layout

    static LinearLayout.LayoutParams full(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, topDp), 0, 0);
        return lp;
    }

    /** Equal-width widgets side by side with a small gap. */
    static LinearLayout row(Context c, View... views) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int gap = dp(c, 8);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMargins(gap, 0, 0, 0);
            fit(views[i]);
            row.addView(views[i], lp);
        }
        return row;
    }

    static LinearLayout pair(Context c, View a, View b) {
        return row(c, a, b);
    }

    /** Standard padded screen container. */
    static LinearLayout screen(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(c, 16);
        root.setPadding(pad, dp(c, 12), pad, pad);
        return root;
    }

    /** Edge-to-edge screen container; children manage their own padding. */
    static LinearLayout bare(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        return root;
    }

    /** 56dp top bar: optional back button, a title that fills the space, then action icons. */
    static LinearLayout topBar(Context c, Runnable onBack, View title, View... actions) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(c, 56));
        bar.setPadding(dp(c, onBack != null ? 4 : 20), dp(c, 4), dp(c, 4), 0);
        if (onBack != null) {
            ImageButton back = iconButton(c, R.drawable.ic_back, "Back");
            back.setOnClickListener(v -> onBack.run());
            bar.addView(back);
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (onBack != null) tl.setMarginStart(dp(c, 4));
        bar.addView(title, tl);
        for (View a : actions) bar.addView(a);
        return bar;
    }

    /** Bottom action bar: a quiet secondary action on the left, the main pill filling the rest. */
    static LinearLayout bottomBar(Context c, View secondary, View primary) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(hairline(c, 0));
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(c, 12), dp(c, 12), dp(c, 16), dp(c, 12));
        if (secondary != null) {
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.setMarginEnd(dp(c, 8));
            bar.addView(secondary, sl);
        }
        fit(primary);
        bar.addView(primary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wrap.addView(bar);
        return wrap;
    }

    // ------------------------------------------------------------------ feedback and dialogs

    static void toast(final Activity a, final String msg) {
        a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_SHORT).show());
    }

    /** Runs heavy work off the UI thread, then calls onOk or onFail on the UI thread. */
    static void task(final Activity a, final Job job, final Runnable onOk, final Runnable onFail) {
        new Thread(() -> {
            try {
                job.run();
                a.runOnUiThread(() -> {
                    if (onOk != null) onOk.run();
                });
            } catch (Exception | OutOfMemoryError e) {
                Diag.log(a.getClass().getSimpleName() + " task failed", e);
                a.runOnUiThread(() -> {
                    Toast.makeText(a, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    if (onFail != null) onFail.run();
                });
            }
        }).start();
    }

    static void promptRename(final Activity a, final DocStore store, final File dir, final Runnable after) {
        final EditText input = input(a, "Name", false);
        input.setText(displayTitle(store.getTitle(dir)));
        input.setSelectAllOnFocus(true);
        LinearLayout wrap = new LinearLayout(a);
        int pad = dp(a, 20);
        wrap.setPadding(pad, dp(a, 8), pad, 0);
        wrap.addView(input, full(a, 0));
        new AlertDialog.Builder(a)
                .setTitle("Rename")
                .setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    String t = input.getText().toString().trim();
                    if (!t.isEmpty()) {
                        store.setTitle(dir, t);
                        if (after != null) after.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static void confirm(Activity a, String message, final Runnable onYes) {
        new AlertDialog.Builder(a)
                .setMessage(message)
                .setPositiveButton("Delete", (d, w) -> onYes.run())
                .setNegativeButton("Cancel", null)
                .show();
    }
}
