/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.lcdui.keyboard;

import android.graphics.RectF;

/** Host-control-surface orientation used to select an orientation-specific virtual layout slot. */
public enum VirtualLayoutOrientation {
	PORTRAIT,
	LANDSCAPE;

	private static final float SQUARE_EPSILON_PX = 0.5f;

	public static VirtualLayoutOrientation resolve(
			RectF bounds, VirtualLayoutOrientation previous) {
		if (bounds == null) return previous != null ? previous : PORTRAIT;
		return resolve(bounds.width(), bounds.height(), previous);
	}

	public static VirtualLayoutOrientation resolve(
			float width, float height, VirtualLayoutOrientation previous) {
		if (!Float.isFinite(width) || !Float.isFinite(height) ||
				width <= 0.0f || height <= 0.0f ||
				Math.abs(width - height) <= SQUARE_EPSILON_PX) {
			return previous != null ? previous : PORTRAIT;
		}
		return width > height ? LANDSCAPE : PORTRAIT;
	}
}
