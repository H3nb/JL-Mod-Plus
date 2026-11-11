// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

import io.github.h3nb.jlmodplus.graphics.ImageEnhancementPolicy;
import io.github.h3nb.jlmodplus.graphics.MmpxScaler;
import io.github.h3nb.jlmodplus.graphics.XbrzFreescaleScaler;

import static org.junit.Assert.assertEquals;

/** Temporary PR research probe. Android emulator timing is not a device performance gate. */
@RunWith(AndroidJUnit4.class)
public class BitmapUpscalingPhaseProbeTest {
    private static final String TAG = "JL-HD-Perf";
    private static final int WARMUP = 3;
    private static final int SAMPLES = 12;

    private static int[] pixels(int width, int height, int seed) {
        int[] result = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = ((x + seed) / 11 + (y + seed) / 9) % 3;
                result[y * width + x] = value == 0 ? 0xff182c46
                        : value == 1 ? 0xffd6b85a : 0xff64a77e;
            }
        }
        return result;
    }

    private static long percentile(long[] nanos, int percentile) {
        long[] ordered = nanos.clone();
        Arrays.sort(ordered);
        return ordered[(ordered.length - 1) * percentile / 100] / 1000;
    }

    @Test public void profileSourceReadAlgorithmBitmapCopyAndPresentation() {
        for (int[] dimensions : new int[][] {{128, 128}, {240, 320}}) {
            int width = dimensions[0], height = dimensions[1];
            int factor = ImageEnhancementPolicy.chooseFactor(width, height, 4,
                    Integer.MAX_VALUE);
            Bitmap source = Bitmap.createBitmap(pixels(width, height, 0),
                    width, height, Bitmap.Config.ARGB_8888);
            Bitmap target = Bitmap.createBitmap(width * 4, height * 4,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(target);
            RectF region = new RectF(0, 0, width * 4, height * 4);
            try {
                for (int mode : new int[] {BitmapUpscaling.MMPX, BitmapUpscaling.XBRZ}) {
                    long[][] phases = new long[4][SAMPLES];
                    for (int iteration = -WARMUP; iteration < SAMPLES; iteration++) {
                        long t0 = System.nanoTime();
                        int[] rgba = new int[width * height];
                        source.getPixels(rgba, 0, width, 0, 0, width, height);
                        long t1 = System.nanoTime();
                        int[] enlarged = mode == BitmapUpscaling.MMPX
                                ? MmpxScaler.scale(rgba, width, height, factor)
                                : XbrzFreescaleScaler.scale(rgba, width, height, factor);
                        long t2 = System.nanoTime();
                        Bitmap enhanced = Bitmap.createBitmap(enlarged,
                                width * factor, height * factor,
                                Bitmap.Config.ARGB_8888);
                        long t3 = System.nanoTime();
                        canvas.drawBitmap(enhanced, null, region, null);
                        long t4 = System.nanoTime();
                        if (iteration >= 0) {
                            phases[0][iteration] = t1 - t0;
                            phases[1][iteration] = t2 - t1;
                            phases[2][iteration] = t3 - t2;
                            phases[3][iteration] = t4 - t3;
                        }
                        enhanced.recycle();
                    }
                    String[] labels = {"read", "scale", "create", "draw"};
                    for (int phase = 0; phase < phases.length; phase++) {
                        String result = "phase mode="
                                + (mode == BitmapUpscaling.MMPX ? "MMPX" : "xBRZ")
                                + " source=" + width + "x" + height
                                + " factor=" + factor + " stage=" + labels[phase]
                                + " median_us=" + percentile(phases[phase], 50)
                                + " p90_us=" + percentile(phases[phase], 90);
                        Log.i(TAG, result);
                        System.out.println(TAG + " " + result);
                    }
                    assertEquals(width * 4, target.getWidth());
                }
            } finally {
                source.recycle();
                target.recycle();
            }
        }
    }

    @Test public void characterizeEvictionUnderLargeImageWorkingSet() {
        final int width = 240, height = 320, factor = 3;
        final long outputBytes = (long) width * height * factor * factor * 4;
        int budget = BitmapUpscaling.cacheBudgetBytes(Runtime.getRuntime().maxMemory());
        int count = Math.min(10, (int) (budget / outputBytes) + 3);
        ArrayList<Image> sources = new ArrayList<>();
        Image target = Image.createDisplayTarget(width, height, 4);
        try {
            for (int i = 0; i < count; i++) {
                sources.add(Image.createRGBImage(pixels(width, height, i * 31),
                        width, height, true));
            }
            for (int mode : new int[]{BitmapUpscaling.MMPX, BitmapUpscaling.XBRZ}) {
                Graphics.configureBitmapUpscaling(mode);
                Graphics graphics = target.getGraphics();
                long firstDrawNs, warmDrawNs, revisitNs;
                long started = System.nanoTime();
                graphics.drawImage(sources.get(0), 0, 0, Graphics.TOP | Graphics.LEFT);
                firstDrawNs = System.nanoTime() - started;

                started = System.nanoTime();
                graphics.drawImage(sources.get(0), 0, 0, Graphics.TOP | Graphics.LEFT);
                warmDrawNs = System.nanoTime() - started;

                for (int i = 1; i < count; i++) {
                    graphics.drawImage(sources.get(i), 0, 0,
                            Graphics.TOP | Graphics.LEFT);
                }
                started = System.nanoTime();
                graphics.drawImage(sources.get(0), 0, 0, Graphics.TOP | Graphics.LEFT);
                revisitNs = System.nanoTime() - started;
                String result = "churn mode="
                        + (mode == BitmapUpscaling.MMPX ? "MMPX" : "xBRZ")
                        + " source=" + width + "x" + height
                        + " sources=" + count + " budget_bytes=" + budget
                        + " first_us=" + firstDrawNs / 1000
                        + " warm_us=" + warmDrawNs / 1000
                        + " revisit_us=" + revisitNs / 1000;
                Log.i(TAG, result);
                System.out.println(TAG + " " + result);
            }
        } finally {
            Graphics.configureBitmapUpscaling(BitmapUpscaling.NEAREST);
            for (Image source : sources) source.getBitmap().recycle();
            target.getBitmap().recycle();
        }
    }
}
