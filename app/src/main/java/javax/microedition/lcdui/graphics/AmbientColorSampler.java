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

/** Captures and converts a reusable low-resolution field from the active guest bitmap. */
public final class AmbientColorSampler {
    public static final int GRID_SIZE = AmbientColorField.GRID_SIZE;
    public static final int GRID_COLOR_COUNT = AmbientColorField.GRID_COLOR_COUNT;
    private static final float GRID_LUMINANCE_CAP = 0.45f;
    private static final float[] SRGB_TO_LINEAR = new float[256];
    private static final float[] LINEAR_TO_SRGB = new float[4097];
    private final int[] gridPixels = new int[GRID_COLOR_COUNT];
    private final Rect gridSource = new Rect();
    private final Rect gridDestination = new Rect(0, 0, GRID_SIZE, GRID_SIZE);
    private Bitmap gridBitmap;
    private Canvas gridCanvas;
    private Paint gridPaint;

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

    /** Copies the complete active bitmap into a private reusable low-resolution bitmap. */
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
        // The reusable destination must not retain RGB/alpha from the previous guest frame.
        // drawBitmap() uses source-over compositing, so a transparent source pixel would
        // otherwise leave the old destination untouched and bypass the later theme fallback.
        gridBitmap.eraseColor(0x00000000);
        gridCanvas.drawBitmap(bitmap, gridSource, gridDestination, gridPaint);
        return true;
    }

    /**
     * Converts the private copy to linear RGB. Transparent guest pixels are composited against
     * the supplied host-theme snapshot; later host-theme changes do not mutate an active sample.
     */
    public void toneCapturedGrid(int baseArgb, float[] outGridLinear) {
        if (gridBitmap == null || gridBitmap.isRecycled() || outGridLinear == null
                || outGridLinear.length < AmbientColorField.GRID_CHANNEL_COUNT) {
            return;
        }
        // The active guest bitmap is no longer referenced here, so this readback can happen after
        // its buffer lock has been released.
        gridBitmap.getPixels(gridPixels, 0, GRID_SIZE, 0, 0, GRID_SIZE, GRID_SIZE);
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
            float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float cap = GRID_LUMINANCE_CAP / Math.max(luminance, 1.0e-6f);
            if (cap < 1.0f) {
                r *= cap;
                g *= cap;
                b *= cap;
            }
            int offset = i * AmbientColorField.CHANNEL_COUNT;
            outGridLinear[offset] = r;
            outGridLinear[offset + 1] = g;
            outGridLinear[offset + 2] = b;
        }
    }

    static float srgbChannelToLinear(int value) {
        return SRGB_TO_LINEAR[value & 0xFF];
    }

    static float linearChannelToSrgb(float value) {
        int index = Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 4096.0f);
        return LINEAR_TO_SRGB[index];
    }

    static int linearChannelToByte(float value) {
        return Math.round(linearChannelToSrgb(value) * 255.0f);
    }
}
