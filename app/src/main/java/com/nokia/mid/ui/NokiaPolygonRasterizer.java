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

import android.graphics.Path;

import java.util.Arrays;

/**
 * Rasterizes Nokia DirectGraphics polygons onto the integer J2ME pixel grid.
 *
 * Android/Skia treats a polygon as continuous vector geometry. Old Nokia
 * implementations rasterized integer-coordinate primitives directly to pixels,
 * including the polygon boundary. The distinction is visible for translucent
 * polygons that meet at integer boundaries, where vector edge coverage can
 * otherwise leave a one-pixel seam.
 */
final class NokiaPolygonRasterizer {

	private NokiaPolygonRasterizer() {
	}

	static void buildPath(Path output,
						  int[] xPoints, int xOffset,
						  int[] yPoints, int yOffset,
						  int nPoints) {
		output.reset();
		output.setFillType(Path.FillType.WINDING);

		if (nPoints < 3) {
			return;
		}

		int minY = yPoints[yOffset];
		int maxY = minY;
		for (int i = 1; i < nPoints; i++) {
			int y = yPoints[yOffset + i];
			if (y < minY) minY = y;
			if (y > maxY) maxY = y;
		}

		double[] intersections = new double[nPoints];

		// Fill the geometric interior using an even-odd scanline rule. Pixel
		// centers are sampled at (x + 0.5, y + 0.5), matching the J2ME grid where
		// integer coordinates describe boundaries between filled pixel cells.
		for (int y = minY; y < maxY; y++) {
			double scanY = y + 0.5d;
			int count = 0;

			int previous = nPoints - 1;
			for (int current = 0; current < nPoints; current++) {
				int x1 = xPoints[xOffset + previous];
				int y1 = yPoints[yOffset + previous];
				int x2 = xPoints[xOffset + current];
				int y2 = yPoints[yOffset + current];

				if ((y1 <= scanY && y2 > scanY) || (y2 <= scanY && y1 > scanY)) {
					intersections[count++] = x1
							+ (scanY - y1) * (double) (x2 - x1) / (double) (y2 - y1);
				}
				previous = current;
			}

			if (count < 2) {
				continue;
			}
			Arrays.sort(intersections, 0, count);
			for (int i = 0; i + 1 < count; i += 2) {
				int left = (int) Math.ceil(intersections[i] - 0.5d);
				int rightExclusive = (int) Math.ceil(intersections[i + 1] - 0.5d);
				if (rightExclusive > left) {
					output.addRect(left, y, rightExclusive, y + 1, Path.Direction.CW);
				}
			}
		}

		// Nokia integer primitives include the polygon's line segments in the
		// filled result. Union a one-pixel Bresenham boundary into the same Path,
		// so boundary pixels are blended only once for this fill operation.
		int previous = nPoints - 1;
		for (int current = 0; current < nPoints; current++) {
			addLine(output,
					xPoints[xOffset + previous], yPoints[yOffset + previous],
					xPoints[xOffset + current], yPoints[yOffset + current]);
			previous = current;
		}
	}

	private static void addLine(Path output, int x0, int y0, int x1, int y1) {
		long dx = Math.abs((long) x1 - x0);
		long sx = x0 < x1 ? 1L : -1L;
		long dy = -Math.abs((long) y1 - y0);
		long sy = y0 < y1 ? 1L : -1L;
		long error = dx + dy;
		long x = x0;
		long y = y0;

		while (true) {
			output.addRect((float) x, (float) y, (float) x + 1.0f, (float) y + 1.0f,
					Path.Direction.CW);
			if (x == x1 && y == y1) {
				break;
			}
			long twiceError = error << 1;
			if (twiceError >= dy) {
				error += dy;
				x += sx;
			}
			if (twiceError <= dx) {
				error += dx;
				y += sy;
			}
		}
	}
}
