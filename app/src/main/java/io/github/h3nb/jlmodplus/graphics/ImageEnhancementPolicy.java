// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.graphics;

/**
 * Bounded reconstruction of immutable pixel sources before raster composition.
 * Internal resolution is a destination density, not a command to rebuild every
 * sprite at that factor. Returning one means enhancement should be bypassed.
 */
public final class ImageEnhancementPolicy {
    public static final int MAX_RECONSTRUCTION_FACTOR = 4;
    public static final long MAX_RESULT_BYTES = 4L * 1024L * 1024L;

    private ImageEnhancementPolicy() {}

    public static int chooseFactor(int width, int height, int targetScale, int maxSide) {
        if (width <= 0 || height <= 0 || targetScale < 2 || maxSide < 2) return 1;
        long sourcePixels = (long) width * height;
        long maxPixels = MAX_RESULT_BYTES / 4L;
        for (int factor = Math.min(targetScale, MAX_RECONSTRUCTION_FACTOR);
                factor >= 2; factor--) {
            long multiplier = (long) factor * factor;
            if (width <= maxSide / factor && height <= maxSide / factor
                    && sourcePixels <= maxPixels / multiplier) {
                return factor;
            }
        }
        return 1;
    }
}
