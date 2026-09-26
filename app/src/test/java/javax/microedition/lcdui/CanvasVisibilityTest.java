/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CanvasVisibilityTest {
	@Test
	public void canvasIsGuestVisibleOnlyWhenAllPresentationConditionsHold() {
		assertFalse(Canvas.isPresentationVisible(false, true, true, true, false));
		assertFalse(Canvas.isPresentationVisible(true, false, true, true, false));
		assertFalse(Canvas.isPresentationVisible(true, true, false, true, false));
		assertFalse(Canvas.isPresentationVisible(true, true, true, false, false));
		assertFalse(Canvas.isPresentationVisible(true, true, true, true, true));
		assertTrue(Canvas.isPresentationVisible(true, true, true, true, false));
	}
}
