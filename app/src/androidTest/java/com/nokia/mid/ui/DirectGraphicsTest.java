/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nokia.mid.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

@RunWith(AndroidJUnit4.class)
public class DirectGraphicsTest {

	@Test
	public void alphaStateIsSharedWithGraphics() {
		Image image = Image.createImage(4, 4);
		Graphics graphics = image.getGraphics();
		DirectGraphics direct = DirectUtils.getDirectGraphics(graphics);

		direct.setARGBColor(0x44112233);
		assertEquals(0x112233, graphics.getColor());
		assertEquals(0x44, direct.getAlphaComponent());
		assertEquals(0x44, DirectUtils.getDirectGraphics(graphics).getAlphaComponent());

		graphics.setColor(0xAABBCC);
		assertEquals(0xAABBCC, graphics.getColor());
		assertEquals(0xFF, direct.getAlphaComponent());
	}

	@Test
	public void directPolygonColorDoesNotChangeGraphicsColor() {
		Image image = Image.createImage(16, 16);
		Graphics graphics = image.getGraphics();
		DirectGraphics direct = DirectUtils.getDirectGraphics(graphics);
		graphics.setColor(0x123456);

		direct.fillTriangle(2, 2, 12, 2, 7, 12, 0x80112233);

		assertEquals(0x123456, graphics.getColor());
		assertEquals(0xFF, direct.getAlphaComponent());
	}

	@Test
	public void fillPolygonUsesEvenOddRule() {
		Image image = Image.createImage(16, 16);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());
		int[] x = {2, 12, 12, 2, 2, 12, 12, 2};
		int[] y = {2, 2, 12, 12, 2, 2, 12, 12};

		direct.fillPolygon(x, 0, y, 0, x.length, 0xFF000000);

		assertEquals(0xFFFFFFFF, image.getBitmap().getPixel(7, 7));
	}

	@Test
	public void shortArgbHonorsTransparencyFlag() {
		Image image = Image.createImage(2, 1);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());
		short[] transparentRed = {(short) 0x0F00};

		direct.drawPixels(transparentRed, false, 0, 1, 0, 0, 1, 1,
				0, DirectGraphics.TYPE_USHORT_4444_ARGB);
		direct.drawPixels(transparentRed, true, 0, 1, 1, 0, 1, 1,
				0, DirectGraphics.TYPE_USHORT_4444_ARGB);

		assertEquals(0xFFFF0000, image.getBitmap().getPixel(0, 0));
		assertEquals(0xFFFFFFFF, image.getBitmap().getPixel(1, 0));
	}

	@Test
	public void intGetPixelsChecksFullScanlengthBeforeWriting() {
		Image image = Image.createImage(2, 2, 0xFF000000);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());
		int[] pixels = {7, 7, 7, 7, 7};
		int[] expected = pixels.clone();

		try {
			direct.getPixels(pixels, 0, 4, 0, 0, 2, 2, DirectGraphics.TYPE_INT_8888_ARGB);
			fail("Expected ArrayIndexOutOfBoundsException");
		} catch (ArrayIndexOutOfBoundsException expectedException) {
			assertArrayEquals(expected, pixels);
		}
	}

	@Test
	public void intPixelsSupportNegativeScanlength() {
		Image image = Image.createImage(2, 2);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());
		int red = 0x00FF0000;
		int green = 0x0000FF00;
		int blue = 0x000000FF;
		int yellow = 0x00FFFF00;
		int[] source = {red, green, blue, yellow};

		// Row zero starts at index 2 and row one walks backwards to index 0.
		direct.drawPixels(source, false, 2, -2, 0, 0, 2, 2,
				0, DirectGraphics.TYPE_INT_888_RGB);

		assertEquals(0xFF0000FF, image.getBitmap().getPixel(0, 0));
		assertEquals(0xFFFFFF00, image.getBitmap().getPixel(1, 0));
		assertEquals(0xFFFF0000, image.getBitmap().getPixel(0, 1));
		assertEquals(0xFF00FF00, image.getBitmap().getPixel(1, 1));

		int[] roundTrip = new int[4];
		direct.getPixels(roundTrip, 2, -2, 0, 0, 2, 2, DirectGraphics.TYPE_INT_888_RGB);
		assertArrayEquals(source, roundTrip);
	}

	@Test
	public void intGetPixelsSupportsOverlappingRows() {
		Image image = Image.createImage(2, 2);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());
		int[] source = {
				0x00112233, 0x00445566,
				0x00778899, 0x00AABBCC
		};
		direct.drawPixels(source, false, 0, 2, 0, 0, 2, 2,
				0, DirectGraphics.TYPE_INT_888_RGB);

		int[] pixels = {0, 0, 0};
		direct.getPixels(pixels, 0, 1, 0, 0, 2, 2, DirectGraphics.TYPE_INT_888_RGB);

		// Index 1 belongs to the second pixel of row zero and the first pixel of
		// row one; Nokia's documented row-order writes make row one win.
		assertArrayEquals(new int[]{0x00112233, 0x00778899, 0x00AABBCC}, pixels);
	}

	@Test
	public void intDrawPixelsSupportsZeroScanlength() {
		Image image = Image.createImage(1, 2);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());
		int[] source = {0x00CC3300};

		direct.drawPixels(source, false, 0, 0, 0, 0, 1, 2,
				0, DirectGraphics.TYPE_INT_888_RGB);

		assertEquals(0xFFCC3300, image.getBitmap().getPixel(0, 0));
		assertEquals(0xFFCC3300, image.getBitmap().getPixel(0, 1));
	}

	@Test
	public void zeroSizedDrawStillValidatesManipulationAndFormat() {
		Image image = Image.createImage(1, 1);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());

		try {
			direct.drawPixels(new int[0], false, 0, 0, 0, 0, 0, 0,
					45, DirectGraphics.TYPE_INT_888_RGB);
			fail("Expected invalid manipulation to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}

		try {
			direct.drawPixels(new int[0], false, 0, 0, 0, 0, 0, 0,
					0, DirectGraphics.TYPE_USHORT_565_RGB);
			fail("Expected wrong int pixel format to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	@Test
	public void verticalOneBitGetPixelsChecksLastColumnBeforeWriting() {
		Image image = Image.createImage(2, 9, 0xFF000000);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());
		byte[] pixels = {(byte) 0x55, (byte) 0x55, (byte) 0x55};
		byte[] expected = pixels.clone();

		try {
			direct.getPixels(pixels, null, 0, 2, 0, 0, 2, 9,
					DirectGraphics.TYPE_BYTE_1_GRAY_VERTICAL);
			fail("Expected ArrayIndexOutOfBoundsException");
		} catch (ArrayIndexOutOfBoundsException expectedException) {
			assertArrayEquals(expected, pixels);
		}
	}

	@Test
	public void oneBitMaskTreatsAnyNonZeroAlphaAsOpaque() {
		Image image = Image.createImage(1, 1, 0x01000000);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());
		byte[] pixels = new byte[1];
		byte[] mask = new byte[1];

		direct.getPixels(pixels, mask, 0, 1, 0, 0, 1, 1, DirectGraphics.TYPE_BYTE_1_GRAY);

		assertEquals(0x80, pixels[0] & 0x80);
		assertEquals(0x80, mask[0] & 0x80);
	}

	@Test
	public void drawImageRejectsConflictingAnchorBits() {
		Image image = Image.createImage(4, 4);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());

		try {
			direct.drawImage(Image.createImage(1, 1), 0, 0,
					Graphics.LEFT | Graphics.RIGHT, 0);
			fail("Expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}
}
