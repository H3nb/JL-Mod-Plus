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

		// Rectangles are common in J2ME effects. Only take this fast path when
		// the polygon consists of all four distinct bounding-box corners exactly
		// once. Repeated or missing corners are degenerate polygons and must use
		// the normal even-odd path instead of being expanded into a full box.
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
							+ (scanY - y1) * ((double) x2 - (double) x1)
							/ ((double) y2 - (double) y1);
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

		// Include Nokia's one-pixel polygon boundary. The visible Bresenham
		// samples are calculated directly from the major-axis step instead of
		// walking from an off-screen endpoint. This preserves the same integer
		// line phase for ordinary coordinates while bounding work to the clip
		// width/height even when games submit extreme world coordinates.
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

		int seenCorners = 0;
		for (int i = 0; i < 4; i++) {
			int next = (i + 1) & 3;
			int x1 = xPoints[xOffset + i];
			int y1 = yPoints[yOffset + i];
			int x2 = xPoints[xOffset + next];
			int y2 = yPoints[yOffset + next];

			if (x1 != x2 && y1 != y2) {
				return false;
			}

			int corner;
			if (x1 == minX && y1 == minY) {
				corner = 1;
			} else if (x1 == maxX && y1 == minY) {
				corner = 2;
			} else if (x1 == maxX && y1 == maxY) {
				corner = 4;
			} else if (x1 == minX && y1 == maxY) {
				corner = 8;
			} else {
				return false;
			}

			if ((seenCorners & corner) != 0) {
				return false;
			}
			seenCorners |= corner;
		}
		return seenCorners == 0x0F;
	}

	private static void addLineRuns(Path output,
								int x0, int y0, int x1, int y1,
								int clipLeft, int clipTop,
								int clipRight, int clipBottom) {
		long edgeMinX = Math.min(x0, x1);
		long edgeMaxX = Math.max(x0, x1);
		long edgeMinY = Math.min(y0, y1);
		long edgeMaxY = Math.max(y0, y1);
		if (edgeMaxX + 1L <= clipLeft || edgeMinX >= clipRight
				|| edgeMaxY + 1L <= clipTop || edgeMinY >= clipBottom) {
			return;
		}

		if (y0 == y1) {
			addClippedRect(output, edgeMinX, y0,
					edgeMaxX + 1L, (long) y0 + 1L,
					clipLeft, clipTop, clipRight, clipBottom);
			return;
		}
		if (x0 == x1) {
			addClippedRect(output, x0, edgeMinY,
					(long) x0 + 1L, edgeMaxY + 1L,
					clipLeft, clipTop, clipRight, clipBottom);
			return;
		}

		long dx = Math.abs((long) x1 - x0);
		long dy = Math.abs((long) y1 - y0);
		long sx = x0 < x1 ? 1L : -1L;
		long sy = y0 < y1 ? 1L : -1L;

		if (dx >= dy) {
			long from;
			long to;
			if (sx > 0L) {
				from = Math.max(0L, (long) clipLeft - x0);
				to = Math.min(dx, (long) clipRight - 1L - x0);
			} else {
				from = Math.max(0L, (long) x0 - ((long) clipRight - 1L));
				to = Math.min(dx, (long) x0 - clipLeft);
			}
			if (from > to) {
				return;
			}

			long runY = Long.MIN_VALUE;
			long runMinX = 0L;
			long runMaxX = 0L;
			for (long step = from; step <= to; step++) {
				long x = (long) x0 + sx * step;
				long y = (long) y0 + sy * roundedRatio(dy, step, dx);
				if (y < clipTop || y >= clipBottom) {
					if (runY != Long.MIN_VALUE) {
						flushRun(output, runMinX, runMaxX, runY,
								clipLeft, clipTop, clipRight, clipBottom);
						runY = Long.MIN_VALUE;
					}
					continue;
				}
				if (runY == y) {
					if (x < runMinX) runMinX = x;
					if (x > runMaxX) runMaxX = x;
				} else {
					if (runY != Long.MIN_VALUE) {
						flushRun(output, runMinX, runMaxX, runY,
								clipLeft, clipTop, clipRight, clipBottom);
					}
					runY = y;
					runMinX = x;
					runMaxX = x;
				}
			}
			if (runY != Long.MIN_VALUE) {
				flushRun(output, runMinX, runMaxX, runY,
						clipLeft, clipTop, clipRight, clipBottom);
			}
			return;
		}

		long from;
		long to;
		if (sy > 0L) {
			from = Math.max(0L, (long) clipTop - y0);
			to = Math.min(dy, (long) clipBottom - 1L - y0);
		} else {
			from = Math.max(0L, (long) y0 - ((long) clipBottom - 1L));
			to = Math.min(dy, (long) y0 - clipTop);
		}
		if (from > to) {
			return;
		}

		for (long step = from; step <= to; step++) {
			long y = (long) y0 + sy * step;
			long x = (long) x0 + sx * roundedRatio(dx, step, dy);
			if (x >= clipLeft && x < clipRight) {
				flushRun(output, x, x, y, clipLeft, clipTop, clipRight, clipBottom);
			}
		}
	}

	/** Returns round(numerator * step / denominator) without overflowing long for normal J2ME ranges. */
	private static long roundedRatio(long numerator, long step, long denominator) {
		if (numerator == 0L || step == 0L) {
			return 0L;
		}
		if (step <= Long.MAX_VALUE / numerator) {
			long product = numerator * step;
			long quotient = product / denominator;
			long remainder = product % denominator;
			if (remainder * 2L >= denominator) {
				quotient++;
			}
			return quotient;
		}

		// Only reachable for near-full 32-bit coordinate spans. Double retains
		// ample precision after division for the integer pixel result and, most
		// importantly, keeps pathological off-screen edges bounded by the clip.
		return (long) Math.floor((double) numerator * (double) step / (double) denominator + 0.5d);
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
