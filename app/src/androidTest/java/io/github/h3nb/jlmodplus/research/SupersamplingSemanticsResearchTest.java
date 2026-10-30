// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * R&D characterization only: native 1x and whole-target 2x LCDUI-like
 * drawing can produce different logical pixel colors on image readback.
 * This is NOT a normative J2ME fixture or a production supersampling patch.
 */
@RunWith(AndroidJUnit4.class)
public final class SupersamplingSemanticsResearchTest {
    @Test public void twoTimesRasterizedEdgesChangeGuestPixelReadback() {
        final int w = 32, h = 32;
        Bitmap logical = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Bitmap physical = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ARGB_8888);
        Bitmap downsampled = null;
        try {
            drawEdge(new Canvas(logical), false);
            Canvas highCanvas = new Canvas(physical);
            highCanvas.scale(2, 2);
            drawEdge(highCanvas, true);
            downsampled = Bitmap.createScaledBitmap(physical, w, h, true);
            assertEquals(w, downsampled.getWidth());
            assertEquals(h, downsampled.getHeight());
            int changedPixels = 0;
            int partiallyCoveredPixels = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = logical.getPixel(x, y);
                    int b = downsampled.getPixel(x, y);
                    if (a != b) changedPixels++;
                    if (Color.red(b) > 0 && Color.red(b) < 255) partiallyCoveredPixels++;
                }
            }
            Log.i("GraphicsRD_HD", "32x32 1x versus 2x: changedPixels=" + changedPixels
                    + " partialCoverage=" + partiallyCoveredPixels);
            assertTrue("Higher-res raster+downsample should visibly differ for subpixel edges",
                    changedPixels > 0);
            assertEquals("background remains unchanged", Color.BLACK,
                    downsampled.getPixel(0, 31));
            // A changed color is compatible with sharper/antialiased presentation,
            // but a game that reads a logical pixel may observe a different value.
        } finally {
            if (downsampled != null && downsampled != physical) downsampled.recycle();
            logical.recycle();
            physical.recycle();
        }
    }

    private static void drawEdge(Canvas canvas, boolean highRes) {
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(1f);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(2.35f, 3.25f, 26.85f, 22.4f, paint);
        // Real guest images can contain cached text, vector paths and sprites.
        // This line proves only a deliberately subpixel-sensitive case.
    }
}
