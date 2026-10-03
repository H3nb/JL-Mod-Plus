// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.video;

import static org.junit.Assert.*;

import org.junit.Test;

public class VideoTimelineTest {
    @Test
    public void audioMasterHoldsOnUnderflowThenContinuesAfterShorterTrackAndFreezesOnPause() {
        VideoTimeline clock = new VideoTimeline();
        clock.seek(0, 1000000000L, true);
        clock.start(1000000000L);
        clock.observeAudio(500000, 1500000000L);
        assertEquals(500000, clock.time(2500000000L));
        clock.finishAudio(700000, 2500000000L);
        assertEquals(1200000, clock.time(3000000000L));
        clock.pause(3000000000L);
        assertEquals(1200000, clock.time(5000000000L));
        clock.start(5000000000L);
        assertEquals(1300000, clock.time(5100000000L));
    }

    @Test
    public void pureVideoSeekReanchorsAndNewAudioIterationCannotKeepOldContinuation() {
        VideoTimeline clock = new VideoTimeline();
        clock.seek(250000, 1000000000L, false);
        clock.start(1000000000L);
        assertEquals(750000, clock.time(1500000000L));
        clock.seek(100000, 2000000000L, true);
        assertEquals(100000, clock.time(3000000000L));
        clock.observeAudio(110000, 3100000000L);
        assertEquals(110000, clock.time(4000000000L));
        assertFalse(clock.continuing());
    }

    @Test
    public void schedulingHoldsEarlyOutputAndDropsOnlyBeyondOneFrame() {
        assertEquals(50000000L, VideoTimeline.delayNanos(100000, 50000));
        assertEquals(0, VideoTimeline.delayNanos(50000, 100000));
        assertFalse(VideoTimeline.late(50000, 110000, 66666));
        assertTrue(VideoTimeline.late(50000, 120000, 66666));
    }

    @Test
    public void endUsesTrackDurationOrLastDecodedPtsEvenWhenFramesWereDropped() {
        assertEquals(-1, VideoTimeline.duration(-1, 500000));
        assertEquals(-1, VideoTimeline.duration(1000000, -1));
        assertEquals(1000000, VideoTimeline.duration(1000000, 500000));
        // VFR metadata's nominal frame interval must not extend a known track end.
        assertEquals(1000000, VideoTimeline.endTime(980000, 66666, 1000000, 0));
        assertEquals(1046666, VideoTimeline.endTime(980000, 66666, -1, 0));
        // A seek beyond a shorter video track must keep the shared target.
        assertEquals(2000000, VideoTimeline.endTime(980000, 66666, 1000000, 2000000));
    }
}
