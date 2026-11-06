package com.anamol.pdfscan;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** The page-finding network loads from the app and finds a page on a real Android system. */
@RunWith(AndroidJUnit4.class)
public class PageNetDeviceTest {
    @Test
    public void findsAPage() {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PageModel.init(c);
        Prefs.setSmartDetect(c, true);
        assertNotNull("model loads", PageModel.get());
        Bitmap b = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(b);
        k.drawColor(Color.rgb(120, 80, 60));
        Path page = new Path();
        page.moveTo(200, 150);
        page.lineTo(500, 250);
        page.lineTo(400, 700);
        page.lineTo(75, 600);
        page.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(235, 235, 230));
        k.drawPath(page, p);
        long t = System.nanoTime();
        float[] corners = ImageProcessor.detectCorners(b);
        Log.i("PageNetDeviceTest", "detect " + (System.nanoTime() - t) / 1_000_000 + " ms");
        assertNotNull(corners);
        assertTrue("top-left x", Math.abs(corners[0] - 200 / 599f) < 0.03f);
        assertTrue("bottom-right y", Math.abs(corners[5] - 700 / 799f) < 0.03f);
    }
}
