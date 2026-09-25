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
		assertFalse(Canvas.isPresentationVisible(false, false, false));
		assertFalse(Canvas.isPresentationVisible(true, false, false));
		assertFalse(Canvas.isPresentationVisible(false, true, false));
		assertFalse(Canvas.isPresentationVisible(false, false, true));
		assertFalse(Canvas.isPresentationVisible(true, true, false));
		assertFalse(Canvas.isPresentationVisible(true, false, true));
		assertFalse(Canvas.isPresentationVisible(false, true, true));
		assertTrue(Canvas.isPresentationVisible(true, true, true));
	}
}
