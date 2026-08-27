/*
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

package javax.microedition.shell.timing;

/**
 * Pure adaptive search for the highest sustainable runtime speed.
 *
 * <p>The governor compares delivered game frames per unit of guest time with a short 1x
 * calibration. It probes upward aggressively while the workload is active, tolerates transient
 * weak samples, and periodically retries a settled upper bound so changing device conditions can
 * be discovered. Recommendations are continuous percentages rather than picker presets.</p>
 */
final class AutoSpeedGovernor {
	private static final double MIN_ACTIVE_FPS = 4.0d;
	private static final int CALIBRATION_SAMPLES = 2;
	private static final double HEALTHY_RATIO = 0.82d;
	private static final double SEVERE_OVERLOAD_RATIO = 0.35d;
	private static final int WEAK_SAMPLES_BEFORE_BACKOFF = 2;
	private static final int IDLE_SAMPLES_BEFORE_BACKOFF = 4;
	private static final int SETTLED_SAMPLES_BEFORE_REPROBE = 4;

	enum Phase {
		CALIBRATING,
		WAITING_FOR_ACTIVITY,
		SEARCHING,
		STABLE,
		BACKING_OFF
	}

	static final class Decision {
		final int speedPercent;
		final Phase phase;

		Decision(int speedPercent, Phase phase) {
			this.speedPercent = speedPercent;
			this.phase = phase;
		}
	}

	private double calibrationFpsTotal;
	private int calibrationSamples;
	private double baselineGuestFps;
	private int lastStablePercent = EmulationSpeed.NORMAL_PERCENT;
	private int failedPercent;
	private int weakSamples;
	private int idleSamples;
	private int settledSamples;

	void reset() {
		calibrationFpsTotal = 0d;
		calibrationSamples = 0;
		baselineGuestFps = 0d;
		lastStablePercent = EmulationSpeed.NORMAL_PERCENT;
		failedPercent = 0;
		weakSamples = 0;
		idleSamples = 0;
		settledSamples = 0;
	}

	Decision sample(int currentPercent, long gameFrames, long elapsedNanos) {
		if (elapsedNanos <= 0L) {
			return new Decision(currentPercent, Phase.WAITING_FOR_ACTIVITY);
		}
		double hostFps = gameFrames * 1_000_000_000d / elapsedNanos;
		if (baselineGuestFps == 0d) {
			return calibrate(currentPercent, hostFps);
		}

		if (gameFrames == 0L) {
			idleSamples++;
			if (currentPercent > EmulationSpeed.NORMAL_PERCENT
					&& idleSamples >= IDLE_SAMPLES_BEFORE_BACKOFF) {
				int next = Math.max(
						EmulationSpeed.NORMAL_PERCENT,
						(int) (((long) currentPercent * 3L) / 4L));
				resetSearchBounds(next, currentPercent);
				return new Decision(next, Phase.BACKING_OFF);
			}
			return new Decision(currentPercent, Phase.WAITING_FOR_ACTIVITY);
		}
		idleSamples = 0;
		if (hostFps < MIN_ACTIVE_FPS) {
			weakSamples = 0;
			return new Decision(currentPercent, Phase.WAITING_FOR_ACTIVITY);
		}

		double guestFps = hostFps * EmulationSpeed.NORMAL_PERCENT / currentPercent;
		double healthRatio = guestFps / baselineGuestFps;
		if (healthRatio >= HEALTHY_RATIO) {
			weakSamples = 0;
			lastStablePercent = Math.max(lastStablePercent, currentPercent);
			if (failedPercent > currentPercent) {
				int gap = failedPercent - currentPercent;
				int tolerance = Math.max(2, currentPercent / 50);
				if (gap <= tolerance) {
					settledSamples++;
					if (settledSamples < SETTLED_SAMPLES_BEFORE_REPROBE) {
						return new Decision(currentPercent, Phase.STABLE);
					}
					int retryPercent = failedPercent;
					failedPercent = 0;
					settledSamples = 0;
					return new Decision(retryPercent, Phase.SEARCHING);
				} else {
					settledSamples = 0;
					return new Decision(probeBetween(currentPercent, failedPercent), Phase.SEARCHING);
				}
			}
			return new Decision(probeAbove(currentPercent, healthRatio), Phase.SEARCHING);
		}

		if (healthRatio > SEVERE_OVERLOAD_RATIO
				&& ++weakSamples < WEAK_SAMPLES_BEFORE_BACKOFF) {
			return new Decision(currentPercent, Phase.SEARCHING);
		}
		weakSamples = 0;
		int estimatedSafe = Math.max(
				EmulationSpeed.NORMAL_PERCENT,
				Math.min(currentPercent - 1,
						(int) Math.round(currentPercent * healthRatio * 0.97d)));
		int next;
		if (currentPercent > lastStablePercent) {
			next = Math.max(lastStablePercent, estimatedSafe);
		} else {
			next = estimatedSafe;
			lastStablePercent = next;
		}
		failedPercent = currentPercent;
		settledSamples = 0;
		return new Decision(next, Phase.BACKING_OFF);
	}

	private Decision calibrate(int currentPercent, double hostFps) {
		if (currentPercent != EmulationSpeed.NORMAL_PERCENT) {
			return new Decision(EmulationSpeed.NORMAL_PERCENT, Phase.CALIBRATING);
		}
		if (hostFps < MIN_ACTIVE_FPS) {
			calibrationFpsTotal = 0d;
			calibrationSamples = 0;
			return new Decision(currentPercent, Phase.WAITING_FOR_ACTIVITY);
		}
		calibrationFpsTotal += hostFps;
		calibrationSamples++;
		if (calibrationSamples < CALIBRATION_SAMPLES) {
			return new Decision(currentPercent, Phase.CALIBRATING);
		}
		baselineGuestFps = calibrationFpsTotal / calibrationSamples;
		return new Decision(probeAbove(currentPercent, 1d), Phase.SEARCHING);
	}

	private void resetSearchBounds(int stableCandidate, int failedCandidate) {
		lastStablePercent = Math.min(lastStablePercent, stableCandidate);
		failedPercent = failedCandidate;
		settledSamples = 0;
	}

	private static int probeBetween(int lower, int upper) {
		long gap = (long) upper - lower;
		return lower + (int) Math.max(1L, (gap * 3L + 4L) / 5L);
	}

	private static int probeAbove(int currentPercent, double healthRatio) {
		double factor = Math.max(1.50d, Math.min(2.00d, healthRatio * 2.00d));
		double candidate = currentPercent * factor;
		if (candidate >= Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return Math.max(currentPercent + 1, (int) Math.round(candidate));
	}
}
