/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.lcdui.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VirtualKeyboardLayoutStateTest {
	@Test
	public void oneBaseOverrideLeavesOtherOrientationDerived() {
		VirtualKeyboardLayoutSnapshot portrait = snapshot(3.0f);
		VirtualKeyboardLayoutState state = VirtualKeyboardLayoutState
				.forBase(VirtualControlsKeyboard.TYPE_DPAD_STANDARD)
				.withOverride(VirtualLayoutOrientation.PORTRAIT, portrait);

		assertSame(portrait, state.portraitOverride());
		assertNull(state.landscapeOverride());
		assertEquals(VirtualControlsKeyboard.TYPE_DPAD_STANDARD, state.baseVariant());
		assertTrue(state.hasRenderableSourceFor(VirtualLayoutOrientation.LANDSCAPE));
	}

	@Test
	public void migratedSharedFallbackSurvivesFirstOrientationEdit() {
		VirtualKeyboardLayoutSnapshot shared = snapshot(3.0f);
		VirtualKeyboardLayoutSnapshot portrait = snapshot(8.0f);
		VirtualKeyboardLayoutState state = VirtualKeyboardLayoutState
				.migrated(shared)
				.withOverride(VirtualLayoutOrientation.PORTRAIT, portrait);

		assertSame(shared, state.legacySharedFallback());
		assertSame(portrait, state.portraitOverride());
		assertNull(state.landscapeOverride());
	}

	@Test
	public void migratedFallbackDropsAfterBothOrientationsBecomeIndependent() {
		VirtualKeyboardLayoutSnapshot shared = snapshot(3.0f);
		VirtualKeyboardLayoutSnapshot portrait = snapshot(8.0f);
		VirtualKeyboardLayoutSnapshot landscape = snapshot(12.0f);
		VirtualKeyboardLayoutState state = VirtualKeyboardLayoutState
				.migrated(shared)
				.withOverride(VirtualLayoutOrientation.PORTRAIT, portrait)
				.withOverride(VirtualLayoutOrientation.LANDSCAPE, landscape);

		assertNull(state.legacySharedFallback());
		assertSame(portrait, state.portraitOverride());
		assertSame(landscape, state.landscapeOverride());
	}

	private static VirtualKeyboardLayoutSnapshot snapshot(float offset) {
		return VirtualKeyboardLayoutSnapshot.legacy(
				VirtualKeyboard.TYPE_CUSTOM,
				new boolean[] { true, false },
				new int[] { -1, 0 },
				new int[] { 2, 4 },
				new float[] { offset, 0.0f },
				new float[] { 0.0f, 1.0f },
				new float[] { 1.0f, 1.0f })
				.withGroupedControls(
						true, false,
						0.20f, 0.82f, 0.16f,
						0.20f, 0.82f, 0.16f,
						false, true)
				.asCustomOverride();
	}
}
