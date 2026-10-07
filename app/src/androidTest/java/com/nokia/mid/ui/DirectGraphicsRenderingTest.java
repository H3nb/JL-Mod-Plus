/* SPDX-License-Identifier: Apache-2.0 */
package com.nokia.mid.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

@RunWith(AndroidJUnit4.class)
public class DirectGraphicsRenderingTest {
	@Test
	public void acutePolygonRetainsRasterInteriorAndOutlineWithoutFragments() {
		int[][] shapes = {
				{64, 262, 57, 250, 10, 196},
				{-282, 221, -283, 417, 162, 236}
		};
		for (int[] points : shapes) {
			Image reference = Image.createImage(240, 320, BACKGROUND);
			Canvas canvas = new Canvas(reference.getBitmap());
			Path path = new Path();
			path.setFillType(Path.FillType.EVEN_ODD);
			path.moveTo(points[0], points[1]);
			path.lineTo(points[2], points[3]);
			path.lineTo(points[4], points[5]);
			path.close();
			Paint paint = new Paint();
			paint.setColor(0xFF1111EE);
			canvas.drawPath(path, paint);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(1);
			canvas.drawPath(path, paint);
			Image actual = Image.createImage(240, 320, BACKGROUND);
			DirectUtils.getDirectGraphics(actual.getGraphics()).fillPolygon(
					new int[]{points[0], points[2], points[4]}, 0,
					new int[]{points[1], points[3], points[5]}, 0, 3, WATER);
			int expectedColor = blendedPixel(BACKGROUND, WATER, 1);
			int[] mask = new int[240 * 320];
			int[] pixels = new int[240 * 320];
			reference.getBitmap().getPixels(mask, 0, 240, 0, 0, 240, 320);
			actual.getBitmap().getPixels(pixels, 0, 240, 0, 0, 240, 320);
			for (int i = 0; i < pixels.length; i++) {
				assertEquals("polygon pixel=" + i % 240 + "," + i / 240,
						mask[i] == BACKGROUND ? BACKGROUND : expectedColor, pixels[i]);
			}
		}
	}

	private static final int BACKGROUND = 0xFF6DCEFC;
	private static final int WATER = 0x441111EE;

	@Test
	public void fillOnlyCoverageReproducesTheGapBeforePresentation() {
		Image image = Image.createImage(240, 12, BACKGROUND);
		Graphics g = image.getGraphics();
		DirectUtils.getDirectGraphics(g).setARGBColor(WATER);
		// This is the unchanged fill-only backend used by the previous Nokia adapter.
		g.fillPolygon(new int[]{42, 89, 89, 42}, 0, new int[]{0, 0, 11, 11}, 0, 4);
		g.fillPolygon(new int[]{90, 178, 178, 90}, 0, new int[]{0, 0, 11, 11}, 0, 4);
		assertEquals(blendedPixel(BACKGROUND, WATER, 1), pixel(image, 88, 6));
		assertEquals(BACKGROUND, pixel(image, 89, 6));
		assertEquals(blendedPixel(BACKGROUND, WATER, 1), pixel(image, 90, 6));
		assertEquals(BACKGROUND, pixel(image, 178, 6));
	}

	@Test
	public void closedPolygonIncludesBoundaryWithOneAlphaApplication() {
		for (int alpha : new int[]{0, 68, 128, 255}) {
			int color = alpha << 24 | 0x1111EE;
			Image image = Image.createImage(12, 12, BACKGROUND);
			DirectGraphics dg = DirectUtils.getDirectGraphics(image.getGraphics());
			fillRectangle(dg, 2, 2, 7, 7, color);
			int expected = blendedPixel(BACKGROUND, color, 1);
			for (int y = 0; y < 12; y++) {
				for (int x = 0; x < 12; x++) {
					assertEquals("alpha=" + alpha + " pixel=" + x + "," + y,
							x >= 2 && x <= 7 && y >= 2 && y <= 7 ? expected : BACKGROUND,
							pixel(image, x, y));
				}
			}
		}
	}

	@Test
	public void consecutiveIntegerRangesDoNotLeaveUnblendedColumns() {
		// Generic closed ranges reproduce the two one-pixel gaps in Bounce Tales.
		// The fixture contains coordinates only, not game assets or game detection.
		for (int cameraOffset : new int[]{0, -56}) {
			Image image = Image.createImage(240, 12, BACKGROUND);
			DirectGraphics dg = DirectUtils.getDirectGraphics(image.getGraphics());
			fillRectangle(dg, 42 + cameraOffset, 0, 89 + cameraOffset, 11, WATER);
			fillRectangle(dg, 90 + cameraOffset, 0, 178 + cameraOffset, 11, WATER);
			fillRectangle(dg, 179 + cameraOffset, 0, 239, 11, WATER);
			for (int x = Math.max(0, 42 + cameraOffset); x < 240; x++) {
				assertEquals("offset=" + cameraOffset + " column=" + x,
						blendedPixel(BACKGROUND, WATER, 1), pixel(image, x, 6));
			}
		}
	}

	@Test
	public void repeatedContourUsesEvenOddInteriorAndBlendsBoundaryOnce() {
		for (int color : new int[]{WATER, 0xFF1111EE}) {
			Image image = Image.createImage(18, 18, BACKGROUND);
			DirectGraphics dg = DirectUtils.getDirectGraphics(image.getGraphics());
			dg.fillPolygon(new int[]{2, 14, 14, 2, 2, 14, 14, 2}, 0,
					new int[]{2, 2, 14, 14, 2, 2, 14, 14}, 0, 8, color);
			assertEquals(BACKGROUND, pixel(image, 7, 7));
			assertEquals(blendedPixel(BACKGROUND, color, 1), pixel(image, 2, 7));
			assertEquals(blendedPixel(BACKGROUND, color, 1), pixel(image, 14, 7));
		}
	}

	@Test
	public void concavePolygonKeepsItsNotchEmpty() {
		Image image = Image.createImage(18, 18, BACKGROUND);
		DirectGraphics dg = DirectUtils.getDirectGraphics(image.getGraphics());
		dg.fillPolygon(new int[]{2, 14, 14, 5, 5, 2}, 0,
				new int[]{2, 2, 5, 5, 14, 14}, 0, 6, WATER);
		assertEquals(blendedPixel(BACKGROUND, WATER, 1), pixel(image, 10, 3));
		assertEquals(blendedPixel(BACKGROUND, WATER, 1), pixel(image, 3, 10));
		assertEquals(BACKGROUND, pixel(image, 10, 10));
	}

	@Test
	public void triangleAndPolygonHaveSameCoverageForEitherPointOrder() {
		Image triangle = Image.createImage(18, 18, BACKGROUND);
		DirectUtils.getDirectGraphics(triangle.getGraphics())
				.fillTriangle(2, 2, 14, 2, 2, 14, WATER);
		Image polygon = Image.createImage(18, 18, BACKGROUND);
		DirectUtils.getDirectGraphics(polygon.getGraphics()).fillPolygon(
				new int[]{2, 2, 14, 2}, 1, new int[]{2, 14, 2, 2}, 1, 3, WATER);
		for (int y = 0; y < 18; y++) {
			for (int x = 0; x < 18; x++) {
				assertEquals("pixel=" + x + "," + y, pixel(triangle, x, y), pixel(polygon, x, y));
			}
		}
		assertEquals(blendedPixel(BACKGROUND, WATER, 1), pixel(triangle, 3, 3));
		assertEquals(BACKGROUND, pixel(triangle, 14, 14));
	}

	@Test
	public void clippingAndTranslationAlsoConstrainTheBoundary() {
		Image image = Image.createImage(20, 20, BACKGROUND);
		Graphics g = image.getGraphics();
		g.translate(3, 4);
		g.setClip(2, 2, 4, 5);
		DirectGraphics dg = DirectUtils.getDirectGraphics(g);
		fillRectangle(dg, -2, -2, 10, 10, WATER);
		for (int y = 0; y < 20; y++) {
			for (int x = 0; x < 20; x++) {
				assertEquals(x >= 5 && x < 9 && y >= 6 && y < 11
						? blendedPixel(BACKGROUND, WATER, 1) : BACKGROUND, pixel(image, x, y));
			}
		}
		assertEquals(3, g.getTranslateX());
		assertEquals(4, g.getTranslateY());
		assertEquals(2, g.getClipX());
		assertEquals(2, g.getClipY());
	}

	@Test
	public void independentCallsStillCompositeTheirOverlapSeparately() {
		Image image = Image.createImage(12, 12, BACKGROUND);
		DirectGraphics dg = DirectUtils.getDirectGraphics(image.getGraphics());
		fillRectangle(dg, 1, 1, 7, 7, WATER);
		fillRectangle(dg, 5, 5, 10, 10, WATER);
		assertEquals(blendedPixel(BACKGROUND, WATER, 1), pixel(image, 3, 3));
		assertEquals(blendedPixel(BACKGROUND, WATER, 2), pixel(image, 6, 6));
	}

	@Test
	public void transparentDestinationKeepsSourceAlphaAtInteriorAndBoundary() {
		Image image = Image.createImage(12, 12, 0);
		DirectGraphics dg = DirectUtils.getDirectGraphics(image.getGraphics());
		fillRectangle(dg, 2, 2, 7, 7, WATER);
		int expected = blendedPixel(0, WATER, 1);
		assertEquals(expected, pixel(image, 4, 4));
		assertEquals(expected, pixel(image, 7, 4));
		assertEquals(expected, pixel(image, 7, 7));
		assertEquals(0, pixel(image, 8, 4));
	}

	@Test
	public void explicitColorMethodsDoNotChangeSharedColorOrStroke() {
		Image image = Image.createImage(20, 20, BACKGROUND);
		Graphics g = image.getGraphics();
		DirectGraphics dg = DirectUtils.getDirectGraphics(g);
		dg.setARGBColor(0x80224466);
		g.setStrokeStyle(Graphics.DOTTED);
		int[] x = {2, 14, 2};
		int[] y = {2, 2, 14};
		dg.drawPolygon(x, 0, y, 0, 3, WATER);
		assertSharedState(g, dg);
		dg.drawTriangle(2, 2, 14, 2, 2, 14, WATER);
		assertSharedState(g, dg);
		dg.fillPolygon(x, 0, y, 0, 3, WATER);
		assertSharedState(g, dg);
		dg.fillTriangle(2, 2, 14, 2, 2, 14, WATER);
		assertSharedState(g, dg);
	}

	@Test
	public void alphaQueryTracksTheSharedContextAcrossWrappersAndMidpSetters() {
		Graphics g = Image.createImage(4, 4).getGraphics();
		DirectGraphics first = DirectUtils.getDirectGraphics(g);
		DirectGraphics second = DirectUtils.getDirectGraphics(g);
		assertEquals(255, first.getAlphaComponent());
		first.setARGBColor(WATER);
		assertEquals(68, second.getAlphaComponent());
		g.setColor(0x123456);
		assertEquals(255, first.getAlphaComponent());
		second.setARGBColor(WATER);
		g.setGrayScale(123);
		assertEquals(255, second.getAlphaComponent());
		first.setARGBColor(WATER);
		g.setColor(12, 34, 56);
		assertEquals(255, second.getAlphaComponent());
	}

	@Test
	public void invalidCoordinateArraysDoNotChangeTheColorOrPixels() {
		Image image = Image.createImage(12, 12, BACKGROUND);
		Graphics g = image.getGraphics();
		DirectGraphics dg = DirectUtils.getDirectGraphics(g);
		dg.setARGBColor(0x80224466);
		try {
			dg.fillPolygon(new int[]{2, 7}, 0, new int[]{2, 2}, 0, 3, WATER);
			fail("Expected invalid coordinate range to fail");
		} catch (ArrayIndexOutOfBoundsException expected) {
			// No drawing or shared color changes may precede validation.
		}
		try {
			dg.drawPolygon(null, 0, new int[]{2, 2, 7}, 0, 3, WATER);
			fail("Expected null coordinates to fail");
		} catch (NullPointerException expected) {
			// No drawing or shared color changes may precede validation.
		}
		assertEquals(0x80224466, g.getColor());
		assertEquals(128, dg.getAlphaComponent());
		assertEquals(BACKGROUND, pixel(image, 4, 4));
	}

	@Test
	public void nokiaFillDoesNotChangeSubsequentMidpCoverageOrColor() {
		Image image = Image.createImage(20, 20, BACKGROUND);
		Graphics g = image.getGraphics();
		g.setColor(0xCC3300);
		DirectGraphics dg = DirectUtils.getDirectGraphics(g);
		fillRectangle(dg, 1, 1, 4, 4, WATER);
		g.fillTriangle(8, 8, 16, 8, 8, 16);
		assertEquals(0xFFCC3300, pixel(image, 9, 9));
		g.fillRect(17, 17, 1, 1);
		assertEquals(0xFFCC3300, pixel(image, 17, 17));
		assertEquals(BACKGROUND, pixel(image, 18, 17));
	}

	private static void assertSharedState(Graphics g, DirectGraphics dg) {
		assertEquals(0x80224466, g.getColor());
		assertEquals(128, dg.getAlphaComponent());
		assertEquals(Graphics.DOTTED, g.getStrokeStyle());
	}

	private static void fillRectangle(DirectGraphics dg, int left, int top,
			int right, int bottom, int color) {
		dg.fillPolygon(new int[]{left, right, right, left}, 0,
				new int[]{top, top, bottom, bottom}, 0, 4, color);
	}

	private static int blendedPixel(int background, int source, int applications) {
		Image reference = Image.createImage(1, 1, background);
		Graphics g = reference.getGraphics();
		DirectUtils.getDirectGraphics(g).setARGBColor(source);
		for (int i = 0; i < applications; i++) g.fillRect(0, 0, 1, 1);
		return pixel(reference, 0, 0);
	}

	private static int pixel(Image image, int x, int y) {
		return image.getBitmap().getPixel(x, y);
	}
}
