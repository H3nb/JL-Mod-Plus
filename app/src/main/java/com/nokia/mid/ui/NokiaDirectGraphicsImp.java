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

import android.graphics.Paint;
import android.graphics.Path;

import javax.microedition.lcdui.Graphics;

/**
 * Nokia DirectGraphics implementation with pixel-grid polygon rasterization.
 * Other DirectGraphics behavior remains in DirectGraphicsImp.
 */
final class NokiaDirectGraphicsImp extends DirectGraphicsImp {
	private final Graphics graphics;
	private final Paint polygonPaint = new Paint();
	private final Path polygonPath = new Path();
	private final NokiaPolygonRasterizer polygonRasterizer = new NokiaPolygonRasterizer();
	private final int[] triangleX = new int[3];
	private final int[] triangleY = new int[3];

	NokiaDirectGraphicsImp(Graphics graphics) {
		super(graphics);
		this.graphics = graphics;
		polygonPaint.setStyle(Paint.Style.FILL);
		polygonPaint.setAntiAlias(false);
	}

	@Override
	public void fillPolygon(int[] xPoints,
							int xOffset,
							int[] yPoints,
							int yOffset,
							int nPoints,
							int argbColor) {
		if (nPoints < 3) {
			super.fillPolygon(xPoints, xOffset, yPoints, yOffset, nPoints, argbColor);
			return;
		}

		int clipLeft = graphics.getClipX();
		int clipTop = graphics.getClipY();
		int clipRight = clipLeft + graphics.getClipWidth();
		int clipBottom = clipTop + graphics.getClipHeight();

		polygonRasterizer.buildPath(
				polygonPath, xPoints, xOffset, yPoints, yOffset, nPoints,
				clipLeft, clipTop, clipRight, clipBottom);
		polygonPaint.setColor(argbColor);
		graphics.getCanvas().drawPath(polygonPath, polygonPaint);
	}

	@Override
	public void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int argbColor) {
		triangleX[0] = x1;
		triangleX[1] = x2;
		triangleX[2] = x3;
		triangleY[0] = y1;
		triangleY[1] = y2;
		triangleY[2] = y3;
		fillPolygon(triangleX, 0, triangleY, 0, 3, argbColor);
	}
}
