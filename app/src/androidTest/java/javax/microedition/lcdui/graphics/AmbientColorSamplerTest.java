/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.graphics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.Test;

public class AmbientColorSamplerTest {
    @Test
    public void samplesOnlyActiveBoundsAndKeepsAllAnchorsValidForTinySource() {
        Bitmap bitmap = Bitmap.createBitmap(2, 4, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[8];
        for (int i = 0; i < 4; i++) pixels[i] = 0xFFFF0000;
        for (int i = 4; i < 8; i++) pixels[i] = 0xFF0000FF;
        bitmap.setPixels(pixels, 0, 2, 0, 0, 2, 4);

        int[] output = new int[AmbientColorSampler.ANCHOR_COUNT];
        AmbientColorSampler sampler = new AmbientColorSampler();
        assertTrue(sampler.sample(bitmap, 2, 2, 0xFF000000, output));
        for (int i = 0; i < output.length; i++) {
            assertEquals(output[0], output[i]);
            assertTrue(((output[i] >>> 16) & 0xFF) > (output[i] & 0xFF));
        }
        assertFalse(sampler.sample(bitmap, 0, 2, 0xFF000000, output));
    }

    @Test
    public void samplesSpatialColorChangesAcrossTheFullFramePalette() {
        Bitmap bitmap = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[81];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 9; x++) pixels[y * 9 + x] = 0xFFFF0000;
        }
        for (int y = 4; y < 9; y++) {
            for (int x = 0; x < 9; x++) pixels[y * 9 + x] = 0xFF0000FF;
        }
        bitmap.setPixels(pixels, 0, 9, 0, 0, 9, 9);

        int[] output = new int[AmbientColorSampler.GRID_COLOR_COUNT];
        AmbientColorSampler sampler = new AmbientColorSampler();
        assertTrue(sampler.sampleGrid(bitmap, 9, 9, 0xFF000000, output));

        int top = output[4];
        int bottom = output[8 * AmbientColorSampler.GRID_SIZE + 4];
        assertTrue(((top >>> 16) & 0xFF) > (top & 0xFF));
        assertTrue((bottom & 0xFF) > ((bottom >>> 16) & 0xFF));
    }
}
