/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GraphicsArgumentValidationTest {
	@Test
	public void illegalRgbAndGrayscaleLeaveSharedArgbStateUntouched() {
		Graphics graphics = Image.createImage(2, 2).getGraphics();
		graphics.setColorAlpha(0x7F123456);
		for (int[] rgb : new int[][]{
				{-1, 0, 0}, {256, 0, 0}, {0, -1, 0},
				{0, 256, 0}, {0, 0, -1}, {0, 0, 256}}) {
			assertThrows(IllegalArgumentException.class,
					() -> graphics.setColor(rgb[0], rgb[1], rgb[2]));
			assertEquals(0x7F123456, graphics.getColorAlpha());
		}
		assertThrows(IllegalArgumentException.class, () -> graphics.setGrayScale(-1));
		assertThrows(IllegalArgumentException.class, () -> graphics.setGrayScale(256));
		assertEquals(0x7F123456, graphics.getColorAlpha());
	}

	@Test
	public void validColorsRemainOpaqueAndPackedRgbIgnoresHighByte() {
		Graphics graphics = Image.createImage(2, 2).getGraphics();
		graphics.setColor(0, 255, 128);
		assertEquals(0xFF00FF80, graphics.getColorAlpha());
		graphics.setGrayScale(255);
		assertEquals(0xFFFFFFFF, graphics.getColorAlpha());
		graphics.setGrayScale(0);
		assertEquals(0xFF000000, graphics.getColorAlpha());
		graphics.setColor(0xA5123456);
		assertEquals(0xFF123456, graphics.getColorAlpha());
	}

	@Test
	public void invalidStrokeKeepsCurrentStyle() {
		Graphics graphics = Image.createImage(2, 2).getGraphics();
		for (int style : new int[]{Graphics.DOTTED, Graphics.SOLID}) {
			graphics.setStrokeStyle(style);
			for (int invalid : new int[]{-1, 2, Integer.MAX_VALUE}) {
				assertThrows(IllegalArgumentException.class,
						() -> graphics.setStrokeStyle(invalid));
				assertEquals(style, graphics.getStrokeStyle());
			}
		}
	}
}
