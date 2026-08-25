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

/** Immutable diagnostic result for one timing monitor sample. */
public final class TimingTelemetrySnapshot {
	private final int targetPercent;
	private final int measuredPercent;

	TimingTelemetrySnapshot(int targetPercent, int measuredPercent) {
		this.targetPercent = targetPercent;
		this.measuredPercent = measuredPercent;
	}

	public int targetPercent() {
		return targetPercent;
	}

	/** Returns -1 until a valid same-generation sample interval is available. */
	public int measuredPercent() {
		return measuredPercent;
	}

	public boolean hasMeasuredPercent() {
		return measuredPercent >= 0;
	}
}
