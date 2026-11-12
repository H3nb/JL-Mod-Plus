// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

/** Selects the physical density for runtime-owned 2D render targets. */
final class InternalResolution {
    static final int MAX_SCALE = 10;
    static final long MAX_BITMAP_PIXELS = Integer.MAX_VALUE / 4L;

    private InternalResolution() {}

    static boolean fitsBitmap(int width, int height, int scale) {
        if (width <= 0 || height <= 0 || scale < 1) return false;
        long pixelsWide = (long) width * scale;
        long pixelsHigh = (long) height * scale;
        return pixelsWide <= Integer.MAX_VALUE
                && pixelsHigh <= MAX_BITMAP_PIXELS / pixelsWide;
    }

    @FunctionalInterface
    interface ScaleAttempt {
        void allocate(int scale);
    }

    /** Allocate replacement surfaces before the caller publishes any new state. */
    static int allocateWithFallback(int desiredScale, ScaleAttempt attempt) {
        for (int scale = Math.max(1, Math.min(MAX_SCALE, desiredScale)); ; scale--) {
            try {
                attempt.allocate(scale);
                return scale;
            } catch (OutOfMemoryError error) {
                if (scale == 1) throw error;
            }
        }
    }

    static int resolve(int requested, int logicalWidth, int logicalHeight,
            int viewportWidth, int viewportHeight) {
        return resolve(requested, logicalWidth, logicalHeight, logicalHeight,
                viewportWidth, viewportHeight);
    }

    static int resolve(int requested, int logicalWidth, int logicalHeight, int backingHeight,
            int viewportWidth, int viewportHeight) {
        if (logicalWidth <= 0 || logicalHeight <= 0 || backingHeight <= 0) return 1;
        int desired = requested == 0
                ? viewportWidth <= 0 || viewportHeight <= 0 ? 1
                    : Math.min(viewportWidth / logicalWidth, viewportHeight / logicalHeight)
                : requested;
        desired = Math.max(1, Math.min(MAX_SCALE, desired));
        for (int factor = desired; factor > 1; factor--) {
            if (fitsBitmap(logicalWidth, Math.max(logicalHeight, backingHeight), factor))
                return factor;
        }
        return 1;
    }
}
