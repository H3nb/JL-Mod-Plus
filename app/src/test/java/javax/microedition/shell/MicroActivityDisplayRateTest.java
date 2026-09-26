/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MicroActivityDisplayRateTest {
	@Test
	public void maximumSupportedFrameRateUsesHighestValidCapabilityAcrossSources() {
		assertEquals(144, MicroActivity.maximumSupportedFrameRate(
				new float[] {60f, 119.6f},
				new float[] {90f, 144f}));
	}

	@Test
	public void maximumSupportedFrameRateRoundsAndIgnoresMalformedCapabilities() {
		assertEquals(120, MicroActivity.maximumSupportedFrameRate(
				new float[] {Float.NaN, -1f, 119.6f, Float.POSITIVE_INFINITY},
				null));
		assertEquals(0, MicroActivity.maximumSupportedFrameRate(
				new float[0],
				new float[] {0f, -60f, Float.NaN}));
	}
}
