/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.graphics;

import android.graphics.RectF;

/** Smooth shared-vertex ring mesh for the area outside the guest game rectangle. */
public final class AmbientMesh {
    /** Eight segments per logical interval remove visible GL color bands on large margins. */
    private static final int SUBDIVISIONS_PER_INTERVAL = 8;
    public static final int MAX_AXIS_SIZE = 1 + 4 * SUBDIVISIONS_PER_INTERVAL;
    public static final int MAX_VERTEX_COUNT = MAX_AXIS_SIZE * MAX_AXIS_SIZE;
    public static final int MAX_INDEX_COUNT = (MAX_AXIS_SIZE - 1) * (MAX_AXIS_SIZE - 1) * 6;
    private final float[] x = new float[MAX_AXIS_SIZE];
    private final float[] y = new float[MAX_AXIS_SIZE];
    private final short[] indices = new short[MAX_INDEX_COUNT];
    private int axisSizeX;
    private int axisSizeY;
    private int vertexCount;
    private int indexCount;

    public void build(int displayWidth, int displayHeight, RectF gameRect) {
        vertexCount = 0;
        indexCount = 0;
        if (displayWidth <= 0 || displayHeight <= 0 || gameRect == null) return;
        axisSizeX = buildAxis(displayWidth, gameRect.left, gameRect.centerX(), gameRect.right, x);
        axisSizeY = buildAxis(displayHeight, gameRect.top, gameRect.centerY(), gameRect.bottom, y);
        if (axisSizeX < 2 || axisSizeY < 2) return;
        vertexCount = axisSizeX * axisSizeY;
        for (int row = 0; row < axisSizeY - 1; row++) {
            for (int col = 0; col < axisSizeX - 1; col++) {
                float left = x[col];
                float right = x[col + 1];
                float top = y[row];
                float bottom = y[row + 1];
                if (right <= left || bottom <= top || fullyCovered(left, top, right, bottom, gameRect)) {
                    continue;
                }
                short tl = (short) (row * axisSizeX + col);
                short tr = (short) (tl + 1);
                short bl = (short) (tl + axisSizeX);
                short br = (short) (bl + 1);
                indices[indexCount++] = tl;
                indices[indexCount++] = bl;
                indices[indexCount++] = tr;
                indices[indexCount++] = tr;
                indices[indexCount++] = bl;
                indices[indexCount++] = br;
            }
        }
    }

    private static int buildAxis(float display, float first, float second, float third,
            float[] output) {
        float[] boundaries = {0.0f, first, second, third, display};
        int boundaryCount = 0;
        for (float value : boundaries) {
            float bounded = Math.max(0.0f, Math.min(display, value));
            boolean duplicate = false;
            for (int i = 0; i < boundaryCount; i++) {
                if (Math.abs(boundaries[i] - bounded) < 0.01f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) boundaries[boundaryCount++] = bounded;
        }
        for (int i = 1; i < boundaryCount; i++) {
            float value = boundaries[i];
            int j = i - 1;
            while (j >= 0 && boundaries[j] > value) {
                boundaries[j + 1] = boundaries[j--];
            }
            boundaries[j + 1] = value;
        }
        int count = 0;
        for (int i = 0; i < boundaryCount - 1; i++) {
            float start = boundaries[i];
            float end = boundaries[i + 1];
            for (int segment = 0; segment < SUBDIVISIONS_PER_INTERVAL; segment++) {
                float value = start + (end - start) * segment / SUBDIVISIONS_PER_INTERVAL;
                if (count == 0 || Math.abs(output[count - 1] - value) >= 0.01f) {
                    output[count++] = value;
                }
            }
        }
        if (boundaryCount > 0 && (count == 0
                || Math.abs(output[count - 1] - boundaries[boundaryCount - 1]) >= 0.01f)) {
            output[count++] = boundaries[boundaryCount - 1];
        }
        return count;
    }

    private static boolean fullyCovered(float left, float top, float right, float bottom,
            RectF gameRect) {
        return left >= gameRect.left && top >= gameRect.top
                && right <= gameRect.right && bottom <= gameRect.bottom;
    }

    public int vertexCount() { return vertexCount; }

    public int indexCount() { return indexCount; }

    public short indexAt(int index) { return indices[index]; }

    public float vertexX(int index) {
        return x[index % axisSizeX];
    }

    public float vertexY(int index) {
        return y[index / axisSizeX];
    }

    public float vertexClipX(int index, int displayWidth) {
        return 2.0f * vertexX(index) / displayWidth - 1.0f;
    }

    public float vertexClipY(int index, int displayHeight) {
        return 1.0f - 2.0f * vertexY(index) / displayHeight;
    }
}
