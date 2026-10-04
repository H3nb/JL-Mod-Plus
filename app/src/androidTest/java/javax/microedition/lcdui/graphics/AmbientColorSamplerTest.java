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
    public void samplesOnlyActiveBoundsForTinySource() {
        Bitmap bitmap = Bitmap.createBitmap(2, 4, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[8];
        for (int i = 0; i < 4; i++) pixels[i] = 0xFFFF0000;
        for (int i = 4; i < 8; i++) pixels[i] = 0xFF0000FF;
        bitmap.setPixels(pixels, 0, 2, 0, 0, 2, 4);

        float[] output = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        AmbientColorSampler sampler = new AmbientColorSampler();
        assertTrue(sampler.captureGrid(bitmap, 2, 2));
        sampler.toneCapturedGrid(0xFF000000, output);
        for (int i = 0; i < output.length; i += AmbientColorField.CHANNEL_COUNT) {
            assertTrue(output[i] > output[i + 2]);
        }
        assertFalse(sampler.captureGrid(bitmap, 0, 2));
    }

    @Test
    public void opaqueGridColorsDoNotFollowHostTheme() {
        Bitmap bitmap = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF40A0E0);

        AmbientColorSampler sampler = new AmbientColorSampler();
        assertTrue(sampler.captureGrid(bitmap, 9, 9));

        float[] darkTheme = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        float[] lightTheme = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        sampler.toneCapturedGrid(0xFF000000, darkTheme);
        sampler.toneCapturedGrid(0xFFFFFFFF, lightTheme);

        for (int i = 0; i < AmbientColorField.GRID_CHANNEL_COUNT; i++) {
            assertEquals(darkTheme[i], lightTheme[i], 0.0001f);
        }
    }

    @Test
    public void transparentGridColorsUseThemeSnapshotFallback() {
        Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0x00000000);

        AmbientColorSampler sampler = new AmbientColorSampler();
        assertTrue(sampler.captureGrid(bitmap, 4, 4));

        float[] darkTheme = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        float[] lightTheme = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        sampler.toneCapturedGrid(0xFF000000, darkTheme);
        sampler.toneCapturedGrid(0xFFFFFFFF, lightTheme);

        assertTrue(lightTheme[0] > darkTheme[0]);
        assertTrue(lightTheme[1] > darkTheme[1]);
        assertTrue(lightTheme[2] > darkTheme[2]);
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

        float[] output = new float[AmbientColorField.GRID_CHANNEL_COUNT];
        AmbientColorSampler sampler = new AmbientColorSampler();
        assertTrue(sampler.captureGrid(bitmap, 9, 9));
        sampler.toneCapturedGrid(0xFF000000, output);

        int top = 4 * AmbientColorField.CHANNEL_COUNT;
        int bottom = ((AmbientColorSampler.GRID_SIZE - 1) * AmbientColorSampler.GRID_SIZE + 4)
                * AmbientColorField.CHANNEL_COUNT;
        assertTrue(output[top] > output[top + 2]);
        assertTrue(output[bottom + 2] > output[bottom]);
    }
}
