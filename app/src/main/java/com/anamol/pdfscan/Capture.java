package com.anamol.pdfscan;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;

/**
 * Takes photos with the system camera app. The camera writes into a temporary MediaStore
 * entry that is read and then deleted, so no image is left in the gallery.
 */
final class Capture {

    private Capture() {
    }

    private static Uri createTempUri(Context c) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "pdfscan_" + System.currentTimeMillis() + ".jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PdfScanTmp");
        return c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
    }

    /** Opens the camera. Returns the temp Uri to read afterwards, or null if it could not start. */
    static Uri start(Activity a, int requestCode) {
        Uri uri = createTempUri(a);
        if (uri == null) {
            Ui.toast(a, "Could not create a temporary image");
            return null;
        }
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (i.resolveActivity(a.getPackageManager()) == null) {
            discard(a, uri);
            Ui.toast(a, "No camera app found");
            return null;
        }
        MusicPlayer.holdForExternal();
        a.startActivityForResult(i, requestCode);
        return uri;
    }

    static void discard(Context c, Uri uri) {
        if (uri != null) c.getContentResolver().delete(uri, null, null);
    }
}
