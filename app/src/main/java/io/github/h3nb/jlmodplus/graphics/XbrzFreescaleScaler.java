/*
 * Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
 * Licensed under GPLv2 or any later version.
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Modified for JL-Mod Plus.
 * CPU translation of the Azahar xBRZ Freescale texture filter:
 * https://github.com/azahar-emu/azahar/blob/6b4e7f2e694514fdf2f72dc20368609b7cd4912e/src/video_core/host_shaders/texture_filtering/xbrz_freescale.frag
 *
 * The four equivalent rotated corner rules replace duplicated GLSL branches.
 * RGB interpolation is alpha-weighted for Android bitmap compositing, rather
 * than raw straight-alpha GLSL mix. Opaque inputs follow the shader's rules.
 */
package io.github.h3nb.jlmodplus.graphics;

import java.util.Arrays;

/**
 * xBRZ Freescale pixel-art texture reconstruction at integer 2x–10x.
 * Source and destination use unpremultiplied 32-bit ARGB.
 */
public final class XbrzFreescaleScaler {
    private static final int NONE = 0;
    private static final int NORMAL = 1;
    private static final int DOMINANT = 2;

    private static final float DOMINANT_THRESHOLD = 3.6f;
    private static final float STEEP_THRESHOLD = 2.2f;
    private static final float PIXEL_EQUAL_TOLERANCE = 30.0f;
    private static final float HALF_DIAGONAL = 0.7071067811865476f;

    // Right-bottom, left-bottom, right-top, left-top (the GLSL z,w,y,x order).
    private static final int[] ADJACENT_FIRST = {2, 0, 3, 1};
    private static final int[] ADJACENT_SECOND = {1, 3, 0, 2};

    private XbrzFreescaleScaler() {}

    public static int[] scale(int[] source, int width, int height, int factor) {
        long size = (long) width * height * factor * factor;
        if (source == null || width < 1 || height < 1 || factor < 2 || factor > 10
                || (long) source.length < (long) width * height || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid xBRZ source dimensions or factor");
        }

        int[] output = new int[(int) size];
        int[] neighborhood = new int[25];
        int[] modes = new int[4];
        int[] neighbors = new int[4];
        float[] originY = new float[4];
        float[] directionX = new float[4];
        float[] directionY = new float[4];
        float[] inverseEdgeLength = new float[4];

        int stride = width * factor;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int sy = Math.max(0, Math.min(height - 1, y + dy));
                    for (int dx = -2; dx <= 2; dx++) {
                        int sx = Math.max(0, Math.min(width - 1, x + dx));
                        neighborhood[(dy + 2) * 5 + dx + 2] = source[sy * width + sx];
                    }
                }

                int e = neighborhood[12];
                for (int corner = 0; corner < 4; corner++) {
                    int f = sample(neighborhood, corner, 1, 0);
                    int h = sample(neighborhood, corner, 0, 1);
                    int i = sample(neighborhood, corner, 1, 1);
                    if ((e == f && h == i) || (e == h && f == i)) {
                        modes[corner] = NONE;
                        continue;
                    }

                    int g = sample(neighborhood, corner, -1, 1);
                    int c = sample(neighborhood, corner, 1, -1);
                    int d = sample(neighborhood, corner, -1, 0);
                    int b = sample(neighborhood, corner, 0, -1);

                    float along = distance(g, e) + distance(e, c)
                            + distance(sample(neighborhood, corner, 0, 2), i)
                            + distance(i, sample(neighborhood, corner, 2, 0))
                            + 4f * distance(h, f);
                    float across = distance(d, h)
                            + distance(h, sample(neighborhood, corner, 1, 2))
                            + distance(b, f)
                            + distance(f, sample(neighborhood, corner, 2, 1))
                            + 4f * distance(e, i);
                    modes[corner] = along < across && e != f && e != h
                            ? (DOMINANT_THRESHOLD * along < across ? DOMINANT : NORMAL)
                            : NONE;
                }

                // Pixel art usually has broad non-edge regions. Their exact
                // shader-equivalent result is the unmodified source color.
                if (modes[0] == NONE && modes[1] == NONE
                        && modes[2] == NONE && modes[3] == NONE) {
                    for (int oy = 0; oy < factor; oy++) {
                        int offset = (y * factor + oy) * stride + x * factor;
                        Arrays.fill(output, offset, offset + factor, e);
                    }
                    continue;
                }

                for (int corner = 0; corner < 4; corner++) {
                    if (modes[corner] == NONE) continue;

                    int a = sample(neighborhood, corner, -1, -1);
                    int b = sample(neighborhood, corner, 0, -1);
                    int c = sample(neighborhood, corner, 1, -1);
                    int d = sample(neighborhood, corner, -1, 0);
                    int f = sample(neighborhood, corner, 1, 0);
                    int g = sample(neighborhood, corner, -1, 1);
                    int h = sample(neighborhood, corner, 0, 1);
                    int i = sample(neighborhood, corner, 1, 1);

                    boolean lineBlend = modes[corner] == DOMINANT || !(
                            (modes[ADJACENT_FIRST[corner]] != NONE && !pixelEqual(e, g))
                            || (modes[ADJACENT_SECOND[corner]] != NONE && !pixelEqual(e, c))
                            || (pixelEqual(g, h) && pixelEqual(h, i) && pixelEqual(i, f)
                                    && pixelEqual(f, c) && !pixelEqual(e, i)));

                    float ox = 0f;
                    float oy = HALF_DIAGONAL;
                    float dx = 1f;
                    float dy = -1f;
                    if (lineBlend) {
                        float fg = distance(f, g);
                        float hc = distance(h, c);
                        boolean shallow = STEEP_THRESHOLD * fg <= hc && e != g && d != g;
                        boolean steep = STEEP_THRESHOLD * hc <= fg && e != c && b != c;
                        oy = shallow ? 0.25f : 0.5f;
                        if (shallow) dx += 1f;
                        if (steep) dy -= 1f;
                    }
                    originY[corner] = oy;
                    directionX[corner] = dx;
                    directionY[corner] = dy;
                    inverseEdgeLength[corner] = factor
                            / (float) Math.sqrt(dx * dx + dy * dy);
                    // GLSL: mix(H, F, step(ColorDist(E,F), ColorDist(E,H))).
                    neighbors[corner] = distance(e, h) >= distance(e, f) ? f : h;
                }

                for (int oy = 0; oy < factor; oy++) {
                    float py = (oy + 0.5f) / factor - 0.5f;
                    int out = (y * factor + oy) * stride + x * factor;
                    for (int ox = 0; ox < factor; ox++) {
                        float px = (ox + 0.5f) / factor - 0.5f;
                        int result = e;
                        for (int corner = 0; corner < 4; corner++) {
                            if (modes[corner] == NONE) continue;

                            float localX, localY;
                            switch (corner) {
                                case 1: localX = py; localY = -px; break;
                                case 2: localX = -py; localY = px; break;
                                case 3: localX = -px; localY = -py; break;
                                default: localX = px; localY = py;
                            }
                            float dx = directionX[corner];
                            float dy = directionY[corner];
                            // Signed distance to the reconstructed edge in
                            // output-pixel units, equivalent to GetLeftRatio().
                            float signedDistance = (localX * -dy
                                    + (localY - originY[corner]) * dx)
                                    * inverseEdgeLength[corner];
                            float ratio = smoothStep(-HALF_DIAGONAL, HALF_DIAGONAL,
                                    signedDistance);
                            result = blend(result, neighbors[corner], ratio);
                        }
                        output[out + ox] = result;
                    }
                }
            }
        }
        return output;
    }

    private static int sample(int[] p, int corner, int x, int y) {
        int u, v;
        switch (corner) {
            case 1: u = -y; v = x; break;
            case 2: u = y; v = -x; break;
            case 3: u = -x; v = -y; break;
            default: u = x; v = y;
        }
        return p[(v + 2) * 5 + u + 2];
    }

    /**
     * The Azahar shader uses BT.2020 YCbCr color distance. This equivalent
     * expression keeps RGB/alpha in byte units, with tolerance 30 instead
     * of dividing every component by 255.
     */
    private static float distance(int a, int b) {
        int ar = (a >>> 24) & 255, br = (b >>> 24) & 255;
        float dr = ((a >>> 16) & 255) - ((b >>> 16) & 255);
        float dg = ((a >>> 8) & 255) - ((b >>> 8) & 255);
        float db = (a & 255) - (b & 255);
        float y = 0.2627f * dr + 0.6780f * dg + 0.0593f * db;
        float cb = (db - y) * (0.5f / (1f - 0.0593f));
        float cr = (dr - y) * (0.5f / (1f - 0.2627f));
        float alphaDiff = ar - br;
        return (float) Math.sqrt((ar * br / (255f * 255f))
                * (y * y + cb * cb + cr * cr) + alphaDiff * alphaDiff);
    }

    private static boolean pixelEqual(int a, int b) {
        return distance(a, b) < PIXEL_EQUAL_TOLERANCE;
    }

    private static float smoothStep(float start, float end, float value) {
        float t = Math.max(0f, Math.min(1f, (value - start) / (end - start)));
        return t * t * (3f - 2f * t);
    }

    // Preserve color at transparent edges when the destination is premultiplied
    // for Android Canvas. Opaque pixels match GLSL mix apart from rounding.
    public static int blend(int a, int b, float weight) {
        if (weight <= 0f) return a;
        if (weight >= 1f) return b;

        int aa = a >>> 24, ba = b >>> 24;
        float alpha = aa * (1f - weight) + ba * weight;
        if (alpha < 0.5f) return 0;
        float wa = (aa * (1f - weight)) / alpha;
        float wb = (ba * weight) / alpha;
        int r = channel(a >>> 16 & 255, b >>> 16 & 255, wa, wb);
        int g = channel(a >>> 8 & 255, b >>> 8 & 255, wa, wb);
        int blue = channel(a & 255, b & 255, wa, wb);
        return (Math.min(255, Math.round(alpha)) << 24)
                | (r << 16) | (g << 8) | blue;
    }

    private static int channel(int a, int b, float wa, float wb) {
        return Math.max(0, Math.min(255, Math.round(a * wa + b * wb)));
    }
}
