/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.crashes;

/** Pure monotonic play-time accounting driven by MIDlet lifecycle stage transitions. */
final class MidletSessionPlayStats {
	private boolean reachedRunning;
	private Long firstRunningWallTimeMillis;
	private long accumulatedActiveMillis;
	private Long activeSegmentStartElapsedRealtimeMillis;

	void transition(MidletSessionJournal.Stage previous, MidletSessionJournal.Stage next,
			long wallTimeMillis, long elapsedRealtimeMillis) {
		if (previous == MidletSessionJournal.Stage.RUNNING && next != MidletSessionJournal.Stage.RUNNING) {
			stopActiveSegment(elapsedRealtimeMillis);
		}
		if (next == MidletSessionJournal.Stage.RUNNING && previous != MidletSessionJournal.Stage.RUNNING) {
			if (!reachedRunning) {
				reachedRunning = true;
				firstRunningWallTimeMillis = wallTimeMillis;
			}
			if (activeSegmentStartElapsedRealtimeMillis == null) {
				activeSegmentStartElapsedRealtimeMillis = elapsedRealtimeMillis;
			}
		}
	}

	void finishActiveSegment(long elapsedRealtimeMillis) {
		stopActiveSegment(elapsedRealtimeMillis);
	}

	Snapshot snapshot() {
		return new Snapshot(
				reachedRunning,
				firstRunningWallTimeMillis,
				accumulatedActiveMillis,
				activeSegmentStartElapsedRealtimeMillis
		);
	}

	private void stopActiveSegment(long elapsedRealtimeMillis) {
		Long started = activeSegmentStartElapsedRealtimeMillis;
		if (started == null) {
			return;
		}
		long delta = elapsedRealtimeMillis >= started ? elapsedRealtimeMillis - started : 0L;
		accumulatedActiveMillis = saturatingAdd(accumulatedActiveMillis, delta);
		activeSegmentStartElapsedRealtimeMillis = null;
	}

	private static long saturatingAdd(long left, long right) {
		if (right <= 0L) {
			return left;
		}
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	static final class Snapshot {
		final boolean reachedRunning;
		final Long firstRunningWallTimeMillis;
		final long accumulatedActiveMillis;
		final Long activeSegmentStartElapsedRealtimeMillis;

		Snapshot(boolean reachedRunning, Long firstRunningWallTimeMillis,
				 long accumulatedActiveMillis, Long activeSegmentStartElapsedRealtimeMillis) {
			this.reachedRunning = reachedRunning;
			this.firstRunningWallTimeMillis = firstRunningWallTimeMillis;
			this.accumulatedActiveMillis = accumulatedActiveMillis;
			this.activeSegmentStartElapsedRealtimeMillis = activeSegmentStartElapsedRealtimeMillis;
		}
	}
}
