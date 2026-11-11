// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.graphics;

/**
 * Smooths corners in pixel art without changing source pixels.
 * Local edge-directed algorithm, not an implementation of xBRZ.
 */
public final class EdgeAwareScaler {
    private EdgeAwareScaler() {}

    public static int[] scale(int[] source, int width, int height, int factor) {
        long size = (long) width * height * factor * factor;
        if (source == null || width <= 0 || height <= 0 || factor < 2 || factor > 10
                || (long) source.length < (long) width * height
                || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid edge-aware image size");
        }
        int[] result = new int[(int) size];
        int stride = width * factor;
        int[] corners = new int[4];
        boolean[] eligible = new boolean[4];
        float[] weights = new float[4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int center = source[y * width + x];
                boolean any = false;
                for (int corner = 0; corner < 4; corner++) {
                    int horizontal = (corner & 1) == 0 ? -1 : 1;
                    int vertical = (corner & 2) == 0 ? -1 : 1;
                    int a = sample(source, width, height, x + horizontal, y);
                    int b = sample(source, width, height, x, y + vertical);
                    int diagonal = sample(source, width, height,
                            x + horizontal, y + vertical);
                    corners[corner] = diagonal;
                    eligible[corner] = distance(a, b) <= 40
                            && distance(a, diagonal) <= 60
                            && distance(b, diagonal) <= 60
                            && distance(center, diagonal) >= 70;
                    any |= eligible[corner];
                }
                for (int py = 0; py < factor; py++) {
                    for (int px = 0; px < factor; px++) {
                        int index = (y * factor + py) * stride + x * factor + px;
                        if (!any) {
                            result[index] = center;
                            continue;
                        }
                        float u = (px + 0.5f) / factor;
                        float v = (py + 0.5f) / factor;
                        float total = 0;
                        for (int corner = 0; corner < 4; corner++) {
                            if (!eligible[corner]) {
                                weights[corner] = 0;
                                continue;
                            }
                            float cx = (corner & 1) == 0 ? 1 - u : u;
                            float cy = (corner & 2) == 0 ? 1 - v : v;
                            float weight = Math.max(0f, cx + cy - 1f) * 0.42f;
                            weights[corner] = weight;
                            total += weight;
                        }
                        if (total == 0f) {
                            result[index] = center;
                            continue;
                        }
                        float normalization = total > 0.8f ? 0.8f / total : 1f;
                        float centerWeight = 1f - total * normalization;
                        float alpha = ((center >>> 24) & 255) * centerWeight;
                        float red = ((center >>> 16) & 255) * alpha;
                        float green = ((center >>> 8) & 255) * alpha;
                        float blue = (center & 255) * alpha;
                        for (int corner = 0; corner < 4; corner++) {
                            float weight = weights[corner] * normalization;
                            if (weight == 0f) continue;
                            int pixel = corners[corner];
                            float contribution = ((pixel >>> 24) & 255) * weight;
                            alpha += contribution;
                            red += ((pixel >>> 16) & 255) * contribution;
                            green += ((pixel >>> 8) & 255) * contribution;
                            blue += (pixel & 255) * contribution;
                        }
                        int outAlpha = Math.min(255, Math.round(alpha));
                        result[index] = outAlpha == 0 ? 0 : (outAlpha << 24)
                                | (Math.min(255, Math.round(red / alpha)) << 16)
                                | (Math.min(255, Math.round(green / alpha)) << 8)
                                | Math.min(255, Math.round(blue / alpha));
                    }
                }
            }
        }
        return result;
    }

    private static int sample(int[] pixels, int width, int height, int x, int y) {
        return pixels[Math.max(0, Math.min(height - 1, y)) * width
                + Math.max(0, Math.min(width - 1, x))];
    }

    private static int distance(int first, int second) {
        int a = (first >>> 24) & 255;
        int b = (second >>> 24) & 255;
        int r1 = ((first >>> 16) & 255) * a / 255;
        int g1 = ((first >>> 8) & 255) * a / 255;
        int b1 = (first & 255) * a / 255;
        int r2 = ((second >>> 16) & 255) * b / 255;
        int g2 = ((second >>> 8) & 255) * b / 255;
        int b2 = (second & 255) * b / 255;
        return Math.abs(r1 - r2) + Math.abs(g1 - g2)
                + Math.abs(b1 - b2) + 2 * Math.abs(a - b);
    }
}
