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

package io.github.h3nb.jlmodplus.config;

/** Stable persisted metric bits shared by the Java runtime and Compose settings. */
public final class PerformanceOverlayOptions {
	public static final int FPS = 1;
	public static final int CAP = 1 << 1;
	/** Guest publications; preserves the pre-release bit identity. */
	public static final int GUEST_FPS = 1 << 2;
	public static final int SPEED = 1 << 3;
	public static final int FRAME_INTERVAL = 1 << 4;
	public static final int P95_INTERVAL = 1 << 5;
	public static final int MAX_INTERVAL = 1 << 6;
	public static final int COALESCED = 1 << 7;
	public static final int PAINT = 1 << 8;
	public static final int COPY = 1 << 9;
	public static final int SUBMIT = 1 << 10;
	public static final int INPUT_QUEUE = 1 << 11;
	public static final int FRAME_QUEUE = 1 << 12;
	public static final int CPU = 1 << 13;
	public static final int RAM = 1 << 14;
	public static final int JAVA_HEAP = 1 << 15;
	public static final int NATIVE_HEAP = 1 << 16;
	public static final int CPU_TEMP = 1 << 17;
	public static final int GPU_TEMP = 1 << 18;
	public static final int BATTERY_TEMP = 1 << 19;
	public static final int THERMAL = 1 << 20;
	public static final int RENDERER = 1 << 21;
	public static final int DISPLAY = 1 << 22;
	public static final int RENDER_INTERVAL = 1 << 23;
	public static final int RENDER_P95_INTERVAL = 1 << 24;
	public static final int RENDER_MAX_INTERVAL = 1 << 25;
	public static final int ALL = (1 << 26) - 1;
	public static final int MINIMAL = FPS | CAP | SPEED;
	public static final int STANDARD = MINIMAL | GUEST_FPS | FRAME_INTERVAL | CPU | RAM | DISPLAY;

	public static final int TOP_LEFT = 0;
	public static final int TOP_RIGHT = 1;
	public static final int BOTTOM_LEFT = 2;
	public static final int BOTTOM_RIGHT = 3;

	private PerformanceOverlayOptions() {
	}

	/** An empty selection is intentional; never silently re-enable metrics. */
	public static int sanitize(int metrics) {
		return metrics & ALL;
	}

	public static boolean requiresFrameMetrics(int metrics) {
		return (sanitize(metrics) & (FPS | GUEST_FPS | COALESCED)) != 0;
	}

	public static boolean requiresRendererMetrics(int metrics) {
		return (sanitize(metrics) & (FPS | COALESCED)) != 0;
	}

	public static int sanitizePosition(int position) {
		return position >= TOP_LEFT && position <= BOTTOM_RIGHT ? position : TOP_LEFT;
	}
}
