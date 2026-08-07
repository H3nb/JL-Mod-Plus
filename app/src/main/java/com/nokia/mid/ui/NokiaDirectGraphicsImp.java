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

		NokiaPolygonRasterizer.buildPath(
				polygonPath, xPoints, xOffset, yPoints, yOffset, nPoints);
		polygonPaint.setColor(argbColor);
		graphics.getCanvas().drawPath(polygonPath, polygonPaint);
	}
}
