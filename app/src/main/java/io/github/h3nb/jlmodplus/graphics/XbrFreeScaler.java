/*
 * Copyright (C) 2011-2015 Hyllian.
 * SPDX-License-Identifier: MIT
 *
 * Adapted for JL-Mod Plus from Hyllian's xBR Lv3 shader:
 * https://github.com/libretro/glsl-shaders/blob/master/xbr/shaders/xbr-lv3.glsl
 * The four rotated edge rules are classified once per source pixel and
 * reconstructed at the requested integer scale. No xBRZ code is used.
 */
package io.github.h3nb.jlmodplus.graphics;

import java.util.Arrays;

/** Reconstructs pixel-art outlines before host composition without changing guest pixels. */
public final class XbrFreeScaler {
    private static final int NEAR = 60;
    private static final int STRICT = 12;
    private static final int COLOR_WEIGHT = 256;
    private static final int LINE_45 = 1;
    private static final int LINE_30 = 2;
    private static final int LINE_60 = 4;
    private static final int LINE_15 = 8;
    private static final int LINE_75 = 16;

    private XbrFreeScaler() {}

    @FunctionalInterface
    public interface RowWriter {
        void write(int firstRow, int[] argb, int rows);
    }

    public static int[] scale(int[] source, int width, int height, int factor) {
        validate(source, width, height, factor);
        int outputWidth = width * factor;
        int[] output = new int[outputWidth * height * factor];
        scaleRows(source, width, height, factor, (firstRow, argb, rows) ->
                System.arraycopy(argb, 0, output, firstRow * outputWidth, rows * outputWidth));
        return output;
    }

    public static void scaleRows(int[] source, int width, int height, int factor,
            RowWriter writer) {
        validate(source, width, height, factor);
        if (writer == null) throw new IllegalArgumentException("Missing row writer");
        int outputWidth = width * factor;
        int[] rows = new int[outputWidth * factor];
        int[][] coverage = buildCoverage(factor);
        int[] neighborhood = new int[25];
        int[] rawNeighborhood = new int[25];
        int[] masks = new int[4];
        int[] colors = new int[4];
        int[] contrasts = new int[4];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int center = source[y * width + x];
                loadNeighborhood(source, width, height, x, y, neighborhood, rawNeighborhood);
                int active = 0;
                if (!uniformNeighborhood(neighborhood)) {
                    for (int corner = 0; corner < 4; corner++) {
                        masks[corner] = classify(neighborhood, corner, false)
                                | mirrorMask(classify(neighborhood, corner, true));
                        if (masks[corner] == 0) continue;
                        int rightDifference = difference(neighborhood[12],
                                neighbor(neighborhood, corner, 1, 0));
                        int bottomDifference = difference(neighborhood[12],
                                neighbor(neighborhood, corner, 0, 1));
                        if (bottomDifference == rightDifference
                                && neighbor(rawNeighborhood, corner, 1, 0)
                                != neighbor(rawNeighborhood, corner, 0, 1)) {
                            masks[corner] = 0;
                            continue;
                        }
                        boolean chooseBottom = bottomDifference < rightDifference;
                        colors[corner] = neighbor(rawNeighborhood, corner,
                                chooseBottom ? 0 : 1, chooseBottom ? 1 : 0);
                        contrasts[corner] = Math.min(rightDifference, bottomDifference);
                        active |= masks[corner];
                    }
                }
                if (active == 0) {
                    for (int py = 0; py < factor; py++) {
                        int row = py * outputWidth + x * factor;
                        Arrays.fill(rows, row, row + factor, center);
                    }
                    continue;
                }
                for (int py = 0; py < factor; py++) {
                    int row = py * outputWidth + x * factor;
                    for (int px = 0; px < factor; px++) {
                        int cell = py * factor + px;
                        int bestWeight = 0;
                        int bestColor = center;
                        int bestDistance = Integer.MAX_VALUE;
                        for (int corner = 0; corner < 4; corner++) {
                            int mask = masks[corner];
                            if (mask == 0) continue;
                            int weight = 0;
                            for (int line = 0; line < 5; line++) {
                                if ((mask & (1 << line)) != 0) {
                                    weight = Math.max(weight, coverage[corner * 5 + line][cell]);
                                }
                            }
                            int distance = contrasts[corner];
                            if (weight > bestWeight || (weight == bestWeight && weight > 0
                                    && distance < bestDistance)) {
                                bestWeight = weight;
                                bestColor = colors[corner];
                                bestDistance = distance;
                            } else if (weight == bestWeight && weight > 0
                                    && distance == bestDistance && colors[corner] != bestColor) {
                                bestColor = center;
                            }
                        }
                        rows[row + px] = bestWeight == 0 ? center
                                : bestWeight == COLOR_WEIGHT ? bestColor
                                : blend(center, bestColor, bestWeight);
                    }
                }
            }
            writer.write(y * factor, rows, factor);
        }
    }

    private static void validate(int[] source, int width, int height, int factor) {
        long pixels = (long) width * height;
        if (source == null || width < 1 || height < 1 || factor < 2 || factor > 10
                || pixels > source.length || pixels > Integer.MAX_VALUE / ((long) factor * factor)) {
            throw new IllegalArgumentException("Invalid xBR FreeScale image size");
        }
    }

    private static void loadNeighborhood(int[] source, int width, int height,
            int x, int y, int[] result, int[] raw) {
        for (int dy = -2; dy <= 2; dy++) {
            int sy = Math.max(0, Math.min(height - 1, y + dy)) * width;
            for (int dx = -2; dx <= 2; dx++) {
                int pixel = source[sy + Math.max(0, Math.min(width - 1, x + dx))];
                raw[(dy + 2) * 5 + dx + 2] = pixel;
                int alpha = pixel >>> 24;
                int normalized;
                if (alpha == 255) {
                    normalized = pixel;
                } else if (alpha == 0) {
                    normalized = 0;
                } else {
                    int red = ((pixel >>> 16) & 255) * alpha / 255;
                    int green = ((pixel >>> 8) & 255) * alpha / 255;
                    int blue = (pixel & 255) * alpha / 255;
                    normalized = alpha << 24 | red << 16 | green << 8 | blue;
                }
                result[(dy + 2) * 5 + dx + 2] = normalized;
            }
        }
    }

    private static boolean uniformNeighborhood(int[] pixels) {
        int center = pixels[12];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (pixels[(dy + 2) * 5 + dx + 2] != center) return false;
            }
        }
        return true;
    }

    private static int neighbor(int[] pixels, int corner, int x, int y) {
        int ox, oy;
        switch (corner) {
            case 1: ox = y; oy = -x; break;
            case 2: ox = -x; oy = -y; break;
            case 3: ox = -y; oy = x; break;
            default: ox = x; oy = y;
        }
        return pixels[(oy + 2) * 5 + ox + 2];
    }

    private static int sample(int[] pixels, int corner, boolean transpose, int x, int y) {
        return neighbor(pixels, corner, transpose ? y : x, transpose ? x : y);
    }

    private static int mirrorMask(int mask) {
        return (mask & LINE_45) | (mask & LINE_30) << 1
                | (mask & LINE_60) >> 1 | (mask & LINE_15) << 1
                | (mask & LINE_75) >> 1;
    }

    private static int classify(int[] p, int c, boolean transpose) {
        int e = p[12];
        int f = sample(p, c, transpose, 1, 0), h = sample(p, c, transpose, 0, 1);
        if (difference(e, f) <= STRICT || difference(e, h) <= STRICT) return 0;
        int b = sample(p, c, transpose, 0, -1), d = sample(p, c, transpose, -1, 0);
        int cc = sample(p, c, transpose, 1, -1), g = sample(p, c, transpose, -1, 1);
        int i = sample(p, c, transpose, 1, 1);
        int f4 = sample(p, c, transpose, 2, 0), h5 = sample(p, c, transpose, 0, 2);
        int i4 = sample(p, c, transpose, 2, 1), i5 = sample(p, c, transpose, 1, 2);
        boolean cornerRule = (difference(f, b) >= NEAR && difference(f, cc) >= NEAR)
                || (difference(h, d) >= NEAR && difference(h, g) >= NEAR)
                || (difference(e, i) < NEAR && ((difference(f, f4) >= NEAR
                        && difference(f, i4) >= NEAR) || (difference(h, h5) >= NEAR
                        && difference(h, i5) >= NEAR)))
                || difference(e, g) < NEAR || difference(e, cc) < NEAR;
        if (!cornerRule) return 0;

        int first = difference(e, cc) + difference(g, i) + difference(h5, f4)
                + 4 * difference(h, f);
        int second = difference(h, d) + difference(i5, f) + difference(i4, b)
                + 4 * difference(e, i);
        if (first >= second) return 0;
        int result = LINE_45;
        boolean shallow = 2 * difference(f, g) <= difference(h, cc)
                && difference(e, g) > STRICT && difference(d, g) > STRICT;
        boolean steep = difference(f, g) >= 2 * difference(h, cc)
                && difference(e, cc) > STRICT && difference(b, cc) > STRICT;
        if (shallow) result |= LINE_30;
        if (steep) result |= LINE_60;
        if (shallow && difference(g, sample(p, c, transpose, -2, 1)) < STRICT
                && difference(sample(p, c, transpose, -2, 0), sample(p, c, transpose, -2, 1)) >= STRICT) {
            result |= LINE_15;
        }
        if (steep && difference(cc, sample(p, c, transpose, 1, -2)) < STRICT
                && difference(sample(p, c, transpose, 0, -2), sample(p, c, transpose, 1, -2)) >= STRICT) {
            result |= LINE_75;
        }
        return result;
    }

    private static int difference(int first, int second) {
        return Math.abs((first >>> 24) - (second >>> 24)) * 2
                + Math.abs(((first >>> 16) & 255) - ((second >>> 16) & 255))
                + Math.abs(((first >>> 8) & 255) - ((second >>> 8) & 255))
                + Math.abs((first & 255) - (second & 255));
    }

    private static int[][] buildCoverage(int factor) {
        int area = factor * factor;
        int[][] table = new int[20][area];
        float[] a = {1, 1, 1, 6, 2};
        float[] b = {1, 0.5f, 2, 2, 6};
        float[] offset = {1.5f, 1, 2, 5, 5};
        for (int corner = 0; corner < 4; corner++) {
            for (int y = 0; y < factor; y++) {
                for (int x = 0; x < factor; x++) {
                    float u = (x + 0.5f) / factor, v = (y + 0.5f) / factor;
                    float rotatedU, rotatedV;
                    switch (corner) {
                        case 1: rotatedU = 1 - v; rotatedV = u; break;
                        case 2: rotatedU = 1 - u; rotatedV = 1 - v; break;
                        case 3: rotatedU = v; rotatedV = 1 - u; break;
                        default: rotatedU = u; rotatedV = v;
                    }
                    for (int line = 0; line < 5; line++) {
                        float distance = (a[line] * rotatedV + b[line] * rotatedU
                                - offset[line]) / (float) Math.hypot(a[line], b[line]);
                        float t = Math.max(0, Math.min(1, 0.5f + factor * distance));
                        table[corner * 5 + line][y * factor + x] =
                                Math.round(t * t * (3 - 2 * t) * COLOR_WEIGHT);
                    }
                }
            }
        }
        return table;
    }

    private static int blend(int center, int neighbor, int weight) {
        int alphaA = center >>> 24;
        int alphaB = neighbor >>> 24;
        int a = alphaA * (COLOR_WEIGHT - weight);
        int b = alphaB * weight;
        int total = a + b;
        if (total == 0) return 0;
        int red = (((center >>> 16) & 255) * a + ((neighbor >>> 16) & 255) * b
                + total / 2) / total;
        int green = (((center >>> 8) & 255) * a + ((neighbor >>> 8) & 255) * b
                + total / 2) / total;
        int blue = ((center & 255) * a + (neighbor & 255) * b + total / 2) / total;
        return ((total + COLOR_WEIGHT / 2) / COLOR_WEIGHT) << 24
                | red << 16 | green << 8 | blue;
    }
}
