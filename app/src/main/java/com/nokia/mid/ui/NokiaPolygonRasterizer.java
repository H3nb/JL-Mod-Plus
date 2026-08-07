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
 * The implementation deliberately keeps the proven integer-coverage behavior,
 * but emits horizontal spans instead of one Path rectangle per boundary pixel.
 * This keeps Nokia-compatible edge coverage while leaving the expensive final
 * fill and alpha compositing to Android/Skia in a single draw operation.
 */
final class NokiaPolygonRasterizer {
	private double[] intersections = new double[8];

	void buildPath(Path output,
				   int[] xPoints, int xOffset,
				   int[] yPoints, int yOffset,
				   int nPoints,
				   int clipLeft, int clipTop,
				   int clipRight, int clipBottom) {
		output.reset();
		output.setFillType(Path.FillType.WINDING);

		if (nPoints < 3 || clipLeft >= clipRight || clipTop >= clipBottom) {
			return;
		}

		int minX = xPoints[xOffset];
		int maxX = minX;
		int minY = yPoints[yOffset];
		int maxY = minY;
		for (int i = 1; i < nPoints; i++) {
			int x = xPoints[xOffset + i];
			int y = yPoints[yOffset + i];
			if (x < minX) minX = x;
			if (x > maxX) maxX = x;
			if (y < minY) minY = y;
			if (y > maxY) maxY = y;
		}

		// Filled Nokia boundary pixels are inclusive at maxX/maxY, hence the
		// +1 on the right/bottom side of this conservative visibility check.
		if ((long) maxX + 1L <= clipLeft || minX >= clipRight
				|| (long) maxY + 1L <= clipTop || minY >= clipBottom) {
			return;
		}

		// Rectangles are common in J2ME effects. They can use the same inclusive
		// Nokia pixel coverage with a single Path rectangle and no scanline work.
		if (isAxisAlignedRectangle(xPoints, xOffset, yPoints, yOffset, nPoints,
				minX, maxX, minY, maxY)) {
			addClippedRect(output, minX, minY,
					(long) maxX + 1L, (long) maxY + 1L,
					clipLeft, clipTop, clipRight, clipBottom);
			return;
		}

		ensureIntersectionCapacity(nPoints);

		// Fill the geometric interior using the Nokia even-odd rule. Pixel
		// centers are sampled at (x + 0.5, y + 0.5). Each covered scanline is
		// represented by one horizontal span per inside interval.
		int firstY = Math.max(minY, clipTop);
		int lastYExclusive = Math.min(maxY, clipBottom);
		for (int y = firstY; y < lastYExclusive; y++) {
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
				addClippedRect(output, left, y, rightExclusive, (long) y + 1L,
						clipLeft, clipTop, clipRight, clipBottom);
			}
		}

		// Include Nokia's one-pixel polygon boundary. Horizontal and vertical
		// edges collapse to one rectangle; general Bresenham edges are compressed
		// into one horizontal run for each touched row instead of one rectangle
		// per pixel. Because all spans are subpaths of a single WINDING Path,
		// overlapping interior/boundary coverage is alpha-blended only once.
		int previous = nPoints - 1;
		for (int current = 0; current < nPoints; current++) {
			addLineRuns(output,
					xPoints[xOffset + previous], yPoints[yOffset + previous],
					xPoints[xOffset + current], yPoints[yOffset + current],
					clipLeft, clipTop, clipRight, clipBottom);
			previous = current;
		}
	}

	private void ensureIntersectionCapacity(int required) {
		if (intersections.length >= required) {
			return;
		}
		int size = intersections.length;
		while (size < required) {
			size <<= 1;
		}
		intersections = new double[size];
	}

	private static boolean isAxisAlignedRectangle(int[] xPoints, int xOffset,
											  int[] yPoints, int yOffset,
											  int nPoints,
											  int minX, int maxX,
											  int minY, int maxY) {
		if (nPoints != 4 || minX == maxX || minY == maxY) {
			return false;
		}
		for (int i = 0; i < 4; i++) {
			int next = (i + 1) & 3;
			int x1 = xPoints[xOffset + i];
			int y1 = yPoints[yOffset + i];
			int x2 = xPoints[xOffset + next];
			int y2 = yPoints[yOffset + next];
			if (x1 != x2 && y1 != y2) {
				return false;
			}
			if ((x1 != minX && x1 != maxX) || (y1 != minY && y1 != maxY)) {
				return false;
			}
		}
		return true;
	}

	private static void addLineRuns(Path output,
								int x0, int y0, int x1, int y1,
								int clipLeft, int clipTop,
								int clipRight, int clipBottom) {
		int edgeMinX = Math.min(x0, x1);
		int edgeMaxX = Math.max(x0, x1);
		int edgeMinY = Math.min(y0, y1);
		int edgeMaxY = Math.max(y0, y1);
		if ((long) edgeMaxX + 1L <= clipLeft || edgeMinX >= clipRight
				|| (long) edgeMaxY + 1L <= clipTop || edgeMinY >= clipBottom) {
			return;
		}

		if (y0 == y1) {
			addClippedRect(output, edgeMinX, y0,
					(long) edgeMaxX + 1L, (long) y0 + 1L,
					clipLeft, clipTop, clipRight, clipBottom);
			return;
		}
		if (x0 == x1) {
			addClippedRect(output, x0, edgeMinY,
					(long) x0 + 1L, (long) edgeMaxY + 1L,
					clipLeft, clipTop, clipRight, clipBottom);
			return;
		}

		long dx = Math.abs((long) x1 - x0);
		long sx = x0 < x1 ? 1L : -1L;
		long dy = -Math.abs((long) y1 - y0);
		long sy = y0 < y1 ? 1L : -1L;
		long error = dx + dy;
		long x = x0;
		long y = y0;

		long runY = y;
		long runMinX = x;
		long runMaxX = x;

		while (true) {
			if (x == x1 && y == y1) {
				flushRun(output, runMinX, runMaxX, runY,
						clipLeft, clipTop, clipRight, clipBottom);
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

			if (y == runY) {
				if (x < runMinX) runMinX = x;
				if (x > runMaxX) runMaxX = x;
			} else {
				flushRun(output, runMinX, runMaxX, runY,
						clipLeft, clipTop, clipRight, clipBottom);
				runY = y;
				runMinX = x;
				runMaxX = x;
			}
		}
	}

	private static void flushRun(Path output,
							 long minX, long maxX, long y,
							 int clipLeft, int clipTop,
							 int clipRight, int clipBottom) {
		addClippedRect(output, minX, y, maxX + 1L, y + 1L,
				clipLeft, clipTop, clipRight, clipBottom);
	}

	private static void addClippedRect(Path output,
							   long left, long top,
							   long right, long bottom,
							   int clipLeft, int clipTop,
							   int clipRight, int clipBottom) {
		long clippedLeft = Math.max(left, (long) clipLeft);
		long clippedTop = Math.max(top, (long) clipTop);
		long clippedRight = Math.min(right, (long) clipRight);
		long clippedBottom = Math.min(bottom, (long) clipBottom);
		if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) {
			return;
		}
		output.addRect((float) clippedLeft, (float) clippedTop,
				(float) clippedRight, (float) clippedBottom, Path.Direction.CW);
	}
}
