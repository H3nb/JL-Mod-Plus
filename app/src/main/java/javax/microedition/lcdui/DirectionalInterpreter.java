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

package javax.microedition.lcdui;

/**
 * Stateful 8-way digital direction interpreter with per-axis hysteresis.
 */
final class DirectionalInterpreter {
	static final float PRESS_THRESHOLD = 0.40f;
	static final float RELEASE_THRESHOLD = 0.28f;

	static final class Direction {
		static final Direction CENTER = new Direction(0, 0);
		private static final Direction LEFT = new Direction(-1, 0);
		private static final Direction RIGHT = new Direction(1, 0);
		private static final Direction UP = new Direction(0, -1);
		private static final Direction DOWN = new Direction(0, 1);
		private static final Direction UP_LEFT = new Direction(-1, -1);
		private static final Direction UP_RIGHT = new Direction(1, -1);
		private static final Direction DOWN_LEFT = new Direction(-1, 1);
		private static final Direction DOWN_RIGHT = new Direction(1, 1);

		final int horizontal;
		final int vertical;

		Direction(int horizontal, int vertical) {
			this.horizontal = horizontal;
			this.vertical = vertical;
		}
	}

	private int horizontal;
	private int vertical;

	Direction update(float x, float y) {
		horizontal = updateAxis(horizontal, sanitize(x));
		vertical = updateAxis(vertical, sanitize(y));
		return directionFor(horizontal, vertical);
	}

	void reset() {
		horizontal = 0;
		vertical = 0;
	}

	private static Direction directionFor(int horizontal, int vertical) {
		if (horizontal < 0) {
			if (vertical < 0) return Direction.UP_LEFT;
			if (vertical > 0) return Direction.DOWN_LEFT;
			return Direction.LEFT;
		}
		if (horizontal > 0) {
			if (vertical < 0) return Direction.UP_RIGHT;
			if (vertical > 0) return Direction.DOWN_RIGHT;
			return Direction.RIGHT;
		}
		if (vertical < 0) return Direction.UP;
		if (vertical > 0) return Direction.DOWN;
		return Direction.CENTER;
	}

	private static int updateAxis(int current, float value) {
		if (current == 0) {
			if (value >= PRESS_THRESHOLD) return 1;
			if (value <= -PRESS_THRESHOLD) return -1;
			return 0;
		}
		if (current > 0) {
			if (value <= -PRESS_THRESHOLD) return -1;
			return value < RELEASE_THRESHOLD ? 0 : 1;
		}
		if (value >= PRESS_THRESHOLD) return 1;
		return value > -RELEASE_THRESHOLD ? 0 : -1;
	}

	private static float sanitize(float value) {
		if (!Float.isFinite(value)) return 0.0f;
		return Math.max(-1.0f, Math.min(1.0f, value));
	}
}
