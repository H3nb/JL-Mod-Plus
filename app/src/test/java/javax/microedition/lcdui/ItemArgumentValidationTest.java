/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ItemArgumentValidationTest {
	@Test
	public void legalLayoutFlagsAndAlignmentCombinationsAreAccepted() {
		StringItem item = new StringItem(null, "text");
		for (int layout : new int[]{
				Item.LAYOUT_DEFAULT, Item.LAYOUT_CENTER, Item.LAYOUT_VCENTER,
				Item.LAYOUT_RIGHT | Item.LAYOUT_BOTTOM,
				Item.LAYOUT_SHRINK | Item.LAYOUT_EXPAND,
				Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER,
				Item.LAYOUT_VSHRINK | Item.LAYOUT_VEXPAND | Item.LAYOUT_2,
				Item.LAYOUT_CENTER | Item.LAYOUT_VCENTER | Item.LAYOUT_2}) {
			item.setLayout(layout);
			assertEquals(layout, item.getLayout());
		}
	}

	@Test
	public void unknownLayoutBitsAreRejectedWithoutChangingLayout() {
		StringItem item = new StringItem(null, "text");
		item.setLayout(Item.LAYOUT_RIGHT | Item.LAYOUT_TOP);
		for (int illegal : new int[]{-1, 4, 64, 128, 32768, Integer.MAX_VALUE}) {
			assertThrows(IllegalArgumentException.class, () -> item.setLayout(illegal));
			assertEquals(Item.LAYOUT_RIGHT | Item.LAYOUT_TOP, item.getLayout());
		}
	}

	@Test
	public void appearanceModeMustBePlainHyperlinkOrButton() {
		for (int mode : new int[]{Item.PLAIN, Item.HYPERLINK, Item.BUTTON}) {
			assertEquals(mode, new StringItem(null, null, mode).getAppearanceMode());
			assertEquals(mode,
					new ImageItem(null, null, Item.LAYOUT_DEFAULT, null, mode).getAppearanceMode());
		}
		for (int mode : new int[]{-1, 3, Integer.MAX_VALUE}) {
			assertThrows(IllegalArgumentException.class,
					() -> new StringItem(null, null, mode));
			assertThrows(IllegalArgumentException.class,
					() -> new ImageItem(null, null, Item.LAYOUT_DEFAULT, null, mode));
		}
	}

	@Test
	public void imageItemConstructorRejectsIllegalLayout() {
		assertThrows(IllegalArgumentException.class,
				() -> new ImageItem(null, null, 4, null));
		assertThrows(IllegalArgumentException.class,
				() -> new ImageItem(null, null, Integer.MIN_VALUE, null, Item.PLAIN));
	}
}
