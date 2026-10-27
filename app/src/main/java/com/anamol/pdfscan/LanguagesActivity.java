package com.anamol.pdfscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Set;

/**
 * Text-recognition languages: Indian languages, Chinese/Japanese/Korean and European languages.
 * English is built in; each other language is a one-time download, then works offline.
 */
public class LanguagesActivity extends Activity {
    private LinearLayout box;
    private TextView summary;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = Ui.bare(this);
        page.addView(Ui.topBar(this, this::finish, Ui.title(this, "Text languages")));
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 32));
        scroll.addView(root);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        summary = Ui.meta(this, "");
        root.addView(summary);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box);
        setContentView(page);
        refresh();
    }

    private void refresh() {
        summary.setText("English is built in. Download the languages your documents use (about 1–4 MB each); "
                + "after that they work offline. Pages are read in every language that is switched on, so keep "
                + "to the ones you need: each extra language makes reading a little slower.\n\nReading in: "
                + String.join(", ", Languages.activeNames(this)));
        box.removeAllViews();
        box.addView(Ui.section(this, "Accuracy"));
        if (Languages.bestEnglish(this)) {
            box.addView(Ui.toggleWith(this, "High-accuracy English", "Downloaded (about 15 MB). Better on poor "
                    + "prints and neat handwriting, but slower. Turn off to go back to the built-in model.", true,
                    (b, isOn) -> {
                        if (!isOn) {
                            Languages.removeBestEnglish(this);
                            refresh();
                        }
                    }));
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(this, 56));
            TextView label = Ui.body(this, "High-accuracy English \u00B7 about 15 MB, better on neat handwriting", false);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final Button get = Ui.chip(this, "Download");
            get.setOnClickListener(v -> {
                Runnable go = () -> {
                    get.setEnabled(false);
                    get.setText("Downloading\u2026");
                    Ui.task(this, () -> Languages.downloadBestEnglish(this, true), () -> {
                        Ui.toast(this, "High-accuracy English added. Use \u201CRecognise text again\u201D on older scans.");
                        refresh();
                    }, this::refresh);
                };
                if (Prefs.internet(this)) go.run();
                else new AlertDialog.Builder(this).setTitle("Download the high-accuracy model?")
                        .setMessage("This connects to GitHub once to download Tesseract's larger English model. "
                                + "Nothing is uploaded, and internet access stays off for everything else.")
                        .setPositiveButton("Download", (d, w) -> go.run()).setNegativeButton("Cancel", null).show();
            });
            row.addView(get);
            box.addView(row);
        }
        box.addView(Ui.meta(this, "Handwriting is hard for any offline reader: neat block letters work best."));

        Set<String> on = Languages.enabled(this);
        for (String[][] group : Languages.GROUPS) {
            box.addView(Ui.section(this, group[0][0]));
            for (int i = 1; i < group.length; i++) {
                final String code = group[i][0];
                final String name = group[i][1];
                if (Languages.installed(this, code)) {
                    Switch s = Ui.toggleWith(this, name, "Downloaded · long-press to remove", on.contains(code),
                            (b, isOn) -> {
                                Languages.setEnabled(this, code, isOn);
                                refreshSummaryOnly();
                            });
                    s.setOnLongClickListener(v -> {
                        Ui.confirm(this, "Remove " + name + " from this phone?", () -> {
                            Languages.remove(this, code);
                            refresh();
                        });
                        return true;
                    });
                    box.addView(s);
                } else {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setMinimumHeight(Ui.dp(this, 52));
                    TextView label = Ui.body(this, name, false);
                    row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    final Button get = Ui.chip(this, "Download");
                    get.setOnClickListener(v -> askAndDownload(get, code, name));
                    row.addView(get);
                    box.addView(row);
                }
            }
        }
    }

    private void refreshSummaryOnly() {
        String s = summary.getText().toString();
        int i = s.lastIndexOf("Reading in: ");
        if (i >= 0) summary.setText(s.substring(0, i) + "Reading in: " + String.join(", ", Languages.activeNames(this)));
    }

    private void askAndDownload(Button get, String code, String name) {
        if (Prefs.internet(this)) {
            download(get, code, name, false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Download " + name + "?")
                .setMessage("This connects to GitHub once to download the official Tesseract model for " + name
                        + ". Nothing is uploaded, and internet access stays off for everything else.")
                .setPositiveButton("Download", (d, w) -> download(get, code, name, true))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void download(Button get, String code, String name, boolean oneOff) {
        get.setEnabled(false);
        get.setText("Downloading…");
        Ui.task(this, () -> Languages.download(this, code, oneOff), () -> {
            Ui.toast(this, name + " added");
            refresh();
        }, this::refresh);
    }
}
