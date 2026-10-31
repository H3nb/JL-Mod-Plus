// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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


    /**
     * Contract-shaped control fixture, not a production HD implementation:
     * integer logical operations can retain identical guest reads at 1x/2x
     * if source-rectangle and copy coordinates are translated explicitly.
     * This does not cover M3G, Mascot, fonts, or general renderer behavior.
     */
    @Test public void integerPixelOperationsWithExplicitCopyScalingMatchOneTimesReadback() {
        final int w = 12, h = 10, scale = 2;
        Bitmap logical = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Bitmap physical = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888);
        Bitmap sprite = Bitmap.createBitmap(new int[]{
                Color.RED, Color.GREEN, Color.BLUE, Color.WHITE
        }, 2, 2, Bitmap.Config.ARGB_8888);
        Bitmap source1 = null, source2 = null, down = null;
        try {
            Canvas nativeCanvas = new Canvas(logical);
            Canvas scaledCanvas = new Canvas(physical);
            scaledCanvas.scale(scale, scale);
            for (Canvas target : new Canvas[]{nativeCanvas, scaledCanvas}) {
                target.drawColor(Color.BLACK);
                Paint fill = new Paint();
                fill.setColor(Color.RED);
                target.drawRect(1, 1, 6, 5, fill);
                int saved = target.save();
                target.clipRect(4, 2, 10, 7);
                fill.setColor(Color.BLUE);
                target.drawRect(2, 3, 11, 7, fill);
                target.restoreToCount(saved);
                Paint nearest = new Paint();
                nearest.setFilterBitmap(false);
                target.drawBitmap(sprite, null, new Rect(7, 1, 9, 3), nearest);
            }
            // Correct a common proposed HD bug: copyArea must sample the
            // physical coordinates of the source, but draw to a logical
            // destination (the Canvas already applies the physical scale).
            source1 = Bitmap.createBitmap(logical, 1, 1, 5, 3);
            source2 = Bitmap.createBitmap(physical, 2, 2, 10, 6);
            nativeCanvas.drawBitmap(source1, null, new Rect(2, 6, 7, 9), null);
            scaledCanvas.drawBitmap(source2, null, new Rect(2, 6, 7, 9), null);

            down = Bitmap.createScaledBitmap(physical, w, h, true);
            int[] original = new int[w * h];
            int[] observed = new int[w * h];
            logical.getPixels(original, 0, w, 0, 0, w, h);
            down.getPixels(observed, 0, w, 0, 0, w, h);
            assertArrayEquals("1x integer logical commands must survive 2x raster and logical readback",
                    original, observed);
            assertEquals(w * scale, physical.getWidth());
            assertEquals(h * scale, physical.getHeight());
            assertEquals(Color.BLUE, observed[5 + 4 * w]);
            assertEquals(Color.GREEN, observed[8 + 1 * w]);
            Log.i("GraphicsRD_HD", "2x integer/clip/sprite/copy readback exact match on "
                    + original.length + " logical pixels");
        } finally {
            if (down != null) down.recycle();
            if (source2 != null) source2.recycle();
            if (source1 != null) source1.recycle();
            sprite.recycle();
            physical.recycle();
            logical.recycle();
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
