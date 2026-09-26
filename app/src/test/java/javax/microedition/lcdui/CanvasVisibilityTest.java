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
		assertTrue(Canvas.isPresentationVisible(true, true, true, true));
	}
}
