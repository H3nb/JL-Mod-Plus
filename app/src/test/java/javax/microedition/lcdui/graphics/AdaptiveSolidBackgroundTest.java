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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class AdaptiveSolidBackgroundTest {
    private static int[] grid(int argb) {
        int[] pixels = new int[AmbientColorSampler.GRID_COLOR_COUNT];
        Arrays.fill(pixels, argb);
        return pixels;
    }

    @Test public void dominantColorOverridesMinorAccentAndRemainsOpaque() {
        AdaptiveSolidBackground background = new AdaptiveSolidBackground(0xFFFFFFFF);
        int[] pixels = grid(0xFF2255CC);
        for (int i = 0; i < 24; i++) pixels[i] = 0xFFFF0000;
        int color = background.representativeColor(pixels, 0xFFFFFFFF);
        assertEquals(0xFF000000, color & 0xFF000000);
        assertTrue((color & 255) > ((color >>> 16) & 255));
        assertTrue((color & 255) <= 150);
    }

    @Test public void transparentPixelsUseFallbackAndDoNotLeakOldFrame() {
        AdaptiveSolidBackground background = new AdaptiveSolidBackground(0xFF000000);
        int[] pixels = grid(0x00000000);
        int black = background.representativeColor(pixels, 0xFF000000);
        int white = background.representativeColor(pixels, 0xFFFFFFFF);
        assertEquals(0xFF000000, black);
        assertNotEquals(black, white);
    }

    @Test public void briefFlashDoesNotCommitAndStableChangeInterpolates() {
        AdaptiveSolidBackground background = new AdaptiveSolidBackground(0xFF111111);
        int[] blue = grid(0xFF0033FF);
        int[] red = grid(0xFFFF2200);
        background.setGrid(blue, 0);
        int initial = background.colorAt(0);
        background.setGrid(red, 200_000_000L);
        assertEquals(initial, background.colorAt(250_000_000L));
        background.setGrid(blue, 400_000_000L);
        assertEquals(initial, background.colorAt(450_000_000L));
        background.setGrid(red, 600_000_000L);
        background.setGrid(red, 800_000_000L);
        assertTrue(background.isTransitioning());
        assertEquals(initial, background.colorAt(800_000_000L));
        int intermediate = background.colorAt(1_050_000_000L);
        assertNotEquals(initial, intermediate);
        assertTrue(background.isTransitioning());
        assertEquals(background.representativeColor(red, 0xFF111111),
                background.colorAt(1_400_000_000L));
        assertFalse(background.isTransitioning());
    }

    @Test public void fallbackStopsAffectingARealGuestFrame() {
        AdaptiveSolidBackground background = new AdaptiveSolidBackground(0xFF111111);
        background.setFallback(0xFFEEEEEE);
        assertEquals(0xFFEEEEEE, background.colorAt(0));
        background.setGrid(grid(0xFF008800), 0);
        int fromGuest = background.colorAt(0);
        background.setFallback(0xFFFFFFFF);
        assertEquals(fromGuest, background.colorAt(0));
    }
}
