/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.lcdui.keyboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VirtualKeyboardBoundsTest {
	@Test
	public void draggingClampsAtBothEdges() {
		assertEquals(10.0f, VirtualKeyboard.constrainPosition(-60.0f, 25.0f, 10.0f, 110.0f), 0.0f);
		assertEquals(85.0f, VirtualKeyboard.constrainPosition(150.0f, 25.0f, 10.0f, 110.0f), 0.0f);
		assertEquals(84.0f, VirtualKeyboard.constrainPosition(84.0f, 25.0f, 10.0f, 110.0f), 0.0f);
	}

	@Test
	public void oversizedStoredButtonCanBePositionedInsideNarrowViewport() {
		assertEquals(10.0f, VirtualKeyboard.constrainPosition(90.0f, 180.0f, 10.0f, 110.0f), 0.0f);
		assertEquals(10.0f, VirtualKeyboard.constrainPosition(-50.0f, 180.0f, 10.0f, 110.0f), 0.0f);
	}

	@Test
	public void offsetViewportOriginIsRespected() {
		assertEquals(230.0f, VirtualKeyboard.constrainPosition(290.0f, 40.0f, 200.0f, 270.0f), 0.0f);
	}
}
