package com.anamol.pdfscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs on a real Android system (an emulator in GitHub Actions): the main screens open in both
 * themes without crashing, and an edited PDF saved over its own pages opens in Android's PDF
 * renderer with the mark-up in place.
 */
@RunWith(AndroidJUnit4.class)
public class SmokeTest {
    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(context.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        }
        if (MusicPlayer.enabled(context)) MusicPlayer.setEnabled(context, false);
    }

    private static <A extends Activity> void opens(Class<A> type) {
        try (ActivityScenario<A> s = ActivityScenario.launch(type)) {
            s.onActivity(a -> assertTrue(type.getSimpleName() + " window", a.getWindow().getDecorView().isShown()));
        }
    }

    @Test
    public void screensOpenInBothThemes() {
        for (boolean black : new boolean[]{false, true}) {
            Prefs.setAmoled(context, black);
            Ui.applyTheme(black);
            opens(MainActivity.class);
            opens(SettingsActivity.class);
            opens(LanguagesActivity.class);
            opens(TrashActivity.class);
        }
        Prefs.setAmoled(context, false);
        Ui.applyTheme(false);
    }

    /** A two-page PDF with real text, made by Android itself. */
    private static byte[] samplePdf() throws IOException {
        PdfDocument doc = new PdfDocument();
        Paint p = new Paint();
        p.setTextSize(24);
        for (int i = 0; i < 2; i++) {
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(300, 400, i + 1).create());
            page.getCanvas().drawText("Page " + (i + 1), 40, 60, p);
            doc.finishPage(page);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.writeTo(out);
        doc.close();
        return out.toByteArray();
    }

    @Test
    public void markedUpPdfKeepsItsOwnPages() throws IOException {
        File dir = new File(context.getCacheDir(), "smoke-doc");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        byte[] pdf = samplePdf();
        assertEquals(2, PdfOverlay.pageCount(pdf));
        Files.write(new File(dir, "source-1.pdf").toPath(), pdf);

        List<File> pages = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            File jpg = new File(dir, "p000" + (i + 1) + ".jpg");
            Bitmap b = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            try (FileOutputStream o = new FileOutputStream(jpg)) {
                b.compress(Bitmap.CompressFormat.JPEG, 80, o);
            }
            b.recycle();
            pages.add(jpg);
        }
        // shown order: the second page with a thick black line across the middle, then the first turned
        PageMeta first = new PageMeta();
        first.srcPdf = "source-1.pdf";
        first.srcPage = 1;
        PageMeta.Mark line = new PageMeta.Mark();
        line.type = PageMeta.Mark.PEN;
        line.color = Color.BLACK;
        line.width = 0.05f;
        line.pts = new float[]{0.05f, 0.5f, 0.95f, 0.5f};
        first.marks.add(line);
        first.write(pages.get(0));
        PageMeta second = new PageMeta();
        second.srcPdf = "source-1.pdf";
        second.srcPage = 0;
        second.rotation = 90;
        second.write(pages.get(1));

        byte[] out = PdfExport.sharp(pages);
        assertNotNull("saved over the original pages", out);
        assertTrue(Arrays.equals(pdf, Arrays.copyOf(out, pdf.length)));
        File result = new File(dir, "result.pdf");
        Files.write(result.toPath(), out);
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(result, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(fd)) {
            assertEquals(2, r.getPageCount());
            try (PdfRenderer.Page p = r.openPage(0)) {
                Bitmap b = Bitmap.createBitmap(p.getWidth(), p.getHeight(), Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                int mid = b.getPixel(b.getWidth() / 2, b.getHeight() / 2);
                assertTrue("mark-up drawn", Color.red(mid) < 80);
                int corner = b.getPixel(b.getWidth() / 2, b.getHeight() - 5);
                assertTrue("page left clear", Color.red(corner) > 200);
                b.recycle();
            }
            try (PdfRenderer.Page p = r.openPage(1)) {
                assertTrue("turned page is landscape", p.getWidth() > p.getHeight());
            }
        }
        for (File f : dir.listFiles()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    @Test
    public void barcodeReaderRunsOnDevice() {
        // ZXing is kept by the shrinker rules; a blank picture simply has no code in it
        assertEquals(null, BarcodeCore.find(new byte[64 * 64], 64, 64));
    }
}
