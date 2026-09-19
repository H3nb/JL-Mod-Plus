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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import io.github.h3nb.jlmodplus.config.ProfileModel;

public class AnalogDirectionMapperTest {
	@Test
	public void fourWayProducesOnlyOneCardinalGuestKey() {
		int mode = ProfileModel.ANALOG_DIRECTION_4_WAY;

		assertMapping(DirectionalInterpreter.Direction.UP, mode, 0, Canvas.KEY_UP, 0);
		assertMapping(DirectionalInterpreter.Direction.RIGHT, mode, Canvas.KEY_RIGHT, 0, 0);
		assertMapping(DirectionalInterpreter.Direction.DOWN, mode, 0, Canvas.KEY_DOWN, 0);
		assertMapping(DirectionalInterpreter.Direction.LEFT, mode, Canvas.KEY_LEFT, 0, 0);
		assertEquals(4, AnalogDirectionMapper.sectorCountForMode(mode));
	}

	@Test
	public void eightWayMapsDiagonalsToTwoDirectionalComponents() {
		int mode = ProfileModel.ANALOG_DIRECTION_8_WAY;

		assertMapping(DirectionalInterpreter.Direction.UP_RIGHT, mode,
				Canvas.KEY_RIGHT, Canvas.KEY_UP, 0);
		assertMapping(DirectionalInterpreter.Direction.DOWN_LEFT, mode,
				Canvas.KEY_LEFT, Canvas.KEY_DOWN, 0);
		assertEquals(8, AnalogDirectionMapper.sectorCountForMode(mode));
	}

	@Test
	public void numericModeUsesPhoneKeypadDirectionsAndNoFireCenter() {
		assertNumeric(DirectionalInterpreter.Direction.UP_LEFT, Canvas.KEY_NUM1);
		assertNumeric(DirectionalInterpreter.Direction.UP, Canvas.KEY_NUM2);
		assertNumeric(DirectionalInterpreter.Direction.UP_RIGHT, Canvas.KEY_NUM3);
		assertNumeric(DirectionalInterpreter.Direction.LEFT, Canvas.KEY_NUM4);
		assertNumeric(DirectionalInterpreter.Direction.RIGHT, Canvas.KEY_NUM6);
		assertNumeric(DirectionalInterpreter.Direction.DOWN_LEFT, Canvas.KEY_NUM7);
		assertNumeric(DirectionalInterpreter.Direction.DOWN, Canvas.KEY_NUM8);
		assertNumeric(DirectionalInterpreter.Direction.DOWN_RIGHT, Canvas.KEY_NUM9);
		assertMapping(DirectionalInterpreter.Direction.CENTER,
				ProfileModel.ANALOG_DIRECTION_NUMERIC, 0, 0, 0);
		assertEquals(8,
				AnalogDirectionMapper.sectorCountForMode(ProfileModel.ANALOG_DIRECTION_NUMERIC));
	}

	private static void assertNumeric(DirectionalInterpreter.Direction direction, int expected) {
		assertMapping(direction, ProfileModel.ANALOG_DIRECTION_NUMERIC, 0, 0, expected);
	}

	private static void assertMapping(
			DirectionalInterpreter.Direction direction,
			int mode,
			int horizontal,
			int vertical,
			int numeric) {
		assertEquals(horizontal, AnalogDirectionMapper.horizontalKey(direction, mode));
		assertEquals(vertical, AnalogDirectionMapper.verticalKey(direction, mode));
		assertEquals(numeric, AnalogDirectionMapper.numericKey(direction, mode));
	}
}
