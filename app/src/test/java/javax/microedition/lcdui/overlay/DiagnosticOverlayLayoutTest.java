/* Licensed under the Apache License, Version 2.0. */
package javax.microedition.lcdui.overlay;

import static org.junit.Assert.assertEquals;

import androidx.core.graphics.Insets;
import org.junit.Test;

public class DiagnosticOverlayLayoutTest {
	@Test
	public void fittedLegacyContentDoesNotReceiveSystemInsetsTwice() {
		Insets bars = Insets.of(0, 24, 0, 48);
		assertEquals(Insets.NONE, DiagnosticOverlayLayout.overlap(
				bars, 0, 0, 360, 800, 0, 24, 360, 728));
		assertEquals(bars, DiagnosticOverlayLayout.overlap(
				bars, 0, 0, 360, 800, 0, 0, 360, 800));
	}

	@Test
	public void childViewportAndWindowOffsetsUseActualOverlap() {
		Insets safe = Insets.of(30, 24, 12, 48);
		assertEquals(Insets.of(20, 4, 0, 8), DiagnosticOverlayLayout.overlap(
				safe, 100, 200, 360, 800, 110, 220, 330, 740));
	}
}
