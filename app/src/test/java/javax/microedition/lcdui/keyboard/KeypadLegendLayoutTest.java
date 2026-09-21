/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.lcdui.keyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import javax.microedition.lcdui.Canvas;

public class KeypadLegendLayoutTest {
	private static final float PRIMARY_WIDTH = 10.0f;
	private static final float TEXT_HEIGHT = 20.0f;
	private static final float ABC_WIDTH = 24.0f;
	private static final float WXYZ_WIDTH = 40.0f;

	@Test
	public void wideButtonPrefersHorizontalWhenCandidatesAreComparable() {
		KeypadLegendLayout.Plan plan = plan(120.0f, 44.0f, ABC_WIDTH);
		assertEquals(KeypadLegendLayout.Mode.HORIZONTAL, plan.mode());
	}

	@Test
	public void narrowTallButtonUsesStackedWhenLegendIsMoreReadable() {
		KeypadLegendLayout.Plan plan = plan(32.0f, 90.0f, ABC_WIDTH);
		assertEquals(KeypadLegendLayout.Mode.STACKED, plan.mode());
	}

	@Test
	public void extremelySmallButtonFallsBackToPrimaryOnly() {
		KeypadLegendLayout.Plan plan = plan(18.0f, 20.0f, ABC_WIDTH);
		assertEquals(KeypadLegendLayout.Mode.PRIMARY_ONLY, plan.mode());
	}

	@Test
	public void primaryNeverGrowsBeyondNormalScale() {
		assertEquals(
				1.0f,
				KeypadLegendLayout.fitPrimaryScale(500.0f, 500.0f, PRIMARY_WIDTH, TEXT_HEIGHT),
				0.0001f);
	}

	@Test
	public void primaryShrinksToFitSmallBounds() {
		float scale = KeypadLegendLayout.fitPrimaryScale(
				8.0f, 10.0f, PRIMARY_WIDTH, TEXT_HEIGHT);
		assertTrue(scale > 0.0f);
		assertTrue(scale < 1.0f);
	}

	@Test
	public void longestLegendLimitsShortAndLongMappingsToSameScale() {
		KeypadLegendLayout.Plan abc = plan(120.0f, 44.0f, ABC_WIDTH);
		KeypadLegendLayout.Plan wxyz = plan(120.0f, 44.0f, WXYZ_WIDTH);
		assertEquals(abc.mode(), wxyz.mode());
		assertEquals(abc.secondaryScale(), wxyz.secondaryScale(), 0.0001f);
	}

	@Test
	public void actualResizedBoundsDrivePresentationWithoutStoredLegendState() {
		assertEquals(KeypadLegendLayout.Mode.HORIZONTAL, plan(120.0f, 44.0f, ABC_WIDTH).mode());
		assertEquals(KeypadLegendLayout.Mode.STACKED, plan(32.0f, 90.0f, ABC_WIDTH).mode());
		assertEquals(KeypadLegendLayout.Mode.PRIMARY_ONLY, plan(18.0f, 20.0f, ABC_WIDTH).mode());
	}

	@Test
	public void isolatedActionKeysDoNotCreateNumericKeypadContext() {
		assertTrue(!KeypadLegendLayout.hasNumericContext(0));
		assertTrue(!KeypadLegendLayout.hasNumericContext(2));
		assertTrue(KeypadLegendLayout.hasNumericContext(3));
	}

	@Test
	public void legendMappingMatchesRequestedPhoneHints() {
		assertEquals(".?!", VirtualKeyboard.legendForKeyCode(Canvas.KEY_NUM1));
		assertEquals("ABC", VirtualKeyboard.legendForKeyCode(Canvas.KEY_NUM2));
		assertEquals("DEF", VirtualKeyboard.legendForKeyCode(Canvas.KEY_NUM3));
		assertEquals("GHI", VirtualKeyboard.legendForKeyCode(Canvas.KEY_NUM4));
		assertEquals("JKL", VirtualKeyboard.legendForKeyCode(Canvas.KEY_NUM5));
		assertEquals("MNO", VirtualKeyboard.legendForKeyCode(Canvas.KEY_NUM6));
		assertEquals("PQRS", VirtualKeyboard.legendForKeyCode(Canvas.KEY_NUM7));
		assertEquals("TUV", VirtualKeyboard.legendForKeyCode(Canvas.KEY_NUM8));
		assertEquals("WXYZ", VirtualKeyboard.legendForKeyCode(Canvas.KEY_NUM9));
		assertEquals("+", VirtualKeyboard.legendForKeyCode(Canvas.KEY_NUM0));
		assertEquals("SYM", VirtualKeyboard.legendForKeyCode(Canvas.KEY_STAR));
		assertEquals("Aa", VirtualKeyboard.legendForKeyCode(Canvas.KEY_POUND));
	}

	private static KeypadLegendLayout.Plan plan(float width, float height, float secondaryWidth) {
		return KeypadLegendLayout.resolve(
				width,
				height,
				PRIMARY_WIDTH,
				TEXT_HEIGHT,
				secondaryWidth,
				TEXT_HEIGHT,
				WXYZ_WIDTH);
	}
}
