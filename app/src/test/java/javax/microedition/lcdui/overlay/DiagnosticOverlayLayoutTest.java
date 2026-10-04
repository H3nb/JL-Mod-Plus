/* Licensed under the Apache License, Version 2.0. */
package javax.microedition.lcdui.overlay;

import static org.junit.Assert.assertEquals;

import androidx.core.graphics.Insets;
import org.junit.Test;

public class DiagnosticOverlayLayoutTest {
	@Test
	public void rightRowsKeepTheirRightEdgeAsTheirWidthsChange() {
		assertEquals(300f, DiagnosticOverlayLayout.rowLeft(8, 352, 52, true, 0, 1, 344), 0f);
		assertEquals(172f, DiagnosticOverlayLayout.rowLeft(8, 352, 180, true, 0, 1, 344), 0f);
		assertEquals(8f, DiagnosticOverlayLayout.rowLeft(8, 352, 52, false, 0, 1, 344), 0f);
		assertEquals(8f, DiagnosticOverlayLayout.rowLeft(8, 352, 180, false, 0, 1, 344), 0f);
	}

	@Test
	public void overflowColumnsKeepIndependentAnchoredEdges() {
		// Two 164px columns separated by 8px: right edges at 172 and 344.
		assertEquals(120f, DiagnosticOverlayLayout.rowLeft(8, 344, 52, true, 0, 2, 172), 0f);
		assertEquals(224f, DiagnosticOverlayLayout.rowLeft(8, 344, 120, true, 1, 2, 172), 0f);
		assertEquals(8f, DiagnosticOverlayLayout.rowLeft(8, 344, 52, false, 0, 2, 172), 0f);
		assertEquals(180f, DiagnosticOverlayLayout.rowLeft(8, 344, 52, false, 1, 2, 172), 0f);
	}

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
