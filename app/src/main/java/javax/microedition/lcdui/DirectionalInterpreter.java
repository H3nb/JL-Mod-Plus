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
 * Stateful radial digital-direction quantizer with angular-sector hysteresis.
 */
final class DirectionalInterpreter {
	static final float PRESS_RADIUS = 0.20f;
	static final float RELEASE_RADIUS = 0.14f;
	static final float ANGULAR_HYSTERESIS_RADIANS = (float) Math.toRadians(6.0);
	static final float ANALOG_PRESS_RADIUS = 0.50f;
	static final float ANALOG_RELEASE_RADIUS = 0.35f;
	static final float ANALOG_ANGULAR_HYSTERESIS_RADIANS = (float) Math.toRadians(7.5);
	private static final float ANALOG_THRESHOLD_EPSILON = 0.000001f;

	private static final int FOUR_WAY_SECTORS = 4;
	private static final int EIGHT_WAY_SECTORS = 8;
	private static final float TWO_PI = (float) (Math.PI * 2.0);

	static final class Direction {
		static final Direction CENTER = new Direction(0, 0);
		static final Direction RIGHT = new Direction(1, 0);
		static final Direction DOWN_RIGHT = new Direction(1, 1);
		static final Direction DOWN = new Direction(0, 1);
		static final Direction DOWN_LEFT = new Direction(-1, 1);
		static final Direction LEFT = new Direction(-1, 0);
		static final Direction UP_LEFT = new Direction(-1, -1);
		static final Direction UP = new Direction(0, -1);
		static final Direction UP_RIGHT = new Direction(1, -1);

		final int horizontal;
		final int vertical;

		Direction(int horizontal, int vertical) {
			this.horizontal = horizontal;
			this.vertical = vertical;
		}
	}

	private static final Direction[] FOUR_WAY = {
			Direction.RIGHT,
			Direction.DOWN,
			Direction.LEFT,
			Direction.UP,
	};
	private static final Direction[] EIGHT_WAY = {
			Direction.RIGHT,
			Direction.DOWN_RIGHT,
			Direction.DOWN,
			Direction.DOWN_LEFT,
			Direction.LEFT,
			Direction.UP_LEFT,
			Direction.UP,
			Direction.UP_RIGHT,
	};

	private Direction current = Direction.CENTER;
	private int sectorCount = EIGHT_WAY_SECTORS;

	Direction update(float x, float y) {
		return update(x, y, EIGHT_WAY_SECTORS);
	}

	Direction update(float x, float y, int requestedSectorCount) {
		return update(x, y, requestedSectorCount, false);
	}

	Direction updateAnalog(float x, float y, int requestedSectorCount) {
		return update(x, y, requestedSectorCount, true);
	}

	private Direction update(float x, float y, int requestedSectorCount, boolean analog) {
		int nextSectorCount = requestedSectorCount == FOUR_WAY_SECTORS
				? FOUR_WAY_SECTORS : EIGHT_WAY_SECTORS;
		if (sectorCount != nextSectorCount) {
			sectorCount = nextSectorCount;
			current = Direction.CENTER;
		}

		float safeX = sanitize(x);
		float safeY = sanitize(y);
		float rawMagnitude = (float) Math.hypot(safeX, safeY);
		float magnitude = analog
				? AnalogRadialProcessor.processMagnitude(rawMagnitude) : rawMagnitude;
		float pressRadius = analog ? ANALOG_PRESS_RADIUS : PRESS_RADIUS;
		float releaseRadius = analog ? ANALOG_RELEASE_RADIUS : RELEASE_RADIUS;
		float angularHysteresis = analog
				? ANALOG_ANGULAR_HYSTERESIS_RADIANS : ANGULAR_HYSTERESIS_RADIANS;
		if (current == Direction.CENTER) {
			if (magnitude < pressRadius - (analog ? ANALOG_THRESHOLD_EPSILON : 0.0f)) {
				return current;
			}
			current = nearestDirection(angleOf(safeX, safeY), sectorCount);
			return current;
		}

		if (magnitude <= releaseRadius + (analog ? ANALOG_THRESHOLD_EPSILON : 0.0f)) {
			current = Direction.CENTER;
			return current;
		}

		float angle = angleOf(safeX, safeY);
		Direction[] sectors = sectors(sectorCount);
		float step = TWO_PI / sectors.length;
		float currentCenter = indexOf(sectors, current) * step;
		if (angularDistance(angle, currentCenter) <= step / 2.0f + angularHysteresis) {
			return current;
		}
		current = nearestDirection(angle, sectorCount);
		return current;
	}

	void reset() {
		current = Direction.CENTER;
		sectorCount = EIGHT_WAY_SECTORS;
	}

	private static Direction nearestDirection(float angle, int sectorCount) {
		Direction[] sectors = sectors(sectorCount);
		float step = TWO_PI / sectors.length;
		int index = (int) Math.floor((angle + step / 2.0f) / step) % sectors.length;
		return sectors[index];
	}

	private static Direction[] sectors(int sectorCount) {
		return sectorCount == FOUR_WAY_SECTORS ? FOUR_WAY : EIGHT_WAY;
	}

	private static int indexOf(Direction[] sectors, Direction direction) {
		for (int i = 0; i < sectors.length; i++) {
			if (sectors[i] == direction) {
				return i;
			}
		}
		return 0;
	}

	private static float angleOf(float x, float y) {
		float angle = (float) Math.atan2(y, x);
		return angle < 0.0f ? angle + TWO_PI : angle;
	}

	private static float angularDistance(float first, float second) {
		float distance = Math.abs(first - second);
		return distance > Math.PI ? TWO_PI - distance : distance;
	}

	private static float sanitize(float value) {
		if (!Float.isFinite(value)) return 0.0f;
		return Math.max(-1.0f, Math.min(1.0f, value));
	}
}
