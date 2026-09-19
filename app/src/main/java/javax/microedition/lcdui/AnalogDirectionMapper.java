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

import io.github.h3nb.jlmodplus.config.ProfileModel;

/** Maps semantic analog sectors onto the selected guest-key convention. */
final class AnalogDirectionMapper {
	private AnalogDirectionMapper() {
	}

	static int sectorCountForMode(int mode) {
		return ProfileModel.sanitizeAnalogDirectionMode(mode)
				== ProfileModel.ANALOG_DIRECTION_4_WAY ? 4 : 8;
	}

	static int horizontalKey(DirectionalInterpreter.Direction direction, int mode) {
		if (ProfileModel.sanitizeAnalogDirectionMode(mode) == ProfileModel.ANALOG_DIRECTION_NUMERIC) {
			return 0;
		}
		if (direction.horizontal < 0) return Canvas.KEY_LEFT;
		if (direction.horizontal > 0) return Canvas.KEY_RIGHT;
		return 0;
	}

	static int verticalKey(DirectionalInterpreter.Direction direction, int mode) {
		if (ProfileModel.sanitizeAnalogDirectionMode(mode) == ProfileModel.ANALOG_DIRECTION_NUMERIC) {
			return 0;
		}
		if (direction.vertical < 0) return Canvas.KEY_UP;
		if (direction.vertical > 0) return Canvas.KEY_DOWN;
		return 0;
	}

	static int numericKey(DirectionalInterpreter.Direction direction, int mode) {
		if (ProfileModel.sanitizeAnalogDirectionMode(mode) != ProfileModel.ANALOG_DIRECTION_NUMERIC) {
			return 0;
		}
		if (direction == DirectionalInterpreter.Direction.UP_LEFT) return Canvas.KEY_NUM1;
		if (direction == DirectionalInterpreter.Direction.UP) return Canvas.KEY_NUM2;
		if (direction == DirectionalInterpreter.Direction.UP_RIGHT) return Canvas.KEY_NUM3;
		if (direction == DirectionalInterpreter.Direction.LEFT) return Canvas.KEY_NUM4;
		if (direction == DirectionalInterpreter.Direction.RIGHT) return Canvas.KEY_NUM6;
		if (direction == DirectionalInterpreter.Direction.DOWN_LEFT) return Canvas.KEY_NUM7;
		if (direction == DirectionalInterpreter.Direction.DOWN) return Canvas.KEY_NUM8;
		if (direction == DirectionalInterpreter.Direction.DOWN_RIGHT) return Canvas.KEY_NUM9;
		return 0;
	}
}
