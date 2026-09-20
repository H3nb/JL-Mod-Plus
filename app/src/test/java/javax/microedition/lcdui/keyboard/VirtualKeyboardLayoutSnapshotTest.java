/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.lcdui.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class VirtualKeyboardLayoutSnapshotTest {
	private static VirtualKeyboardLayoutSnapshot legacy(float offset) {
		return legacy(3, offset);
	}

	private static VirtualKeyboardLayoutSnapshot legacy(int variant, float offset) {
		return VirtualKeyboardLayoutSnapshot.legacy(
				variant,
				new boolean[] { true, false },
				new int[] { -1, 0 },
				new int[] { 2, 4 },
				new float[] { offset, 0.0f },
				new float[] { 0.0f, 1.0f },
				new float[] { 1.0f, 1.0f });
	}

	@Test
	public void snapshotOwnsImmutableCopies() {
		boolean[] visible = { true, false };
		int[] origins = { -1, 0 };
		int[] modes = { 2, 4 };
		float[] x = { 3.0f, 0.0f };
		float[] y = { 0.0f, 1.0f };
		float[] scales = { 1.0f, 1.0f };
		VirtualKeyboardLayoutSnapshot snapshot = VirtualKeyboardLayoutSnapshot.legacy(
				3, visible, origins, modes, x, y, scales);

		visible[0] = false;
		origins[0] = 99;
		x[0] = 50.0f;
		scales[0] = 4.0f;

		assertEquals(legacy(3.0f), snapshot);
	}

	@Test
	public void semanticGeometryChangeIsDirty() {
		assertNotEquals(legacy(3.0f), legacy(5.0f));
	}

	@Test
	public void untouchedStandardTemplateIgnoresViewportDerivedReflow() {
		VirtualKeyboardLayoutSnapshot portrait = legacy(
				VirtualControlsKeyboard.TYPE_DPAD_STANDARD, 3.0f).withGroupedControls(
				true, false,
				0.20f, 0.82f, 0.16f,
				0.20f, 0.82f, 0.16f,
				true, false);
		VirtualKeyboardLayoutSnapshot landscape = legacy(
				VirtualControlsKeyboard.TYPE_DPAD_STANDARD, 120.0f).withGroupedControls(
				true, false,
				0.14f, 0.55f, 0.20f,
				0.14f, 0.55f, 0.20f,
				true, false);

		assertEquals(portrait, landscape);
	}

	@Test
	public void nonStandardLayoutIgnoresTransientStandardTemplateFlag() {
		VirtualKeyboardLayoutSnapshot first = legacy(3.0f).withGroupedControls(
				true, false,
				0.20f, 0.82f, 0.16f,
				0.20f, 0.82f, 0.16f,
				false, false);
		VirtualKeyboardLayoutSnapshot second = legacy(3.0f).withGroupedControls(
				true, false,
				0.20f, 0.82f, 0.16f,
				0.20f, 0.82f, 0.16f,
				false, true);

		assertEquals(first, second);
	}

	@Test
	public void editedStandardTemplateKeepsRealGeometryInDirtyComparison() {
		VirtualKeyboardLayoutSnapshot baseline = legacy(
				VirtualControlsKeyboard.TYPE_DPAD_STANDARD, 3.0f).withGroupedControls(
				true, false,
				0.20f, 0.82f, 0.16f,
				0.20f, 0.82f, 0.16f,
				true, true);
		VirtualKeyboardLayoutSnapshot changed = legacy(
				VirtualControlsKeyboard.TYPE_DPAD_STANDARD, 3.0f).withGroupedControls(
				true, false,
				0.25f, 0.82f, 0.16f,
				0.20f, 0.82f, 0.16f,
				true, true);

		assertNotEquals(baseline, changed);
	}
}
