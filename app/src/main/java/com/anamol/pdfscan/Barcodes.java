package com.anamol.pdfscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.Image;
import android.net.Uri;
import android.widget.TextView;


import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Reads QR codes and barcodes from the live camera picture, entirely on the phone (ZXing).
 * Shows what was found with Copy / Share / Open; nothing is looked up online by the app.
 */
final class Barcodes {
    private Barcodes() {
    }

    /** Finds a code in a camera frame (YUV, brightness plane only). Null if none. */
    static String decode(Image img) {
        Image.Plane y = img.getPlanes()[0];
        int w = img.getWidth();
        int h = img.getHeight();
        int rs = y.getRowStride();
        ByteBuffer buf = y.getBuffer();
        byte[] lum = new byte[w * h];
        for (int r = 0; r < h; r++) {
            buf.position(r * rs);
            buf.get(lum, r * w, Math.min(w, buf.remaining()));
        }
        return BarcodeCore.find(lum, w, h);
    }

    /** Shows a found code with actions suited to what it is. */
    static AlertDialog showResult(final Activity a, final String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        final boolean link = lower.startsWith("http://") || lower.startsWith("https://");
        String kind = link ? "Link" : lower.startsWith("wifi:") ? "Wi-Fi details" : lower.startsWith("upi://")
                ? "UPI payment link" : lower.startsWith("begin:vcard") ? "Contact" : text.matches("\\d{8,14}")
                ? "Barcode number" : "Text";
        TextView body = Ui.body(a, text, false);
        body.setTextIsSelectable(true);
        int pad = Ui.dp(a, 24);
        body.setPadding(pad, Ui.dp(a, 8), pad, 0);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(kind)
                .setView(body)
                .setNegativeButton("Copy", (d, w) -> {
                    ClipboardManager cm = a.getSystemService(ClipboardManager.class);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Code", text));
                    Ui.toast(a, "Copied");
                })
                .setNeutralButton("Share", (d, w) -> {
                    MusicPlayer.holdForExternal();
                    a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text), "Share"));
                });
        if (link || lower.startsWith("upi://")) {
            b.setPositiveButton(link ? "Open link" : "Open in a UPI app", (d, w) -> {
                try {
                    MusicPlayer.holdForExternal();
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(text)));
                } catch (RuntimeException e) {
                    Ui.toast(a, "No app on this phone can open it");
                }
            });
        } else {
            b.setPositiveButton("Close", null);
        }
        return b.show();
    }
}
