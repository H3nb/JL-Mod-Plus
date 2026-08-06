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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM characterization of the fullscreen soft key dispatch decision.
 * The private Canvas.SoftBar paths themselves need a device; this covers
 * the pure predicate that routes the L/R virtual keys.
 */
public class CanvasSoftBarDispatchTest {
	@Test
	public void twoCommandsFullscreenWithListenerDispatchDirectly() {
		assertTrue(Canvas.isDirectTwoCommandDispatch(2, true, true));
	}

	@Test
	public void singleCommandFullscreenNeverDispatchesDirectly() {
		assertFalse(Canvas.isDirectTwoCommandDispatch(1, true, true));
		assertFalse(Canvas.isDirectTwoCommandDispatch(1, true, false));
	}

	@Test
	public void overflowCommandsFullscreenUsePopup() {
		assertFalse(Canvas.isDirectTwoCommandDispatch(3, true, true));
		assertFalse(Canvas.isDirectTwoCommandDispatch(4, true, true));
		assertFalse(Canvas.isDirectTwoCommandDispatch(5, true, true));
	}

	@Test
	public void nonFullscreenKeepsLegacyPath() {
		assertFalse(Canvas.isDirectTwoCommandDispatch(2, false, true));
		assertFalse(Canvas.isDirectTwoCommandDispatch(2, false, false));
	}

	@Test
	public void twoCommandsFullscreenWithoutListenerNeverDispatches() {
		assertFalse(Canvas.isDirectTwoCommandDispatch(2, true, false));
	}
}
