/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.lcdui.keyboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VirtualLayoutOrientationTest {
	@Test
	public void hostLandscapeSelectsLandscapeRegardlessOfGuestShape() {
		assertEquals(
				VirtualLayoutOrientation.LANDSCAPE,
				VirtualLayoutOrientation.resolve(1200.0f, 600.0f, null));
	}

	@Test
	public void hostPortraitSelectsPortrait() {
		assertEquals(
				VirtualLayoutOrientation.PORTRAIT,
				VirtualLayoutOrientation.resolve(600.0f, 1200.0f, null));
	}

	@Test
	public void squareTransitionKeepsLastKnownOrientation() {
		assertEquals(
				VirtualLayoutOrientation.LANDSCAPE,
				VirtualLayoutOrientation.resolve(
						800.0f, 800.0f, VirtualLayoutOrientation.LANDSCAPE));
		assertEquals(
				VirtualLayoutOrientation.PORTRAIT,
				VirtualLayoutOrientation.resolve(
						800.0f, 800.0f, VirtualLayoutOrientation.PORTRAIT));
	}

	@Test
	public void squareWithoutHistoryUsesDeterministicPortraitDefault() {
		assertEquals(
				VirtualLayoutOrientation.PORTRAIT,
				VirtualLayoutOrientation.resolve(800.0f, 800.0f, null));
	}
}
