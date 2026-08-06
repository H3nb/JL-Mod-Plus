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

package javax.microedition.lcdui.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM characterization of the soft bar overflow guards (no View is created in
 * these tests). The Android-dependent popup path itself cannot run without a
 * device, but the guard that decides whether the popup may open at all is pure.
 */
public class SoftBarMenuGuardTest {
	@Test
	public void showMenuIsGuardedForUpToThreeCommands() {
		assertFalse(ScreenSoftBar.shouldShowMenu(0));
		assertFalse(ScreenSoftBar.shouldShowMenu(1));
		assertFalse(ScreenSoftBar.shouldShowMenu(2));
		assertFalse(ScreenSoftBar.shouldShowMenu(3));
	}

	@Test
	public void showMenuAllowedOnlyForOverflowCommands() {
		assertTrue(ScreenSoftBar.shouldShowMenu(4));
		assertTrue(ScreenSoftBar.shouldShowMenu(5));
	}

	@Test
	public void effectiveSkipNeverExceedsCommandCount() {
		assertEquals(1, AbstractSoftKeysBar.effectiveSkip(2, 1));
		assertEquals(2, AbstractSoftKeysBar.effectiveSkip(2, 2));
		assertEquals(2, AbstractSoftKeysBar.effectiveSkip(2, 5));
	}

	@Test
	public void effectiveSkipClampsNegativeAndEmpty() {
		assertEquals(0, AbstractSoftKeysBar.effectiveSkip(-1, 5));
		assertEquals(0, AbstractSoftKeysBar.effectiveSkip(2, 0));
		assertEquals(0, AbstractSoftKeysBar.effectiveSkip(0, 0));
	}
}
