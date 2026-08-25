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

/** Selects which guest-visible wall-clock contract is used by a timing session. */
public final class TimingMode {
	/** All guest wall-clock and relative timing APIs follow the emulation clock. */
	public static final int FULL_GUEST_TIME = 0;
	/** Wall-clock APIs remain real while relative guest delays remain speed-scaled. */
	public static final int REAL_WALL_CLOCK = 1;

	private TimingMode() {
	}

	public static boolean isValid(int mode) {
		return mode == FULL_GUEST_TIME || mode == REAL_WALL_CLOCK;
	}

	/** Returns the safe migration fallback for a missing or malformed persisted value. */
	public static int sanitize(int mode) {
		return isValid(mode) ? mode : FULL_GUEST_TIME;
	}

	public static int[] values() {
		return new int[]{FULL_GUEST_TIME, REAL_WALL_CLOCK};
	}
}
