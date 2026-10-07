/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.nokia.mid.ui.DirectGraphics;
import com.nokia.mid.ui.DirectUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GraphicsTriangleRenderingTest {
	private static final int BACKGROUND = 0xFF6DCEFC;
	private static final int TERRAIN = 0xFF0C431D;
	private static final int WATER = 0x441111EE;

	@Test
	public void shallowBottomEdgeClosesTheRowBeforeAdjacentWater() {
		Image old = Image.createImage(240, 200, BACKGROUND);
		Graphics oldGraphics = old.getGraphics();
		oldGraphics.setColor(TERRAIN);
		oldGraphics.fillPolygon(new int[]{74, 32, 131}, 0,
				new int[]{110, 174, 178}, 0, 3);
		Image image = Image.createImage(240, 200, BACKGROUND);
		Graphics g = image.getGraphics();
		DirectGraphics dg = DirectUtils.getDirectGraphics(g);
		dg.fillPolygon(new int[]{104, 239, 239, 104}, 0,
				new int[]{178, 178, 199, 199}, 0, 4, WATER);
		g.setColor(TERRAIN);
		g.fillTriangle(74, 110, 32, 174, 131, 178);
		// Integer coordinates reconstructed from the reported one-row seam.
		// The fixture contains no game assets or identity-specific behavior.
		for (int x = 104; x <= 118; x++) {
			assertEquals("old gap column=" + x, BACKGROUND, pixel(old, x, 177));
			assertEquals("closed edge column=" + x, TERRAIN, pixel(image, x, 177));
		}
		assertEquals(blendedPixel(BACKGROUND, WATER, 1), pixel(image, 110, 180));
	}

	@Test
	public void vertexOrderThinAndDegenerateTrianglesKeepInclusiveBounds() {
		int[][] triangles = {
				{2, 2, 8, 2, 2, 8},
				{2, 8, 8, 8, 8, 2},
				{2, 2, 10, 3, 10, 2},
				{2, 3, 10, 3, 5, 3},
				{3, 2, 3, 10, 3, 6},
				{5, 5, 5, 5, 5, 5}
		};
		int[][] permutations = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2},
				{1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
		for (int[] points : triangles) {
			Image reference = triangle(points, permutations[0], TERRAIN);
			int minX = Math.min(points[0], Math.min(points[2], points[4]));
			int maxX = Math.max(points[0], Math.max(points[2], points[4]));
			int minY = Math.min(points[1], Math.min(points[3], points[5]));
			int maxY = Math.max(points[1], Math.max(points[3], points[5]));
			for (int[] order : permutations) {
				Image actual = triangle(points, order, TERRAIN);
				for (int v = 0; v < 3; v++) {
					assertEquals("vertex=" + v, TERRAIN, pixel(actual, points[v * 2], points[v * 2 + 1]));
				}
				for (int y = 0; y < 14; y++) {
					for (int x = 0; x < 14; x++) {
						assertEquals("order pixel=" + x + "," + y, pixel(reference, x, y), pixel(actual, x, y));
						if (x < minX || x > maxX || y < minY || y > maxY) {
							assertEquals("outside bounds=" + x + "," + y, BACKGROUND, pixel(actual, x, y));
						}
					}
				}
				if (minY == maxY) {
					for (int x = minX; x <= maxX; x++) assertEquals(TERRAIN, pixel(actual, x, minY));
				}
				if (minX == maxX) {
					for (int y = minY; y <= maxY; y++) assertEquals(TERRAIN, pixel(actual, minX, y));
				}
			}
		}
		Image rightAngle = triangle(triangles[0], permutations[0], TERRAIN);
		for (int p = 2; p <= 8; p++) {
			assertEquals(TERRAIN, pixel(rightAngle, p, 2));
			assertEquals(TERRAIN, pixel(rightAngle, 2, p));
			assertEquals(TERRAIN, pixel(rightAngle, p, 10 - p));
		}
	}

	@Test
	public void oneCallBlendsInteriorEdgesAndEndpointsOnce() {
		for (int background : new int[]{BACKGROUND, 0}) {
			for (int alpha : new int[]{0, 68, 128, 255}) {
				int color = alpha << 24 | 0x1111EE;
				Image image = Image.createImage(14, 14, background);
				Graphics g = image.getGraphics();
				DirectUtils.getDirectGraphics(g).setARGBColor(color);
				g.setStrokeStyle(Graphics.DOTTED);
				g.fillTriangle(2, 2, 10, 2, 2, 10);
				int expected = blendedPixel(background, color, 1);
				assertEquals(expected, pixel(image, 3, 3));
				assertEquals(expected, pixel(image, 6, 6));
				assertEquals(expected, pixel(image, 10, 2));
				assertEquals(expected, pixel(image, 2, 10));
				assertEquals(background, pixel(image, 11, 2));
				assertEquals(color, g.getColor());
				assertEquals(Graphics.DOTTED, g.getStrokeStyle());
				g.fillTriangle(2, 2, 10, 2, 2, 10);
				assertEquals(blendedPixel(background, color, 2), pixel(image, 6, 6));
			}
		}
	}

	@Test
	public void clippingAndTranslationConstrainAllBoundaryCoverage() {
		Image reference = Image.createImage(20, 20, BACKGROUND);
		Graphics referenceGraphics = reference.getGraphics();
		referenceGraphics.setColor(TERRAIN);
		referenceGraphics.fillTriangle(3, 4, 13, 4, 3, 14);
		Image image = Image.createImage(20, 20, BACKGROUND);
		Graphics g = image.getGraphics();
		g.translate(3, 4);
		g.setClip(2, 2, 4, 5);
		g.setColor(TERRAIN);
		g.fillTriangle(0, 0, 10, 0, 0, 10);
		for (int y = 0; y < 20; y++) {
			for (int x = 0; x < 20; x++) {
				assertEquals(x >= 5 && x < 9 && y >= 6 && y < 11
						? pixel(reference, x, y) : BACKGROUND, pixel(image, x, y));
			}
		}
		assertEquals(3, g.getTranslateX());
		assertEquals(4, g.getTranslateY());
		assertEquals(2, g.getClipX());
		assertEquals(2, g.getClipY());
	}

	@Test
	public void triangleDoesNotChangeNokiaCoverageOrRectangleConvention() {
		Image image = Image.createImage(24, 24, BACKGROUND);
		Graphics g = image.getGraphics();
		g.setColor(TERRAIN);
		g.fillTriangle(1, 1, 5, 1, 1, 5);
		DirectGraphics dg = DirectUtils.getDirectGraphics(g);
		dg.fillPolygon(new int[]{10, 20, 20, 10, 10, 20, 20, 10}, 0,
				new int[]{10, 10, 20, 20, 10, 10, 20, 20}, 0, 8, WATER);
		assertEquals(BACKGROUND, pixel(image, 15, 15));
		assertEquals(blendedPixel(BACKGROUND, WATER, 1), pixel(image, 20, 15));
		g.fillRect(22, 22, 1, 1);
		assertEquals(TERRAIN, pixel(image, 22, 22));
		assertEquals(BACKGROUND, pixel(image, 23, 22));
	}

	private static Image triangle(int[] points, int[] order, int color) {
		Image image = Image.createImage(14, 14, BACKGROUND);
		Graphics g = image.getGraphics();
		g.setColor(color);
		g.fillTriangle(points[order[0] * 2], points[order[0] * 2 + 1],
				points[order[1] * 2], points[order[1] * 2 + 1],
				points[order[2] * 2], points[order[2] * 2 + 1]);
		return image;
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
