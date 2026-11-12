// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

/** Distinguishes temporary GPU upload failures from targets requiring a size change. */
final class TextureUploadRetry {
    static final long RETRY_DELAY_MILLIS = 500L;
    private static final long RETRY_DELAY_NANOS = RETRY_DELAY_MILLIS * 1_000_000L;

    private int failedWidth = -1;
    private int failedHeight = -1;
    private int failedScale = -1;
    private long retryAfterNanos;

    boolean shouldSkip(int width, int height, int scale, long nowNanos) {
        return width == failedWidth && height == failedHeight && scale == failedScale
                && nowNanos < retryAfterNanos;
    }

    void recordFailure(int width, int height, int scale, boolean retryable, long nowNanos) {
        failedWidth = width;
        failedHeight = height;
        failedScale = scale;
        retryAfterNanos = retryable ? nowNanos + RETRY_DELAY_NANOS : Long.MAX_VALUE;
    }

    void reset() {
        failedWidth = -1;
        failedHeight = -1;
        failedScale = -1;
        retryAfterNanos = 0L;
    }
}
