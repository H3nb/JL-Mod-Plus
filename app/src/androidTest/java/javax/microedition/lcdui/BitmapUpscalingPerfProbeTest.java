// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.github.h3nb.jlmodplus.graphics.ImageEnhancementPolicy;

import static org.junit.Assert.assertEquals;

/**
 * Temporary PR-scoped probe for relative CPU cost, not an Android FPS benchmark.
 * Inputs are synthetic and represent typical MIDlet image dimensions.
 */
@RunWith(AndroidJUnit4.class)
public class BitmapUpscalingPerfProbeTest {
    private static final String TAG = "JL-HD-Perf";
    private static final int[][] SIZES = {
            {64, 64}, {128, 128}, {240, 320}, {422, 208}
    };

    @Test public void measureColdVersusCachedReconstruction() {
        try {
            for (int mode : new int[] {BitmapUpscaling.MMPX, BitmapUpscaling.XBRZ}) {
                for (int[] dimensions : SIZES) {
                    int width = dimensions[0];
                    int height = dimensions[1];
                    int[] pixels = new int[width * height];
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int pattern = (x / 11 + y / 9) % 3;
                            pixels[y * width + x] = pattern == 0 ? 0xff182c46
                                    : pattern == 1 ? 0xffd6b85a : 0xff64a77e;
                        }
                    }
                    Image original = Image.createRGBImage(pixels, width, height, true);
                    Image target = Image.createDisplayTarget(width, height, 4);
                    try {
                        Graphics.configureBitmapUpscaling(mode);
                        Graphics graphics = target.getGraphics();
                        long start = System.nanoTime();
                        graphics.drawImage(original, 0, 0, Graphics.TOP | Graphics.LEFT);
                        long coldNs = System.nanoTime() - start;
                        long bestCachedNs = Long.MAX_VALUE;
                        for (int n = 0; n < 3; n++) {
                            start = System.nanoTime();
                            graphics.drawImage(original, 0, 0, Graphics.TOP | Graphics.LEFT);
                            bestCachedNs = Math.min(bestCachedNs, System.nanoTime() - start);
                        }
                        assertEquals(width * 4, target.getBitmap().getWidth());
                        assertEquals(height * 4, target.getBitmap().getHeight());
                        int factor = ImageEnhancementPolicy.chooseFactor(
                                width, height, 4, Integer.MAX_VALUE);
                        String result = "mode=" + (mode == BitmapUpscaling.MMPX ? "MMPX" : "xBRZ")
                                + " source=" + width + "x" + height
                                + " factor=" + factor
                                + " cold_us=" + coldNs / 1000L
                                + " warm_best_us=" + bestCachedNs / 1000L;
                        Log.i(TAG, result);
                        System.out.println(TAG + " " + result);
                    } finally {
                        original.getBitmap().recycle();
                        target.getBitmap().recycle();
                    }
                }
            }
        } finally {
            Graphics.configureBitmapUpscaling(BitmapUpscaling.NEAREST);
        }
    }
}
