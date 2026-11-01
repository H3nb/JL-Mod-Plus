// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Characterization of REAL JL-Mod Plus LCDUI Image/Graphics entry points
 * under a hypothetical 2x physical backing. This deliberately does not change
 * production target semantics or assert that a global Canvas.scale is correct.
 */
@RunWith(AndroidJUnit4.class)
public final class HdLogicalTargetBoundaryResearchTest {
    private static final int W = 12, H = 10, S = 2;
    private static final String TAG = "GraphicsRD_HD_Boundary";

    private static void paintPattern(Graphics g) {
        g.setColor(0xFF0000);
        g.fillRect(1, 1, 3, 3);
        g.setColor(0x00FF00);
        g.fillRect(5, 2, 4, 4);
        g.setColor(0x0000FF);
        g.fillRect(9, 0, 3, 2);
    }

    private static Image physicalImage() {
        Bitmap bitmap = Bitmap.createBitmap(W * S, H * S, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        return new Image(bitmap, true);
    }

    private static int mismatches(Bitmap base, Bitmap physical) {
        int different = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (base.getPixel(x, y) != physical.getPixel(x * S, y * S)) different++;
            }
        }
        return different;
    }

    @Test public void logicalImageApiNeedsPhysicalCoordinateAdapter() {
        Image nativeImage = Image.createImage(W, H, Color.BLACK);
        Image hd = physicalImage();
        Bitmap copy = null;
        try {
            Graphics original = nativeImage.getGraphics();
            // Get the real Graphics before changing its Image's logical bounds
            // so the initial canvas clip still spans the full physical target.
            Graphics scaled = hd.getGraphics();
            hd.getBounds().set(0, 0, W, H);
            scaled.getCanvas().scale(S, S);

            assertEquals(W, hd.getWidth());
            assertEquals(H, hd.getHeight());
            assertEquals(W * S, hd.getBitmap().getWidth());
            paintPattern(original);
            paintPattern(scaled);
            assertEquals("Integer-aligned drawing alone can match",
                    0, mismatches(nativeImage.getBitmap(), hd.getBitmap()));

            int[] one = new int[1], two = new int[1];
            nativeImage.getRGB(one, 0, 1, 1, 1, 1, 1);
            hd.getRGB(two, 0, 1, 1, 1, 1, 1);
            assertEquals(Color.RED, one[0]);
            assertNotEquals("Current Image.getRGB reads backing coordinates, not logical",
                    one[0], two[0]);
            assertEquals("Physical mapping reveals correct rasterized pixel",
                    one[0], hd.getBitmap().getPixel(2, 2));

            original.copyArea(1, 1, 3, 3, 8, 6, Graphics.TOP | Graphics.LEFT);
            scaled.copyArea(1, 1, 3, 3, 8, 6, Graphics.TOP | Graphics.LEFT);
            int broken = mismatches(nativeImage.getBitmap(), hd.getBitmap());
            assertTrue("Current Graphics.copyArea reads wrong physical source",
                    broken > 0);
            Log.i(TAG, "naive global scale copyArea differing logical pixels=" + broken);

            // R&D positive oracle: physical source coordinates, logical target
            // coordinates (destination Canvas already has the 2x transform).
            // Re-paint base before the corrected copy to remove the bad copy.
            hd.getBitmap().eraseColor(Color.BLACK);
            paintPattern(scaled);
            copy = Bitmap.createBitmap(hd.getBitmap(), 1 * S, 1 * S, 3 * S, 3 * S);
            scaled.getCanvas().drawBitmap(copy, null, new Rect(8, 6, 11, 9), null);
            assertEquals("Explicit source mapping recovers the integer-pixel oracle",
                    0, mismatches(nativeImage.getBitmap(), hd.getBitmap()));
        } finally {
            if (copy != null) copy.recycle();
            hd.getBitmap().recycle();
            nativeImage.getBitmap().recycle();
        }
    }

    @Test public void presenterMustUsePhysicalContentRectRatherThanImageBounds() {
        Image hd = physicalImage();
        Bitmap naive = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Bitmap corrected = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        try {
            Graphics g = hd.getGraphics();
            hd.getBounds().set(0, 0, W, H);
            g.getCanvas().scale(S, S);
            paintPattern(g);
            // This is the exact source Rect contract used by
            // CanvasWrapper.drawImage(Image, RectF) in the active codebase.
            new Canvas(naive).drawBitmap(hd.getBitmap(), hd.getBounds(),
                    new Rect(0, 0, W, H), null);
            new Canvas(corrected).drawBitmap(hd.getBitmap(),
                    new Rect(0, 0, W * S, H * S), new Rect(0, 0, W, H), null);

            int wrong = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int expected = hd.getBitmap().getPixel(x * S, y * S);
                if (naive.getPixel(x, y) != expected) wrong++;
                assertEquals("Full physical content maps to the intended guest output",
                        expected, corrected.getPixel(x, y));
            }
            assertTrue("Image.getBounds() is not the HD backing source rectangle", wrong > 0);
            Log.i(TAG, "naive presenter uses wrong source crop: mismatched=" + wrong);
        } finally {
            naive.recycle();
            corrected.recycle();
            hd.getBitmap().recycle();
        }
    }
}
