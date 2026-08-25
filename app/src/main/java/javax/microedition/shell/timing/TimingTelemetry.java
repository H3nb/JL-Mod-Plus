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

import androidx.annotation.NonNull;

/**
 * Host-side sampler for the guest clock. It never schedules guest work and discards intervals
 * that cross a session or speed transition, so the displayed rate cannot mix two mappings.
 */
public final class TimingTelemetry {
	private TimingSnapshot previous;

	@NonNull
	public synchronized TimingTelemetrySnapshot sample(@NonNull TimingSnapshot current) {
		if (current == null) {
			throw new NullPointerException("current");
		}
		int measuredPercent = -1;
		TimingSnapshot prior = previous;
		if (prior != null
				&& prior.generation() == current.generation()
				&& prior.speedPercent() == current.speedPercent()) {
			measuredPercent = TimingMath.measureRatePercent(
					current.guestMonotonicNanos() - prior.guestMonotonicNanos(),
					current.hostMonotonicNanos() - prior.hostMonotonicNanos());
		}
		previous = current;
		return new TimingTelemetrySnapshot(current.speedPercent(), measuredPercent);
	}

	public synchronized void reset() {
		previous = null;
	}
}
