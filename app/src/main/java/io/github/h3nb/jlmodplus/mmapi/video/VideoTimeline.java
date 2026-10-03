// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.video;

/** One clock for A/V, or monotonic continuation for pure/finished-audio video. */
final class VideoTimeline {
    private long media, anchor;
    private boolean running, audioEnded;

    void seek(long time, long now, boolean hasAudio) {
        media = time;
        anchor = now;
        audioEnded = !hasAudio;
    }

    void start(long now) {
        anchor = now;
        running = true;
    }

    void pause(long now) {
        media = time(now);
        anchor = now;
        running = false;
    }

    void observeAudio(long time, long now) {
        if (!audioEnded) {
            media = Math.max(media, time);
            anchor = now;
        }
    }

    void finishAudio(long time, long now) {
        observeAudio(time, now);
        audioEnded = true;
        anchor = now;
    }

    long time(long now) {
        return media + (running && audioEnded ? Math.max(0, now - anchor) / 1000 : 0);
    }

    boolean continuing() {
        return audioEnded;
    }

    // Hold only one decoded output; never submit far-future Surface frames.
    static long delayNanos(long pts, long media) {
        return Math.max(0, pts - media) * 1000;
    }

    static boolean late(long pts, long media, long frameDuration) {
        return media - pts > Math.max(20000, frameDuration);
    }

    static long duration(long first, long second) {
        return first < 0 || second < 0 ? -1 : Math.max(first, second);
    }

    static long endTime(long lastPts, long frameDuration, long declaredDuration, long target) {
        long decoded = lastPts < 0 ? 0 : lastPts + frameDuration;
        return Math.max(
                target,
                declaredDuration >= 0 && declaredDuration >= lastPts ? declaredDuration : decoded);
    }
}
