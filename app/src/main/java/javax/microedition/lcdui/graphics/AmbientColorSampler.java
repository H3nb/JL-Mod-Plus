/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.graphics;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/** Copies and tones a small full-frame palette from the active guest bitmap. */
public final class AmbientColorSampler {
    public static final int ANCHOR_COUNT = AmbientColorField.ANCHOR_COUNT;
    public static final int GRID_SIZE = AmbientColorField.GRID_SIZE;
    public static final int GRID_COLOR_COUNT = AmbientColorField.GRID_COLOR_COUNT;
    private static final float INSET = 0.03f;
    private static final float GRID_SAMPLE_MIX = 0.90f;
    private static final float GRID_LUMINANCE_CAP = 0.45f;
    private static final float[] SRGB_TO_LINEAR = new float[256];
    private static final float[] LINEAR_TO_SRGB = new float[4097];
    private static final float[] ANCHOR_X = {INSET, 0.5f, 1.0f - INSET, 1.0f - INSET,
            1.0f - INSET, 0.5f, INSET, INSET};
    private static final float[] ANCHOR_Y = {INSET, INSET, INSET, 0.5f,
            1.0f - INSET, 1.0f - INSET, 1.0f - INSET, 0.5f};
    private final int[] pixels = new int[72];
    private final int[] gridPixels = new int[GRID_COLOR_COUNT];
    private final Rect gridSource = new Rect();
    private final Rect gridDestination = new Rect(0, 0, GRID_SIZE, GRID_SIZE);
    private Bitmap gridBitmap;
    private Canvas gridCanvas;
    private Paint gridPaint;
    private int patchWidth;
    private int patchHeight;

    static {
        for (int i = 0; i < SRGB_TO_LINEAR.length; i++) {
            float s = i / 255.0f;
            SRGB_TO_LINEAR[i] = s <= 0.04045f
                    ? s / 12.92f
                    : (float) Math.pow((s + 0.055f) / 1.055f, 2.4);
        }
        for (int i = 0; i < LINEAR_TO_SRGB.length; i++) {
            float linear = i / 4096.0f;
            float s = linear <= 0.0031308f
                    ? linear * 12.92f
                    : 1.055f * (float) Math.pow(linear, 1.0 / 2.4) - 0.055f;
            LINEAR_TO_SRGB[i] = Math.max(0.0f, Math.min(1.0f, s));
        }
    }

    /**
     * Samples only the active bitmap bounds. The caller must hold its buffer lock while this
     * method runs and must not retain the bitmap reference afterward.
     */
    public boolean sample(Bitmap bitmap, int activeWidth, int activeHeight,
            int baseArgb, int[] outAnchorArgb) {
        if (!capture(bitmap, activeWidth, activeHeight)) return false;
        toneCaptured(baseArgb, outAnchorArgb);
        return true;
    }

    /** Downscales the complete active bitmap into one reusable low-resolution palette. */
    public boolean sampleGrid(Bitmap bitmap, int activeWidth, int activeHeight,
            int baseArgb, int[] outGridArgb) {
        if (!captureGrid(bitmap, activeWidth, activeHeight)) return false;
        toneCapturedGrid(baseArgb, outGridArgb);
        return true;
    }

    /** Copies the complete active bitmap into the reusable low-resolution palette. */
    public boolean captureGrid(Bitmap bitmap, int activeWidth, int activeHeight) {
        if (bitmap == null || activeWidth <= 0 || activeHeight <= 0) return false;
        int width = Math.min(activeWidth, bitmap.getWidth());
        int height = Math.min(activeHeight, bitmap.getHeight());
        if (width <= 0 || height <= 0) return false;
        if (gridBitmap == null || gridBitmap.isRecycled()) {
            gridBitmap = Bitmap.createBitmap(GRID_SIZE, GRID_SIZE, Bitmap.Config.ARGB_8888);
            gridCanvas = new Canvas(gridBitmap);
            gridPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        }
        gridSource.set(0, 0, width, height);
        gridCanvas.drawBitmap(bitmap, gridSource, gridDestination, gridPaint);
        gridBitmap.getPixels(gridPixels, 0, GRID_SIZE, 0, 0, GRID_SIZE, GRID_SIZE);
        return true;
    }

    /** Copies the eight patches; the caller may release its bitmap lock after this returns. */
    public boolean capture(Bitmap bitmap, int activeWidth, int activeHeight) {
        if (bitmap == null || activeWidth <= 0 || activeHeight <= 0) return false;
        int width = Math.min(activeWidth, bitmap.getWidth());
        int height = Math.min(activeHeight, bitmap.getHeight());
        if (width <= 0 || height <= 0) return false;

        patchWidth = Math.min(3, width);
        patchHeight = Math.min(3, height);
        for (int anchor = 0; anchor < ANCHOR_COUNT; anchor++) {
            int centerX = Math.round(ANCHOR_X[anchor] * (width - 1));
            int centerY = Math.round(ANCHOR_Y[anchor] * (height - 1));
            int left = clamp(centerX - patchWidth / 2, 0, width - patchWidth);
            int top = clamp(centerY - patchHeight / 2, 0, height - patchHeight);
            bitmap.getPixels(pixels, anchor * 9, patchWidth, left, top,
                    patchWidth, patchHeight);
        }
        return true;
    }

    /** Averages and tones the most recently captured patches without touching the bitmap. */
    public void toneCaptured(int baseArgb, int[] outAnchorArgb) {
        if (outAnchorArgb == null || outAnchorArgb.length < ANCHOR_COUNT
                || patchWidth <= 0 || patchHeight <= 0) return;
        float baseR = SRGB_TO_LINEAR[(baseArgb >>> 16) & 0xFF];
        float baseG = SRGB_TO_LINEAR[(baseArgb >>> 8) & 0xFF];
        float baseB = SRGB_TO_LINEAR[baseArgb & 0xFF];
        for (int anchor = 0; anchor < ANCHOR_COUNT; anchor++) {
            float r = 0.0f;
            float g = 0.0f;
            float b = 0.0f;
            int count = patchWidth * patchHeight;
            int pixelOffset = anchor * 9;
            for (int i = 0; i < count; i++) {
                int pixel = pixels[pixelOffset + i];
                float alpha = ((pixel >>> 24) & 0xFF) / 255.0f;
                float inverseAlpha = 1.0f - alpha;
                r += SRGB_TO_LINEAR[(pixel >>> 16) & 0xFF] * alpha + baseR * inverseAlpha;
                g += SRGB_TO_LINEAR[(pixel >>> 8) & 0xFF] * alpha + baseG * inverseAlpha;
                b += SRGB_TO_LINEAR[pixel & 0xFF] * alpha + baseB * inverseAlpha;
            }
            float invCount = 1.0f / count;
            r = r * invCount * 0.85f + baseR * 0.15f;
            g = g * invCount * 0.85f + baseG * 0.15f;
            b = b * invCount * 0.85f + baseB * 0.15f;
            float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float cap = 0.22f / Math.max(luminance, 1.0e-6f);
            if (cap < 1.0f) {
                r *= cap;
                g *= cap;
                b *= cap;
            }
            outAnchorArgb[anchor] = 0xFF000000
                    | (linearToByte(r) << 16)
                    | (linearToByte(g) << 8)
                    | linearToByte(b);
        }
    }

    /** Tones the most recently captured full-frame palette without touching the bitmap. */
    public void toneCapturedGrid(int baseArgb, int[] outGridArgb) {
        if (outGridArgb == null || outGridArgb.length < GRID_COLOR_COUNT) return;
        float baseR = SRGB_TO_LINEAR[(baseArgb >>> 16) & 0xFF];
        float baseG = SRGB_TO_LINEAR[(baseArgb >>> 8) & 0xFF];
        float baseB = SRGB_TO_LINEAR[baseArgb & 0xFF];
        for (int i = 0; i < GRID_COLOR_COUNT; i++) {
            int pixel = gridPixels[i];
            float alpha = ((pixel >>> 24) & 0xFF) / 255.0f;
            float inverseAlpha = 1.0f - alpha;
            float r = SRGB_TO_LINEAR[(pixel >>> 16) & 0xFF] * alpha + baseR * inverseAlpha;
            float g = SRGB_TO_LINEAR[(pixel >>> 8) & 0xFF] * alpha + baseG * inverseAlpha;
            float b = SRGB_TO_LINEAR[pixel & 0xFF] * alpha + baseB * inverseAlpha;
            r = r * GRID_SAMPLE_MIX + baseR * (1.0f - GRID_SAMPLE_MIX);
            g = g * GRID_SAMPLE_MIX + baseG * (1.0f - GRID_SAMPLE_MIX);
            b = b * GRID_SAMPLE_MIX + baseB * (1.0f - GRID_SAMPLE_MIX);
            float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float cap = GRID_LUMINANCE_CAP / Math.max(luminance, 1.0e-6f);
            if (cap < 1.0f) {
                r *= cap;
                g *= cap;
                b *= cap;
            }
            outGridArgb[i] = 0xFF000000
                    | (linearToByte(r) << 16)
                    | (linearToByte(g) << 8)
                    | linearToByte(b);
        }
    }

    static float srgbChannelToLinear(int value) {
        return SRGB_TO_LINEAR[value & 0xFF];
    }

    private static int linearToByte(float value) {
        int index = Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 4096.0f);
        return Math.round(LINEAR_TO_SRGB[index] * 255.0f);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
