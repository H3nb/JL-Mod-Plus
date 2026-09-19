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

package javax.microedition.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MenuLongPressStateTest {
	private static final int DEVICE_ONE = 1;
	private static final int DEVICE_TWO = 2;
	private static final int KEYCODE_BACK = 4;
	private static final int KEYCODE_BUTTON_START = 108;

	@Test
	public void overlappingMenuSourcesKeepLongPressSuppressionIndependent() {
		MenuLongPressState state = new MenuLongPressState();

		state.begin(DEVICE_ONE, KEYCODE_BACK);
		state.begin(DEVICE_ONE, KEYCODE_BUTTON_START);
		state.markHandled(DEVICE_ONE, KEYCODE_BACK);

		assertFalse(state.consumeHandled(DEVICE_ONE, KEYCODE_BUTTON_START));
		assertTrue(state.consumeHandled(DEVICE_ONE, KEYCODE_BACK));
		assertFalse(state.consumeHandled(DEVICE_ONE, KEYCODE_BACK));
	}

	@Test
	public void sameKeyCodeFromDifferentDevicesUsesDifferentSourceState() {
		MenuLongPressState state = new MenuLongPressState();
		long first = MenuLongPressState.sourceToken(DEVICE_ONE, KEYCODE_BACK);
		long second = MenuLongPressState.sourceToken(DEVICE_TWO, KEYCODE_BACK);

		assertNotEquals(first, second);

		state.begin(DEVICE_ONE, KEYCODE_BACK);
		state.begin(DEVICE_TWO, KEYCODE_BACK);
		state.markHandled(DEVICE_ONE, KEYCODE_BACK);

		assertFalse(state.consumeHandled(DEVICE_TWO, KEYCODE_BACK));
		assertTrue(state.consumeHandled(DEVICE_ONE, KEYCODE_BACK));
	}

	@Test
	public void newDownResetsOnlyItsOwnHandledState() {
		MenuLongPressState state = new MenuLongPressState();

		state.markHandled(DEVICE_ONE, KEYCODE_BACK);
		state.markHandled(DEVICE_ONE, KEYCODE_BUTTON_START);
		state.begin(DEVICE_ONE, KEYCODE_BUTTON_START);

		assertFalse(state.consumeHandled(DEVICE_ONE, KEYCODE_BUTTON_START));
		assertTrue(state.consumeHandled(DEVICE_ONE, KEYCODE_BACK));
	}
}
