/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.graphics;

import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.RectF;

/** Tiny bilinear bitmap renderer for software and Android Canvas presentation paths. */
public final class AmbientCanvasRenderer {
    public static final int GRID_SIZE = 32;
    private final AmbientColorField field;
    private final Bitmap bitmap = Bitmap.createBitmap(GRID_SIZE, GRID_SIZE, Bitmap.Config.ARGB_8888);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float[] normalizedX = new float[GRID_SIZE * GRID_SIZE];
    private final float[] normalizedY = new float[GRID_SIZE * GRID_SIZE];
    private final int[] pixels = new int[GRID_SIZE * GRID_SIZE];
    private final int[] nextPixels = new int[GRID_SIZE * GRID_SIZE];
    private final RectF top = new RectF();
    private final RectF bottom = new RectF();
    private final RectF left = new RectF();
    private final RectF right = new RectF();
    private boolean configured;

    public AmbientCanvasRenderer(AmbientColorField field, int initialArgb) {
        this.field = field;
        bitmap.eraseColor(initialArgb | 0xFF000000);
        for (int i = 0; i < pixels.length; i++) pixels[i] = initialArgb | 0xFF000000;
    }

    public void configure(int surfaceWidth, int surfaceHeight, RectF gameRect) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || gameRect == null) {
            configured = false;
            return;
        }
        float leftNorm = clamp01(gameRect.left / surfaceWidth);
        float topNorm = clamp01(gameRect.top / surfaceHeight);
        float rightNorm = clamp01(gameRect.right / surfaceWidth);
        float bottomNorm = clamp01(gameRect.bottom / surfaceHeight);
        int index = 0;
        for (int y = 0; y < GRID_SIZE; y++) {
            float normalizedYValue = y / (float) (GRID_SIZE - 1);
            for (int x = 0; x < GRID_SIZE; x++, index++) {
                normalizedX[index] = x / (float) (GRID_SIZE - 1);
                normalizedY[index] = normalizedYValue;
            }
        }
        field.configureNodes(normalizedX, normalizedY, leftNorm, topNorm, rightNorm, bottomNorm,
                surfaceWidth / (float) surfaceHeight);
        configured = true;
    }

    /** Rebuilds the small raster only when its visible output changes. */
    public boolean update(long nowNs) {
        if (!configured) return false;
        boolean transitioning = field.renderNodesArgb(nowNs, nextPixels);
        boolean changed = false;
        for (int i = 0; i < nextPixels.length; i++) {
            if (nextPixels[i] != pixels[i]) {
                changed = true;
                break;
            }
        }
        if (changed) {
            bitmap.setPixels(nextPixels, 0, GRID_SIZE, 0, 0, GRID_SIZE, GRID_SIZE);
            int[] swap = pixels;
            System.arraycopy(nextPixels, 0, swap, 0, nextPixels.length);
        }
        return transitioning || changed;
    }

    /** Draws one transformed bitmap through disjoint margin clips, leaving the game area untouched. */
    public void draw(android.graphics.Canvas canvas, RectF surface, RectF gameRect) {
        if (!configured || surface.width() <= 0.0f || surface.height() <= 0.0f) return;
        float l = Math.max(surface.left, Math.min(surface.right, gameRect.left));
        float t = Math.max(surface.top, Math.min(surface.bottom, gameRect.top));
        float r = Math.max(surface.left, Math.min(surface.right, gameRect.right));
        float b = Math.max(surface.top, Math.min(surface.bottom, gameRect.bottom));
        int save = canvas.save();
        if (t > surface.top) {
            top.set(surface.left, surface.top, surface.right, t);
            canvas.clipRect(top);
            canvas.drawBitmap(bitmap, null, surface, paint);
            canvas.restoreToCount(save);
            save = canvas.save();
        }
        if (b < surface.bottom) {
            bottom.set(surface.left, b, surface.right, surface.bottom);
            canvas.clipRect(bottom);
            canvas.drawBitmap(bitmap, null, surface, paint);
            canvas.restoreToCount(save);
            save = canvas.save();
        }
        if (l > surface.left && b > t) {
            left.set(surface.left, t, l, b);
            canvas.clipRect(left);
            canvas.drawBitmap(bitmap, null, surface, paint);
            canvas.restoreToCount(save);
            save = canvas.save();
        }
        if (r < surface.right && b > t) {
            right.set(r, t, surface.right, b);
            canvas.clipRect(right);
            canvas.drawBitmap(bitmap, null, surface, paint);
        }
        canvas.restoreToCount(save);
    }

    public Bitmap bitmap() { return bitmap; }

    private static float clamp01(float value) {
        return Float.isFinite(value) ? Math.max(0.0f, Math.min(1.0f, value)) : 0.0f;
    }
}
