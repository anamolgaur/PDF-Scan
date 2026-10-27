package com.anamol.pdfscan;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;

import java.io.FileOutputStream;
import java.io.IOException;

/** Hands a finished PDF to Android's print screen (any printer on the network, or "Save as PDF"). */
final class PdfPrintAdapter extends PrintDocumentAdapter {
    private final String name;
    private final byte[] pdf;
    private final int pageCount;

    PdfPrintAdapter(String name, byte[] pdf, int pageCount) {
        this.name = name;
        this.pdf = pdf;
        this.pageCount = pageCount;
    }

    @Override
    public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes, CancellationSignal cancel,
                         LayoutResultCallback callback, Bundle extras) {
        if (cancel.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        PrintDocumentInfo info = new PrintDocumentInfo.Builder(name + ".pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pageCount)
                .build();
        callback.onLayoutFinished(info, !newAttributes.equals(oldAttributes));
    }

    @Override
    public void onWrite(PageRange[] pages, ParcelFileDescriptor destination, CancellationSignal cancel,
                        WriteResultCallback callback) {
        try (FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
            out.write(pdf);
            callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
        } catch (IOException e) {
            callback.onWriteFailed(e.getMessage());
        }
    }
}
