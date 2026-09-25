/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

import java.util.Arrays;

/** Keeps runtime no-op checks ahead of ownership detachment and filesystem publication. */
final class RuntimeVirtualKeyboardPersistence {
	private RuntimeVirtualKeyboardPersistence() {
	}

	static boolean layoutSelectionChanged(int currentLayout, int selectedLayout) {
		return currentLayout != selectedLayout;
	}

	static boolean hiddenButtonsChanged(boolean[] current, boolean[] changed) {
		return current != null
				&& changed != null
				&& current.length == changed.length
				&& !Arrays.equals(current, changed);
	}
}
