// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

/** Selects the physical density for runtime-owned 2D render targets. */
final class InternalResolution {
    static final int MAX_SCALE = 10;
    // Conservative prototype allocation limits, not detected GPU capabilities.
    static final int MAX_TARGET_SIDE = 4096;
    static final long MAX_ESTIMATED_BYTES = 160L * 1024L * 1024L;

    private InternalResolution() {}

    static int resolve(int requested, int logicalWidth, int logicalHeight,
            int viewportWidth, int viewportHeight) {
        if (logicalWidth <= 0 || logicalHeight <= 0) return 1;
        int desired = requested == 0
                ? viewportWidth <= 0 || viewportHeight <= 0 ? 1
                    : Math.min(viewportWidth / logicalWidth, viewportHeight / logicalHeight)
                : requested;
        desired = Math.max(1, Math.min(MAX_SCALE, desired));
        for (int factor = desired; factor > 1; factor--) {
            long w = (long) logicalWidth * factor;
            long h = (long) logicalHeight * factor;
            // Guest paint, GameCanvas/other source, published frame, GL upload.
            long estimated = w * h * 4L * 4L;
            if (w <= MAX_TARGET_SIDE && h <= MAX_TARGET_SIDE && estimated <= MAX_ESTIMATED_BYTES)
                return factor;
        }
        return 1;
    }
}
