/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RuntimeVirtualKeyboardPersistenceTest {
	@Test
	public void sameLayoutSelectionNeedsNoPersistence() {
		assertFalse(RuntimeVirtualKeyboardPersistence.layoutSelectionChanged(3, 3));
		assertTrue(RuntimeVirtualKeyboardPersistence.layoutSelectionChanged(3, 4));
	}

	@Test
	public void unchangedHiddenButtonsNeedNoPersistence() {
		boolean[] current = new boolean[] {false, true, false};

		assertFalse(RuntimeVirtualKeyboardPersistence.hiddenButtonsChanged(
				current, new boolean[] {false, true, false}));
		assertFalse(RuntimeVirtualKeyboardPersistence.hiddenButtonsChanged(
				current, new boolean[] {false, true}));
		assertTrue(RuntimeVirtualKeyboardPersistence.hiddenButtonsChanged(
				current, new boolean[] {true, true, false}));
	}
}
