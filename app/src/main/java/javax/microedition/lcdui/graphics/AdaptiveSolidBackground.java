/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.graphics;

import java.util.Arrays;

/** Single opaque host background color, independent of the immersive spatial renderer. */
public final class AdaptiveSolidBackground {
    public static final long SAMPLE_INTERVAL_NS = 200_000_000L;
    private static final long TRANSITION_NS = 500_000_000L;
    private static final int CHANGE_THRESHOLD_SQUARED = 22 * 22;
    private static final int GRID_SIZE = AmbientColorSampler.GRID_SIZE;
    private static final int PIXEL_COUNT = AmbientColorSampler.GRID_COLOR_COUNT;
    private final int[] weights = new int[64];
    private final long[] reds = new long[64];
    private final long[] greens = new long[64];
    private final long[] blues = new long[64];
    private int fallbackArgb;
    private int startArgb;
    private int targetArgb;
    private int pendingArgb;
    private int pendingSamples;
    private long transitionStartNs;
    private boolean transitioning;
    private boolean hasSample;

    public AdaptiveSolidBackground(int fallbackArgb) {
        reset(fallbackArgb);
    }

    public void reset(int fallbackArgb) {
        this.fallbackArgb = fallbackArgb | 0xFF000000;
        startArgb = this.fallbackArgb;
        targetArgb = this.fallbackArgb;
        pendingSamples = 0;
        transitioning = false;
        hasSample = false;
    }

    /** The host theme is a fallback only until the first guest frame arrives. */
    public void setFallback(int argb) {
        fallbackArgb = argb | 0xFF000000;
        if (!hasSample) {
            startArgb = fallbackArgb;
            targetArgb = fallbackArgb;
        }
    }

    /** Called only for new captured guest frames, at a bounded sampling cadence. */
    public void setGrid(int[] pixels, long nowNs) {
        if (pixels == null || pixels.length < PIXEL_COUNT) return;
        int candidate = representativeColor(pixels, fallbackArgb);
        if (!hasSample) {
            hasSample = true;
            startArgb = candidate;
            targetArgb = candidate;
            pendingSamples = 0;
            transitioning = false;
            return;
        }
        if (distanceSquared(candidate, targetArgb) < CHANGE_THRESHOLD_SQUARED) {
            pendingSamples = 0;
            return;
        }
        // One-frame flashes do not immediately recolor the entire host background.
        if (pendingSamples == 0 || distanceSquared(candidate, pendingArgb)
                >= CHANGE_THRESHOLD_SQUARED) {
            pendingArgb = candidate;
            pendingSamples = 1;
            return;
        }
        pendingSamples = 0;
        int current = colorAt(nowNs);
        startArgb = current;
        targetArgb = candidate;
        transitionStartNs = nowNs;
        transitioning = current != candidate;
    }

    public int colorAt(long nowNs) {
        if (!transitioning) return targetArgb;
        long elapsed = nowNs - transitionStartNs;
        if (elapsed >= TRANSITION_NS) {
            transitioning = false;
            return targetArgb;
        }
        if (elapsed <= 0L) return startArgb;
        float progress = elapsed / (float) TRANSITION_NS;
        float eased = 1.0f - (1.0f - progress) * (1.0f - progress);
        return 0xFF000000
                | (mix((startArgb >>> 16) & 255, (targetArgb >>> 16) & 255, eased) << 16)
                | (mix((startArgb >>> 8) & 255, (targetArgb >>> 8) & 255, eased) << 8)
                | mix(startArgb & 255, targetArgb & 255, eased);
    }

    public boolean isTransitioning() {
        return transitioning;
    }

    /** Quantize the guest palette, with a modest preference for edge colors. */
    int representativeColor(int[] pixels, int baseArgb) {
        Arrays.fill(weights, 0);
        Arrays.fill(reds, 0);
        Arrays.fill(greens, 0);
        Arrays.fill(blues, 0);
        for (int i = 0; i < PIXEL_COUNT; i++) {
            int pixel = pixels[i];
            int alpha = pixel >>> 24;
            int inverse = 255 - alpha;
            int red = (((pixel >>> 16) & 255) * alpha
                    + ((baseArgb >>> 16) & 255) * inverse + 127) / 255;
            int green = (((pixel >>> 8) & 255) * alpha
                    + ((baseArgb >>> 8) & 255) * inverse + 127) / 255;
            int blue = ((pixel & 255) * alpha + (baseArgb & 255) * inverse + 127) / 255;
            int bin = ((red >>> 6) << 4) | ((green >>> 6) << 2) | (blue >>> 6);
            int x = i % GRID_SIZE;
            int y = i / GRID_SIZE;
            int importance = x < 3 || x >= GRID_SIZE - 3 || y < 3
                    || y >= GRID_SIZE - 3 ? 2 : 1;
            weights[bin] += importance;
            reds[bin] += (long) red * importance;
            greens[bin] += (long) green * importance;
            blues[bin] += (long) blue * importance;
        }
        int best = 0;
        for (int i = 1; i < weights.length; i++) {
            if (weights[i] > weights[best]) best = i;
        }
        if (weights[best] == 0) return baseArgb | 0xFF000000;
        int red = (int) (reds[best] / weights[best]);
        int green = (int) (greens[best] / weights[best]);
        int blue = (int) (blues[best] / weights[best]);
        // Subtle muted tones complement the LCD without competing for attention.
        int luminance = (54 * red + 183 * green + 19 * blue) >>> 8;
        return 0xFF000000
                | (tone(red, luminance) << 16)
                | (tone(green, luminance) << 8)
                | tone(blue, luminance);
    }

    private static int tone(int channel, int luminance) {
        return Math.min(150, Math.round((channel * 0.82f + luminance * 0.18f) * 0.78f));
    }

    private static int distanceSquared(int a, int b) {
        int dr = ((a >>> 16) & 255) - ((b >>> 16) & 255);
        int dg = ((a >>> 8) & 255) - ((b >>> 8) & 255);
        int db = (a & 255) - (b & 255);
        return dr * dr + dg * dg + db * db;
    }

    private static int mix(int a, int b, float factor) {
        return Math.round(a + (b - a) * factor);
    }
}
