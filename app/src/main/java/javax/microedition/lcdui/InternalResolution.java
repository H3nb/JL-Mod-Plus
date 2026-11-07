// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

/**
 * Research target sizing only. This is NOT final output scaling or guest logical resolution.
 * One display target may coexist with another guest target, its published copy and GPU texture.
 */
final class InternalResolution {
    static final int MAX_SCALE = 10;
    private static final int MAX_TARGET_SIDE = 4096;
    private static final long TARGET_BUDGET_BYTES = 160L * 1024L * 1024L;
    private static final int ACCOUNTED_PLANES = 4;

    private InternalResolution() {}

    static int resolve(int requested, int logicalWidth, int logicalHeight,
            int viewportWidth, int viewportHeight) {
        if (logicalWidth <= 0 || logicalHeight <= 0) return 1;
        int desired;
        if (requested == 0) {
            if (viewportWidth <= 0 || viewportHeight <= 0) return 1;
            desired = Math.min(viewportWidth / logicalWidth, viewportHeight / logicalHeight);
        } else {
            desired = requested;
        }
        desired = Math.max(1, Math.min(MAX_SCALE, desired));
        for (int scale = desired; scale > 1; scale--) {
            long physicalWidth = (long) logicalWidth * scale;
            long physicalHeight = (long) logicalHeight * scale;
            long bytes = physicalWidth * physicalHeight * 4L * ACCOUNTED_PLANES;
            if (physicalWidth <= MAX_TARGET_SIDE && physicalHeight <= MAX_TARGET_SIDE
                    && bytes <= TARGET_BUDGET_BYTES) return scale;
        }
        return 1;
    }
}
