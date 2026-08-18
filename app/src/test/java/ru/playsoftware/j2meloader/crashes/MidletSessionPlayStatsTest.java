/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MidletSessionPlayStatsTest {
	@Test
	public void firstRunningStartsMonotonicSegment() {
		MidletSessionPlayStats stats = new MidletSessionPlayStats();

		stats.transition(
				MidletSessionJournal.Stage.STARTING,
				MidletSessionJournal.Stage.RUNNING,
				1_000L,
				5_000L);

		MidletSessionPlayStats.Snapshot snapshot = stats.snapshot();
		assertTrue(snapshot.reachedRunning);
		assertEquals(Long.valueOf(1_000L), snapshot.firstRunningWallTimeMillis);
		assertEquals(0L, snapshot.accumulatedActiveMillis);
		assertEquals(Long.valueOf(5_000L), snapshot.activeSegmentStartElapsedRealtimeMillis);
	}

	@Test
	public void pauseResumeAccumulatesOnlyRunningSegments() {
		MidletSessionPlayStats stats = new MidletSessionPlayStats();
		stats.transition(MidletSessionJournal.Stage.STARTING, MidletSessionJournal.Stage.RUNNING,
				1_000L, 5_000L);
		stats.transition(MidletSessionJournal.Stage.RUNNING, MidletSessionJournal.Stage.PAUSING,
				1_500L, 5_400L);
		stats.transition(MidletSessionJournal.Stage.PAUSING, MidletSessionJournal.Stage.PAUSED,
				1_600L, 5_500L);
		stats.transition(MidletSessionJournal.Stage.PAUSED, MidletSessionJournal.Stage.STARTING,
				2_000L, 8_000L);
		stats.transition(MidletSessionJournal.Stage.STARTING, MidletSessionJournal.Stage.RUNNING,
				2_100L, 8_100L);
		stats.transition(MidletSessionJournal.Stage.RUNNING, MidletSessionJournal.Stage.STOPPING,
				2_700L, 8_650L);

		MidletSessionPlayStats.Snapshot snapshot = stats.snapshot();
		assertTrue(snapshot.reachedRunning);
		assertEquals(Long.valueOf(1_000L), snapshot.firstRunningWallTimeMillis);
		assertEquals(950L, snapshot.accumulatedActiveMillis);
		assertNull(snapshot.activeSegmentStartElapsedRealtimeMillis);
	}

	@Test
	public void fatalTailIsFoldedExactlyOnce() {
		MidletSessionPlayStats stats = new MidletSessionPlayStats();
		stats.transition(MidletSessionJournal.Stage.STARTING, MidletSessionJournal.Stage.RUNNING,
				100L, 1_000L);

		stats.finishActiveSegment(1_250L);
		stats.finishActiveSegment(1_500L);

		MidletSessionPlayStats.Snapshot snapshot = stats.snapshot();
		assertEquals(250L, snapshot.accumulatedActiveMillis);
		assertNull(snapshot.activeSegmentStartElapsedRealtimeMillis);
	}

	@Test
	public void neverRunningSessionRemainsIneligible() {
		MidletSessionPlayStats stats = new MidletSessionPlayStats();
		stats.transition(MidletSessionJournal.Stage.PREPARING, MidletSessionJournal.Stage.INITIALIZING,
				100L, 200L);
		stats.transition(MidletSessionJournal.Stage.INITIALIZING, MidletSessionJournal.Stage.STOPPING,
				150L, 250L);

		MidletSessionPlayStats.Snapshot snapshot = stats.snapshot();
		assertFalse(snapshot.reachedRunning);
		assertNull(snapshot.firstRunningWallTimeMillis);
		assertEquals(0L, snapshot.accumulatedActiveMillis);
		assertNull(snapshot.activeSegmentStartElapsedRealtimeMillis);
	}

	@Test
	public void backwardElapsedClockDoesNotSubtractActiveTime() {
		MidletSessionPlayStats stats = new MidletSessionPlayStats();
		stats.transition(MidletSessionJournal.Stage.STARTING, MidletSessionJournal.Stage.RUNNING,
				100L, 5_000L);
		stats.transition(MidletSessionJournal.Stage.RUNNING, MidletSessionJournal.Stage.PAUSING,
				200L, 4_900L);

		assertEquals(0L, stats.snapshot().accumulatedActiveMillis);
	}
}
