/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.shell.time;

/**
 * Immutable clock state anchored at one host-clock sample.
 */
public final class SpeedSnapshot {
	private final EmulationSpeed speed;
	private final boolean paused;
	private final long hostAnchorNanos;
	private final long virtualAnchorNanos;
	private final long wallAnchorMillis;
	private final long generation;
	private final boolean stopping;

	SpeedSnapshot(EmulationSpeed speed, boolean paused, long hostAnchorNanos,
			long virtualAnchorNanos, long wallAnchorMillis, long generation) {
		this(speed, paused, hostAnchorNanos, virtualAnchorNanos, wallAnchorMillis,
				generation, false);
	}

	SpeedSnapshot(EmulationSpeed speed, boolean paused, long hostAnchorNanos,
			long virtualAnchorNanos, long wallAnchorMillis, long generation,
			boolean stopping) {
		this.speed = speed;
		this.paused = paused;
		this.hostAnchorNanos = hostAnchorNanos;
		this.virtualAnchorNanos = virtualAnchorNanos;
		this.wallAnchorMillis = wallAnchorMillis;
		this.generation = generation;
		this.stopping = stopping;
	}

	public EmulationSpeed speed() {
		return speed;
	}

	public boolean isPaused() {
		return paused;
	}

	public long generation() {
		return generation;
	}

	public boolean isStopping() {
		return stopping;
	}

	public long hostAnchorNanos() {
		return hostAnchorNanos;
	}

	public long virtualAnchorNanos() {
		return virtualAnchorNanos;
	}

	public long wallAnchorMillis() {
		return wallAnchorMillis;
	}

	public long virtualNanosAt(long hostNanos) {
		long elapsedHostNanos = hostNanos - hostAnchorNanos;
		if (elapsedHostNanos <= 0 || paused) {
			return virtualAnchorNanos;
		}
		long elapsedVirtualNanos = EmulationTimeController.scaleElapsed(
				elapsedHostNanos, speed.numerator(), speed.denominator());
		return EmulationTimeController.saturatedAdd(virtualAnchorNanos, elapsedVirtualNanos);
	}

	public long wallMillisAt(long hostNanos) {
		long virtualNowNanos = virtualNanosAt(hostNanos);
		long virtualElapsedNanos = virtualNowNanos <= virtualAnchorNanos
				? 0L : virtualNowNanos - virtualAnchorNanos;
		if (virtualElapsedNanos <= 0) {
			return wallAnchorMillis;
		}
		return EmulationTimeController.saturatedAdd(
				wallAnchorMillis, virtualElapsedNanos / 1_000_000L);
	}
}
