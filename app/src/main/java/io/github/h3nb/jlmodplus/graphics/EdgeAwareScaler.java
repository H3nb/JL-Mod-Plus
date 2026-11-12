// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.graphics;

/** Reconstructs smooth pixel-art outlines without filtering uniform regions. */
public final class EdgeAwareScaler {
    private static final int MIN_EDGE_CONTRAST = 48;
    private static final int COLOR_WEIGHT = 256;

    private EdgeAwareScaler() {}

    public static int[] scale(int[] source, int width, int height, int factor) {
        long sourcePixels = (long) width * height;
        if (source == null || width <= 0 || height <= 0 || factor < 2 || factor > 10
                || sourcePixels > source.length
                || sourcePixels > Integer.MAX_VALUE / ((long) factor * factor)) {
            throw new IllegalArgumentException("Invalid smooth HD image size");
        }

        int[] output = new int[(int) (sourcePixels * factor * factor)];
        int stride = width * factor;
        int[] cornerColors = new int[4];
        int[] coverage = coverage(factor);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int center = source[y * width + x];
                int corners = 0;
                for (int corner = 0; corner < 4; corner++) {
                    int dx = (corner & 1) == 0 ? -1 : 1;
                    int dy = (corner & 2) == 0 ? -1 : 1;
                    int horizontal = sample(source, width, height, x + dx, y);
                    int vertical = sample(source, width, height, x, y + dy);
                    int diagonal = sample(source, width, height, x + dx, y + dy);
                    int contrast = Math.min(distance(center, horizontal),
                            distance(center, vertical));
                    int horizontalMatch = distance(horizontal, diagonal);
                    int verticalMatch = distance(vertical, diagonal);
                    if (contrast >= MIN_EDGE_CONTRAST
                            && distance(horizontal, vertical) * 3 <= contrast
                            && Math.min(horizontalMatch, verticalMatch) * 2 <= contrast) {
                        corners |= 1 << corner;
                        cornerColors[corner] = diagonal;
                    }
                }

                // Leave isolated one-pixel features intact.
                if (corners == 15) corners = 0;

                for (int py = 0; py < factor; py++) {
                    int bottom = py * 2 >= factor ? 2 : 0;
                    int nearY = bottom == 0 ? py : factor - 1 - py;
                    int row = (y * factor + py) * stride + x * factor;
                    for (int px = 0; px < factor; px++) {
                        int right = px * 2 >= factor ? 1 : 0;
                        int corner = bottom + right;
                        boolean middle = (factor & 1) != 0
                                && (px == factor / 2 || py == factor / 2);
                        int nearX = right == 0 ? px : factor - 1 - px;
                        int weight = middle || (corners & (1 << corner)) == 0 ? 0
                                : coverage[nearY * factor + nearX];
                        output[row + px] = weight == 0 ? center
                                : weight == COLOR_WEIGHT ? cornerColors[corner]
                                : blend(center, cornerColors[corner], weight);
                    }
                }
            }
        }
        return output;
    }

    private static int[] coverage(int factor) {
        int[] weights = new int[factor * factor];
        for (int y = 0; y < factor; y++) {
            for (int x = 0; x < factor; x++) {
                float value = 0.5f + 0.75f * (factor * 0.5f - 1f - x - y);
                weights[y * factor + x] = Math.max(0,
                        Math.min(COLOR_WEIGHT, Math.round(value * COLOR_WEIGHT)));
            }
        }
        return weights;
    }

    private static int sample(int[] pixels, int width, int height, int x, int y) {
        return pixels[Math.max(0, Math.min(height - 1, y)) * width
                + Math.max(0, Math.min(width - 1, x))];
    }

    private static int distance(int first, int second) {
        int a = (first >>> 24) & 255;
        int b = (second >>> 24) & 255;
        int red = Math.abs(((first >>> 16) & 255) * a / 255
                - ((second >>> 16) & 255) * b / 255);
        int green = Math.abs(((first >>> 8) & 255) * a / 255
                - ((second >>> 8) & 255) * b / 255);
        int blue = Math.abs((first & 255) * a / 255 - (second & 255) * b / 255);
        return red + green + blue + 2 * Math.abs(a - b);
    }

    private static int blend(int center, int neighbor, int weight) {
        int centerAlpha = center >>> 24;
        int neighborAlpha = neighbor >>> 24;
        int centerWeight = COLOR_WEIGHT - weight;
        int weightedCenterAlpha = centerAlpha * centerWeight;
        int weightedNeighborAlpha = neighborAlpha * weight;
        int totalAlpha = weightedCenterAlpha + weightedNeighborAlpha;
        if (totalAlpha == 0) return 0;
        int red = (((center >>> 16) & 255) * weightedCenterAlpha
                + ((neighbor >>> 16) & 255) * weightedNeighborAlpha
                + totalAlpha / 2) / totalAlpha;
        int green = (((center >>> 8) & 255) * weightedCenterAlpha
                + ((neighbor >>> 8) & 255) * weightedNeighborAlpha
                + totalAlpha / 2) / totalAlpha;
        int blue = ((center & 255) * weightedCenterAlpha
                + (neighbor & 255) * weightedNeighborAlpha
                + totalAlpha / 2) / totalAlpha;
        return ((totalAlpha + 128) / COLOR_WEIGHT) << 24
                | red << 16 | green << 8 | blue;
    }
}
