package com.anamol.pdfscan;

import android.content.ActivityNotFoundException;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets another app open a document found by the phone search, read-only. Only files that are in
 * the search index can be served, and only to an app the user chose to open it with.
 */
public class PhoneFileProvider extends ContentProvider {
    static final String AUTHORITY = "com.anamol.pdfscan.phonefiles";

    static Uri uriFor(long id, String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(String.valueOf(id)).appendPath(name).build();
    }

    static String mimeOf(String name) {
        String ext = OfficeText.extension(name);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    /** Opens a found file in whichever app the user picks. */
    static void open(Context c, long id, String name) {
        Uri uri = uriFor(id, name);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            MusicPlayer.holdForExternal();
            c.startActivity(Intent.createChooser(i, "Open " + name));
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(c, "No app on this phone can open ." + OfficeText.extension(name) + " files",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (uri.getPathSegments().isEmpty()) throw new FileNotFoundException();
        long id;
        try {
            id = Long.parseLong(uri.getPathSegments().get(0));
        } catch (NumberFormatException e) {
            throw new FileNotFoundException();
        }
        String path = DeviceIndex.get(getContext()).pathOf(id);
        if (path == null) throw new FileNotFoundException();
        File f = new File(path);
        if (!f.isFile()) throw new FileNotFoundException();
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.equals("r")) throw new SecurityException("Read only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String last = uri.getLastPathSegment();
        return last != null ? mimeOf(last) : null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }
}
