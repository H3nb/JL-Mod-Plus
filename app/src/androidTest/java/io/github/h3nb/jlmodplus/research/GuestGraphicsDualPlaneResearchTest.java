// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Characterizes real Java ME Graphics/Image calls against dual-plane
 * approaches. The 2x Image is intentionally synthetic research state,
 * not a production change to Image's logical/physical ownership contract.
 */
@RunWith(AndroidJUnit4.class)
public final class GuestGraphicsDualPlaneResearchTest {
    private static final String TAG = "GraphicsRD_GuestDual";
    private static final int W = 20, H = 16, S = 2;

    private static int mismatches(Bitmap one, Bitmap hd) {
        int differences = 0;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if (one.getPixel(x, y) != hd.getPixel(x * S, y * S))
                    differences++;
        return differences;
    }

    /**
     * The guest is already allowed to pass int[] pixels. Does the existing
     * drawRGB path respect a scaled Canvas the same way as drawImage? A
     * black/green mark exposes positions without relying on font AA.
     */
    @Test public void realGuestDrawRgbAndDrawImageHaveDifferentScaleBoundaries() {
        Image logical = Image.createImage(W, H, Color.BLACK);
        Image physical = new Image(Bitmap.createBitmap(W * S, H * S,
                Bitmap.Config.ARGB_8888), true);
        physical.getBitmap().eraseColor(Color.BLACK);
        Image rgbSource = Image.createRGBImage(new int[]{
                Color.GREEN, Color.GREEN, Color.GREEN,
                Color.GREEN, Color.GREEN, Color.GREEN,
                Color.GREEN, Color.GREEN, Color.GREEN
        }, 3, 3, true);
        try {
            Graphics one = logical.getGraphics();
            Graphics hd = physical.getGraphics();
            physical.getBounds().set(0, 0, W, H);
            hd.getCanvas().scale(S, S);
            assertEquals(W, physical.getWidth());

            one.drawImage(rgbSource, 2, 3, Graphics.LEFT | Graphics.TOP);
            hd.drawImage(rgbSource, 2, 3, Graphics.LEFT | Graphics.TOP);
            assertEquals("Image draw scales in the Android Canvas",
                    0, mismatches(logical.getBitmap(), physical.getBitmap()));
            assertEquals(Color.GREEN, physical.getBitmap().getPixel(4, 6));

            logical.getBitmap().eraseColor(Color.BLACK);
            physical.getBitmap().eraseColor(Color.BLACK);
            int[] pixels = new int[9];
            for (int i = 0; i < pixels.length; i++) pixels[i] = Color.GREEN;
            one.drawRGB(pixels, 0, 3, 2, 3, 3, 3, true);
            hd.drawRGB(pixels, 0, 3, 2, 3, 3, 3, true);
            int observed = mismatches(logical.getBitmap(), physical.getBitmap());
            Log.i(TAG, "actual javax.microedition.Graphics.drawRGB logical-to-physical mismatch="
                    + observed + " / " + (W * H));

            // The 1x guest output remains authoritative. A nonzero mismatch
            // is a reason for an HD-specific drawRGB adapter, not a test
            // failure to hide. No claim that Android's API is consistent
            // across driver versions.
            assertEquals(Color.GREEN, logical.getBitmap().getPixel(2, 3));
            assertEquals(Color.GREEN, logical.getBitmap().getPixel(4, 5));
        } finally {
            logical.getBitmap().recycle();
            physical.getBitmap().recycle();
            rgbSource.getBitmap().recycle();
        }
    }

    /**
     * A mutable Image is a real Java ME object whose backing pixels change
     * between a queued draw and its eventual Picture replay. The result is
     * characterized against immediate javax.microedition.Graphics.drawImage.
     */
    @Test public void mutableGuestImageRequiresAStableRecordedSource() {
        Image source = Image.createImage(4, 4, Color.RED);
        Image immediate = Image.createImage(W, H, Color.BLACK);
        Bitmap deferred = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Bitmap snapshotted = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        deferred.eraseColor(Color.BLACK);
        snapshotted.eraseColor(Color.BLACK);
        Bitmap savedSource = null;
        try {
            immediate.getGraphics().drawImage(source, 3, 2,
                    Graphics.LEFT | Graphics.TOP);
            savedSource = source.getBitmap().copy(Bitmap.Config.ARGB_8888, false);
            Picture delayed = new Picture();
            Canvas recording = delayed.beginRecording(W, H);
            recording.drawBitmap(source.getBitmap(), 3, 2, null);
            delayed.endRecording();
            // Guest mutation after recording, before playback.
            Graphics writer = source.getGraphics();
            writer.setColor(0x00FF00);
            writer.fillRect(0, 0, 4, 4);
            assertEquals(Color.GREEN, source.getBitmap().getPixel(1, 1));
            delayed.draw(new Canvas(deferred));
            int difference = 0;
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++)
                    if (immediate.getBitmap().getPixel(x, y)
                            != deferred.getPixel(x, y)) difference++;
            // A copied source has explicitly stable lifetime; it reproduces
            // the immediate Java ME Graphics result even after guest mutation.
            new Canvas(snapshotted).drawBitmap(savedSource, 3, 2, null);
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++)
                    assertEquals("Frozen snapshot must preserve draw-time pixels",
                            immediate.getBitmap().getPixel(x, y),
                            snapshotted.getPixel(x, y));
            Log.i(TAG, "Mutable guest Image changed after Picture record: deferred mismatch="
                    + difference + " pixels; frozen source parity=true");
            // The driver may snapshot Picture bitmap data at record time.
            // Record the outcome; this observation is not a failure on its own.
        } finally {
            if (savedSource != null) savedSource.recycle();
            source.getBitmap().recycle(); immediate.getBitmap().recycle();
            deferred.recycle(); snapshotted.recycle();
        }
    }
}
