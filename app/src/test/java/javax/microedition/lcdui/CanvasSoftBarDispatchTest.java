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

package javax.microedition.lcdui;

import static javax.microedition.lcdui.Canvas.SoftKeyAction.CONSUME;
import static javax.microedition.lcdui.Canvas.SoftKeyAction.FIRST_COMMAND;
import static javax.microedition.lcdui.Canvas.SoftKeyAction.OPEN_MENU;
import static javax.microedition.lcdui.Canvas.SoftKeyAction.RAW_KEY;
import static javax.microedition.lcdui.Canvas.SoftKeyAction.SECOND_COMMAND;
import static javax.microedition.lcdui.Canvas.resolveSoftKeyAction;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * JVM characterization of the virtual soft key dispatch decision.
 * The private Canvas.SoftBar show/fire paths themselves need a device;
 * this covers the pure resolver that routes the L/R virtual keys.
 */
public class CanvasSoftBarDispatchTest {
	@Test
	public void zeroCommandsAlwaysFallThrough() {
		assertEquals(RAW_KEY, resolveSoftKeyAction(0, true, true, false));
		assertEquals(RAW_KEY, resolveSoftKeyAction(0, true, false, false));
		assertEquals(RAW_KEY, resolveSoftKeyAction(0, false, true, true));
		assertEquals(RAW_KEY, resolveSoftKeyAction(0, false, false, true));
	}

	@Test
	public void fullscreenTwoCommandsWithListenerDispatchDistinctCommands() {
		assertEquals(FIRST_COMMAND, resolveSoftKeyAction(2, true, true, false));
		assertEquals(SECOND_COMMAND, resolveSoftKeyAction(2, true, true, true));
		assertNotEquals(FIRST_COMMAND, SECOND_COMMAND);
	}

	@Test
	public void fullscreenTwoCommandsWithoutListenerStayRaw() {
		assertEquals(CONSUME, resolveSoftKeyAction(2, true, false, false));
		assertEquals(RAW_KEY, resolveSoftKeyAction(2, true, false, true));
	}

	@Test
	public void fullscreenSingleCommandNeverDispatches() {
		assertEquals(RAW_KEY, resolveSoftKeyAction(1, true, true, false));
		assertEquals(OPEN_MENU, resolveSoftKeyAction(1, true, true, true));
		assertEquals(RAW_KEY, resolveSoftKeyAction(1, true, false, false));
		assertEquals(RAW_KEY, resolveSoftKeyAction(1, true, false, true));
	}

	@Test
	public void fullscreenOverflowWithListenerUsesPopup() {
		assertEquals(OPEN_MENU, resolveSoftKeyAction(3, true, true, false));
		assertEquals(OPEN_MENU, resolveSoftKeyAction(4, true, true, true));
		assertEquals(OPEN_MENU, resolveSoftKeyAction(5, true, true, true));
	}

	@Test
	public void fullscreenOverflowWithoutListenerConsumesLeftOnly() {
		assertEquals(CONSUME, resolveSoftKeyAction(3, true, false, false));
		assertEquals(RAW_KEY, resolveSoftKeyAction(3, true, false, true));
	}

	@Test
	public void nonFullscreenLeftAlwaysFiresFirstCommand() {
		assertEquals(FIRST_COMMAND, resolveSoftKeyAction(1, false, true, false));
		assertEquals(FIRST_COMMAND, resolveSoftKeyAction(2, false, false, false));
		assertEquals(FIRST_COMMAND, resolveSoftKeyAction(5, false, true, false));
	}

	@Test
	public void nonFullscreenRightSingleCommandStaysRaw() {
		assertEquals(RAW_KEY, resolveSoftKeyAction(1, false, true, true));
		assertEquals(RAW_KEY, resolveSoftKeyAction(1, false, false, true));
	}

	@Test
	public void nonFullscreenRightTwoCommandsDispatchOrConsume() {
		assertEquals(SECOND_COMMAND, resolveSoftKeyAction(2, false, true, true));
		assertEquals(CONSUME, resolveSoftKeyAction(2, false, false, true));
	}

	@Test
	public void nonFullscreenRightOverflowUsesPopup() {
		assertEquals(OPEN_MENU, resolveSoftKeyAction(3, false, true, true));
		assertEquals(OPEN_MENU, resolveSoftKeyAction(3, false, false, true));
	}
}
