/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.lcdui.keyboard;

import java.util.Arrays;

/** Immutable semantic state for one virtual-controls layout edit transaction. */
public final class VirtualKeyboardLayoutSnapshot {
	final int layoutVariant;
	final boolean[] visible;
	final int[] snapOrigins;
	final int[] snapModes;
	final float[] snapOffsetX;
	final float[] snapOffsetY;
	final float[] keyScales;

	final boolean hasGroupedControls;
	final boolean dpadEnabled;
	final boolean analogEnabled;
	final float dpadCenterX;
	final float dpadCenterY;
	final float dpadRadius;
	final float analogCenterX;
	final float analogCenterY;
	final float analogRadius;
	final boolean standardTemplate;
	final boolean standardTemplateEdited;

	private VirtualKeyboardLayoutSnapshot(
			int layoutVariant,
			boolean[] visible,
			int[] snapOrigins,
			int[] snapModes,
			float[] snapOffsetX,
			float[] snapOffsetY,
			float[] keyScales,
			boolean hasGroupedControls,
			boolean dpadEnabled,
			boolean analogEnabled,
			float dpadCenterX,
			float dpadCenterY,
			float dpadRadius,
			float analogCenterX,
			float analogCenterY,
			float analogRadius,
			boolean standardTemplate,
			boolean standardTemplateEdited) {
		this.layoutVariant = layoutVariant;
		this.visible = visible.clone();
		this.snapOrigins = snapOrigins.clone();
		this.snapModes = snapModes.clone();
		this.snapOffsetX = snapOffsetX.clone();
		this.snapOffsetY = snapOffsetY.clone();
		this.keyScales = keyScales.clone();
		this.hasGroupedControls = hasGroupedControls;
		this.dpadEnabled = dpadEnabled;
		this.analogEnabled = analogEnabled;
		this.dpadCenterX = dpadCenterX;
		this.dpadCenterY = dpadCenterY;
		this.dpadRadius = dpadRadius;
		this.analogCenterX = analogCenterX;
		this.analogCenterY = analogCenterY;
		this.analogRadius = analogRadius;
		this.standardTemplate = standardTemplate;
		this.standardTemplateEdited = standardTemplateEdited;
	}

	static VirtualKeyboardLayoutSnapshot legacy(
			int layoutVariant,
			boolean[] visible,
			int[] snapOrigins,
			int[] snapModes,
			float[] snapOffsetX,
			float[] snapOffsetY,
			float[] keyScales) {
		return new VirtualKeyboardLayoutSnapshot(
				layoutVariant,
				visible,
				snapOrigins,
				snapModes,
				snapOffsetX,
				snapOffsetY,
				keyScales,
				false,
				false,
				false,
				0.0f, 0.0f, 0.0f,
				0.0f, 0.0f, 0.0f,
				false,
				false);
	}

	VirtualKeyboardLayoutSnapshot withGroupedControls(
			boolean dpadEnabled,
			boolean analogEnabled,
			float dpadCenterX,
			float dpadCenterY,
			float dpadRadius,
			float analogCenterX,
			float analogCenterY,
			float analogRadius,
			boolean standardTemplate,
			boolean standardTemplateEdited) {
		return new VirtualKeyboardLayoutSnapshot(
				layoutVariant,
				visible,
				snapOrigins,
				snapModes,
				snapOffsetX,
				snapOffsetY,
				keyScales,
				true,
				dpadEnabled,
				analogEnabled,
				dpadCenterX,
				dpadCenterY,
				dpadRadius,
				analogCenterX,
				analogCenterY,
				analogRadius,
				standardTemplate,
				standardTemplateEdited);
	}

	boolean matchesLegacyShape(int keyCount, int scaleCount) {
		return visible.length == keyCount &&
				snapOrigins.length == keyCount &&
				snapModes.length == keyCount &&
				snapOffsetX.length == keyCount &&
				snapOffsetY.length == keyCount &&
				keyScales.length == scaleCount;
	}

	boolean isUneditedStandardTemplate() {
		return standardTemplate && !standardTemplateEdited;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof VirtualKeyboardLayoutSnapshot that)) return false;
		if (layoutVariant != that.layoutVariant ||
				hasGroupedControls != that.hasGroupedControls ||
				standardTemplate != that.standardTemplate) {
			return false;
		}
		if (standardTemplate && standardTemplateEdited != that.standardTemplateEdited) return false;
		// Built-in standard templates are viewport-derived while untouched. Orientation/reflow may
		// change their concrete snap/scales and normalized geometry without changing user intent.
		if (isUneditedStandardTemplate() && that.isUneditedStandardTemplate()) return true;
		if (!Arrays.equals(visible, that.visible) ||
				!Arrays.equals(snapOrigins, that.snapOrigins) ||
				!Arrays.equals(snapModes, that.snapModes) ||
				!Arrays.equals(snapOffsetX, that.snapOffsetX) ||
				!Arrays.equals(snapOffsetY, that.snapOffsetY) ||
				!Arrays.equals(keyScales, that.keyScales)) {
			return false;
		}
		if (!hasGroupedControls) return true;
		return dpadEnabled == that.dpadEnabled &&
				analogEnabled == that.analogEnabled &&
				Float.compare(dpadCenterX, that.dpadCenterX) == 0 &&
				Float.compare(dpadCenterY, that.dpadCenterY) == 0 &&
				Float.compare(dpadRadius, that.dpadRadius) == 0 &&
				Float.compare(analogCenterX, that.analogCenterX) == 0 &&
				Float.compare(analogCenterY, that.analogCenterY) == 0 &&
				Float.compare(analogRadius, that.analogRadius) == 0;
	}

	@Override
	public int hashCode() {
		int result = 31 * layoutVariant + Boolean.hashCode(hasGroupedControls);
		result = 31 * result + Boolean.hashCode(standardTemplate);
		if (standardTemplate) result = 31 * result + Boolean.hashCode(standardTemplateEdited);
		if (isUneditedStandardTemplate()) return result;
		result = 31 * result + Arrays.hashCode(visible);
		result = 31 * result + Arrays.hashCode(snapOrigins);
		result = 31 * result + Arrays.hashCode(snapModes);
		result = 31 * result + Arrays.hashCode(snapOffsetX);
		result = 31 * result + Arrays.hashCode(snapOffsetY);
		result = 31 * result + Arrays.hashCode(keyScales);
		if (hasGroupedControls) {
			result = 31 * result + Boolean.hashCode(dpadEnabled);
			result = 31 * result + Boolean.hashCode(analogEnabled);
			result = 31 * result + Float.hashCode(dpadCenterX);
			result = 31 * result + Float.hashCode(dpadCenterY);
			result = 31 * result + Float.hashCode(dpadRadius);
			result = 31 * result + Float.hashCode(analogCenterX);
			result = 31 * result + Float.hashCode(analogCenterY);
			result = 31 * result + Float.hashCode(analogRadius);
		}
		return result;
	}
}
