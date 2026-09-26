/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CanvasFrameLimitTest {
	@Test
	public void zeroUsesHostMaximumWithoutClampingExplicitOrInternalValues() {
		assertEquals(120, Canvas.resolveFrameRateLimit(0, 120));
		assertEquals(1, Canvas.resolveFrameRateLimit(0, 0));
		assertEquals(240, Canvas.resolveFrameRateLimit(240, 120));
		assertEquals(-1, Canvas.resolveFrameRateLimit(-1, 120));
	}
}
