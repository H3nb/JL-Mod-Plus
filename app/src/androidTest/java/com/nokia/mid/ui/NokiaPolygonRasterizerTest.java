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

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

@RunWith(AndroidJUnit4.class)
public class NokiaPolygonRasterizerTest {

	@Test
	public void adjacentTranslucentPolygonsDoNotLeaveUntintedColumn() {
		Image image = Image.createImage(8, 4, 0xFFFFFFFF);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());
		int color = 0x441111EE;

		// The two integer-coordinate regions meet as consecutive Nokia pixel
		// boundaries. Their filled boundary pixels must not expose the original
		// white destination as a bright vertical seam.
		direct.fillPolygon(
				new int[]{0, 3, 3, 0}, 0,
				new int[]{0, 0, 3, 3}, 0,
				4, color);
		direct.fillPolygon(
				new int[]{4, 7, 7, 4}, 0,
				new int[]{0, 0, 3, 3}, 0,
				4, color);

		int boundaryLeft = image.getBitmap().getPixel(3, 1);
		int boundaryRight = image.getBitmap().getPixel(4, 1);
		assertEquals(boundaryLeft, boundaryRight);
	}

	@Test
	public void repeatedCornerPolygonDoesNotBecomeFilledRectangle() {
		Image image = Image.createImage(8, 8, 0xFFFFFFFF);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());

		// This is a degenerate path with one repeated corner and one missing
		// bounding-box corner. It must not take the rectangle fast path.
		direct.fillPolygon(
				new int[]{1, 6, 6, 6}, 0,
				new int[]{1, 1, 6, 1}, 0,
				4, 0xFF000000);

		assertEquals(0xFFFFFFFF, image.getBitmap().getPixel(3, 3));
		assertEquals(0xFF000000, image.getBitmap().getPixel(3, 1));
	}

	@Test(timeout = 1000L)
	public void extremeOffscreenEdgesAreBoundedByClip() {
		Image image = Image.createImage(8, 8, 0xFFFFFFFF);
		DirectGraphics direct = DirectUtils.getDirectGraphics(image.getGraphics());

		// Old boundary iteration could walk billions of Bresenham steps before
		// reaching this tiny viewport. The visible work must remain clip-sized.
		direct.fillPolygon(
				new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, 0,
				new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE}, 0,
				3, 0xFF000000);
	}

	@Test
	public void polygonHonorsGraphicsTranslationAndClip() {
		Image image = Image.createImage(8, 8, 0xFFFFFFFF);
		Graphics graphics = image.getGraphics();
		graphics.translate(2, 1);
		graphics.setClip(0, 0, 2, 2);
		DirectGraphics direct = DirectUtils.getDirectGraphics(graphics);

		direct.fillPolygon(
				new int[]{0, 3, 3, 0}, 0,
				new int[]{0, 0, 3, 3}, 0,
				4, 0xFF000000);

		assertEquals(0xFF000000, image.getBitmap().getPixel(2, 1));
		assertEquals(0xFF000000, image.getBitmap().getPixel(3, 2));
		assertEquals(0xFFFFFFFF, image.getBitmap().getPixel(4, 1));
		assertEquals(0xFFFFFFFF, image.getBitmap().getPixel(2, 3));
	}
}
