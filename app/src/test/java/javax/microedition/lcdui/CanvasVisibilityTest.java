/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CanvasVisibilityTest {
	@Test
	public void staleForegroundCompletionCannotGrantNewerHostEdge() {
		Display.initDisplay();
		long firstForeground = Display.requestForeground();
		assertEquals(firstForeground, Display.currentForegroundRequestGeneration());
		Display.revokeForeground();
		assertEquals(0L, Display.currentForegroundRequestGeneration());
		long returnedForeground = Display.requestForeground();

		assertFalse(Display.isForegroundRequestCurrent(firstForeground));
		assertFalse(Display.grantForeground(firstForeground));
		assertTrue(Display.isForegroundRequestCurrent(returnedForeground));
		assertTrue(Display.grantForeground(returnedForeground));
	}

	@Test
	public void canvasVisibilityDependsOnlyOnGuestAndRealHostPresentationFacts() {
		assertFalse(Canvas.isPresentationVisible(false, true, true, true));
		assertFalse(Canvas.isPresentationVisible(true, false, true, true));
		assertFalse(Canvas.isPresentationVisible(true, true, false, true));
		assertFalse(Canvas.isPresentationVisible(true, true, true, false));
		// Host-owned runtime menu/tooling is intentionally not a MIDP presentation fact.
		assertTrue(Canvas.isPresentationVisible(true, true, true, true));
	}

	@Test
	public void zeroFpsUsesDisplayMaximumWithoutClampingExplicitOrInternalValues() {
		assertEquals(120, Canvas.resolveFrameRateLimit(0, 120));
		assertEquals(240, Canvas.resolveFrameRateLimit(240, 120));
		assertEquals(-1, Canvas.resolveFrameRateLimit(-1, 120));
	}

	@Test
	public void displayMaximumUsesHighestValidCapabilityAndFailsClosed() {
		assertEquals(120, Canvas.resolveMaximumDisplayFps(
				new float[]{30.0f, 60.0f, Float.NaN},
				new float[]{90.0f, 119.88f, Float.POSITIVE_INFINITY},
				0));
		assertEquals(144, Canvas.resolveMaximumDisplayFps(
				new float[]{143.6f}, new float[0], 60));
		assertEquals(90, Canvas.resolveMaximumDisplayFps(
				new float[]{0.0f, -1.0f, Float.NaN}, null, 90));
		assertEquals(1, Canvas.resolveMaximumDisplayFps(null, new float[0], 0));
	}
}
