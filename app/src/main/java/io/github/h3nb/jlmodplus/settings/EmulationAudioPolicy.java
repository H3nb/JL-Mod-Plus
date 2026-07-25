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

package io.github.h3nb.jlmodplus.settings;

/**
 * Process-wide policy for the experimental audio response to emulation speed.
 *
 * <p>The default is deliberately disabled. The policy only controls whether
 * audio players follow emulation speed; it does not change the emulation clock
 * or the speed selected by the user.</p>
 */
public final class EmulationAudioPolicy {
	private static volatile boolean audioSpeedEnabled;

	private EmulationAudioPolicy() {
	}

	public static boolean isAudioSpeedEnabled() {
		return audioSpeedEnabled;
	}

	public static void setAudioSpeedEnabled(boolean enabled) {
		audioSpeedEnabled = enabled;
	}
}
