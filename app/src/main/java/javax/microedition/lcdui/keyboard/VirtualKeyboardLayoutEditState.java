/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.lcdui.keyboard;

import java.util.Objects;

/** Complete immutable semantic baseline/current state for one layout-edit transaction. */
public final class VirtualKeyboardLayoutEditState {
	private final VirtualKeyboardLayoutSnapshot singleLayout;
	private final VirtualKeyboardLayoutState customLayout;

	private VirtualKeyboardLayoutEditState(
			VirtualKeyboardLayoutSnapshot singleLayout,
			VirtualKeyboardLayoutState customLayout) {
		this.singleLayout = singleLayout;
		this.customLayout = customLayout;
	}

	public static VirtualKeyboardLayoutEditState single(VirtualKeyboardLayoutSnapshot snapshot) {
		return new VirtualKeyboardLayoutEditState(Objects.requireNonNull(snapshot), null);
	}

	public static VirtualKeyboardLayoutEditState custom(VirtualKeyboardLayoutState state) {
		return new VirtualKeyboardLayoutEditState(null, Objects.requireNonNull(state));
	}

	public boolean isCustom() {
		return customLayout != null;
	}

	public VirtualKeyboardLayoutSnapshot singleLayout() {
		return singleLayout;
	}

	public VirtualKeyboardLayoutState customLayout() {
		return customLayout;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof VirtualKeyboardLayoutEditState that)) return false;
		return Objects.equals(singleLayout, that.singleLayout) &&
				Objects.equals(customLayout, that.customLayout);
	}

	@Override
	public int hashCode() {
		return Objects.hash(singleLayout, customLayout);
	}
}
