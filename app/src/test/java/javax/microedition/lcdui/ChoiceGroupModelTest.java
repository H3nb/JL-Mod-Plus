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

package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;

/**
 * JVM characterization of the ChoiceGroup model, with focus on POPUP
 * selection semantics that feed the popup image-parity renderer.
 * Image objects are not constructed on the JVM (Bitmap is Android-only);
 * null images exercise the state logic completely.
 */
public class ChoiceGroupModelTest {
	private static ChoiceGroup popup(String... elements) {
		return new ChoiceGroup("Popup", Choice.POPUP, elements, null);
	}

	private static ChoiceGroup exclusive(String... elements) {
		return new ChoiceGroup("Choice", Choice.EXCLUSIVE, elements, null);
	}

	private static ChoiceGroup multiple(String... elements) {
		return new ChoiceGroup("Choice", Choice.MULTIPLE, elements, null);
	}

	@Test
	public void constructorRejectsUnsupportedType() {
		try {
			new ChoiceGroup("Bad", Choice.IMPLICIT);
			assertTrue("IMPLICIT choice type must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
	}

	@Test
	public void constructorRejectsNullElements() {
		try {
			new ChoiceGroup("Bad", Choice.POPUP, null, null);
			assertTrue("NULL elements array must be rejected", false);
		} catch (NullPointerException expected) {
			// contract
		}
	}

	@Test
	public void constructorRejectsLengthMismatch() {
		try {
			new ChoiceGroup("Bad", Choice.POPUP,
					new String[] {"A"}, new Image[] {null, null});
			assertTrue("length mismatch must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
	}

	@Test
	public void constructorSelectsFirstForSingleSelectionTypes() {
		assertEquals(0, popup("A", "B").getSelectedIndex());
		assertEquals(0, exclusive("A", "B").getSelectedIndex());
	}

	@Test
	public void appendAutoSelectsFirstExceptMultiple() {
		ChoiceGroup popup = new ChoiceGroup("Popup", Choice.POPUP);
		assertEquals(-1, popup.getSelectedIndex());
		assertEquals(0, popup.append("A", null));
		assertEquals("first appended POPUP item is auto-selected", 0, popup.getSelectedIndex());

		ChoiceGroup multiple = new ChoiceGroup("Choice", Choice.MULTIPLE);
		assertEquals(0, multiple.append("A", null));
		assertEquals(-1, multiple.getSelectedIndex());
	}

	@Test
	public void appendAfterFirstDoesNotChangeSelection() {
		ChoiceGroup popup = popup("A");
		popup.append("B", null);
		popup.append("C", null);
		assertEquals(0, popup.getSelectedIndex());
		popup.setSelectedIndex(2, true);
		popup.append("D", null);
		assertEquals(2, popup.getSelectedIndex());
	}

	@Test
	public void setSelectedIndexMovesSingleSelection() {
		ChoiceGroup popup = popup("A", "B", "C");
		popup.setSelectedIndex(1, true);
		assertEquals(1, popup.getSelectedIndex());
		assertTrue(popup.isSelected(1));
		assertFalse(popup.isSelected(0));
		assertFalse(popup.isSelected(2));
	}

	@Test
	public void setSelectedIndexDeselectIsNoOpForSingleSelection() {
		ChoiceGroup popup = popup("A", "B", "C");
		popup.setSelectedIndex(1, true);
		popup.setSelectedIndex(0, false);
		assertEquals(1, popup.getSelectedIndex());
	}

	@Test
	public void setSelectedIndexOutOfBoundsThrows() {
		ChoiceGroup popup = popup("A", "B");
		try {
			popup.setSelectedIndex(2, true);
			assertTrue("out-of-bounds selection must throw", false);
		} catch (IndexOutOfBoundsException expected) {
			// contract
		}
	}

	@Test
	public void deleteSelectedItemSelectsNearestRemainingItem() {
		ChoiceGroup popup = popup("A", "B", "C");
		popup.setSelectedIndex(1, true);
		popup.delete(1);
		assertEquals("delete must re-select min(removed, new size - 1)", 1, popup.getSelectedIndex());
		assertEquals("C", popup.getString(1));

		popup = popup("A");
		popup.delete(0);
		assertEquals("delete last item clears selection", -1, popup.getSelectedIndex());
	}

	@Test
	public void multipleDeleteDoesNotAutoSelectRemaining() {
		ChoiceGroup multiple = multiple("A", "B", "C");
		multiple.setSelectedIndex(1, true);
		multiple.delete(1);
		assertEquals(-1, multiple.getSelectedIndex());
		assertFalse(multiple.isSelected(1));
	}

	@Test
	public void deleteNonSelectedKeepsSelectionPositionally() {
		ChoiceGroup popup = popup("A", "B", "C");
		popup.setSelectedIndex(2, true);
		popup.delete(0);
		assertEquals(1, popup.getSelectedIndex());
	}

	@Test
	public void deleteAllClearsSelectionAndItems() {
		ChoiceGroup popup = popup("A", "B", "C");
		popup.deleteAll();
		assertEquals(0, popup.size());
		assertEquals(-1, popup.getSelectedIndex());
	}

	@Test
	public void insertIntoEmptyListSelectsNewItem() {
		ChoiceGroup popup = new ChoiceGroup("Popup", Choice.POPUP);
		popup.insert(0, "A", null);
		assertEquals(0, popup.getSelectedIndex());
	}

	@Test
	public void insertIntoNonEmptyListDoesNotChangeSelection() {
		ChoiceGroup popup = popup("A", "B");
		popup.setSelectedIndex(1, true);
		popup.insert(0, "X", null);
		assertEquals("insert shifts existing selection index", 2, popup.getSelectedIndex());
	}

	@Test
	public void setUpdatesTextAndImageButKeepsSelection() {
		ChoiceGroup popup = popup("A", "B");
		popup.setSelectedIndex(1, true);
		popup.set(1, "B2", null);
		assertEquals("B2", popup.getString(1));
		assertNull(popup.getImage(1));
		assertEquals(1, popup.getSelectedIndex());
	}

	@Test
	public void multipleFlagsToggle() {
		ChoiceGroup multiple = multiple("A", "B", "C");
		boolean[] flags = new boolean[3];
		multiple.setSelectedFlags(new boolean[] {true, false, true});
		assertEquals(2, multiple.getSelectedFlags(flags));
		assertTrue(flags[0]);
		assertFalse(flags[1]);
		assertTrue(flags[2]);
		assertEquals(-1, multiple.getSelectedIndex());

		multiple.setSelectedIndex(1, true);
		multiple.setSelectedIndex(0, false);
		assertEquals(2, multiple.getSelectedFlags(flags));
		assertFalse(flags[0]);
		assertTrue(flags[1]);
		assertTrue(flags[2]);
	}

	@Test
	public void setSelectedFlagsPicksFirstTrueForSingleSelection() {
		ChoiceGroup popup = popup("A", "B", "C");
		popup.setSelectedFlags(new boolean[] {false, true, false});
		assertEquals(1, popup.getSelectedIndex());
	}

	@Test
	public void setSelectedFlagsShortArrayThrows() {
		ChoiceGroup popup = popup("A", "B");
		try {
			popup.setSelectedFlags(new boolean[1]);
			assertTrue("short flag array must throw", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
	}

	@Test
	public void popupClickOnAlreadySelectedItemKeepsSelection() throws Exception {
		ChoiceGroup popup = popup("A", "B", "C");
		popup.setSelectedIndex(0, true);
		Method method = ChoiceGroup.class.getDeclaredMethod("onItemClick", long.class);
		method.setAccessible(true);
		method.invoke(popup, getItemUiId(popup, 0));
		assertEquals("POPUP click on the selected item must not deselect", 0, popup.getSelectedIndex());
	}

	@Test
	public void exclusiveClickOnAlreadySelectedItemKeepsSelection() throws Exception {
		ChoiceGroup exclusive = exclusive("A", "B", "C");
		Method method = ChoiceGroup.class.getDeclaredMethod("onItemClick", long.class);
		method.setAccessible(true);
		method.invoke(exclusive, getItemUiId(exclusive, 0));
		assertEquals(0, exclusive.getSelectedIndex());
		method.invoke(exclusive, getItemUiId(exclusive, 1));
		assertEquals(1, exclusive.getSelectedIndex());
	}

	@Test
	public void staleClickOutOfBoundsIsIgnored() throws Exception {
		ChoiceGroup popup = popup("A", "B", "C");
		popup.setSelectedIndex(2, true);
		long removedId = getItemUiId(popup, 2);
		popup.delete(2);
		int selectionAfterDelete = popup.getSelectedIndex();
		Method method = ChoiceGroup.class.getDeclaredMethod("onItemClick", long.class);
		method.setAccessible(true);
		method.invoke(popup, removedId);
		assertEquals("stale click must not throw or change state", selectionAfterDelete, popup.getSelectedIndex());
		assertEquals(2, popup.size());
	}

	@Test
	public void staleClickWithValidIndexRoutesToCorrectItem() throws Exception {
		ChoiceGroup popup = popup("A", "B", "C");
		popup.setSelectedIndex(2, true);
		long itemId = getItemUiId(popup, 2);
		popup.delete(0);
		Method method = ChoiceGroup.class.getDeclaredMethod("onItemClick", long.class);
		method.setAccessible(true);
		method.invoke(popup, itemId);
		assertEquals("callback must resolve by identity, not stale index", 1, popup.getSelectedIndex());
		assertEquals("C", popup.getString(1));
	}

	private static long getItemUiId(ChoiceGroup group, int index) throws Exception {
		java.lang.reflect.Field itemsField = ChoiceGroup.class.getDeclaredField("items");
		itemsField.setAccessible(true);
		@SuppressWarnings("unchecked")
		java.util.List<javax.microedition.lcdui.list.CompoundItem> items =
				(java.util.List<javax.microedition.lcdui.list.CompoundItem>) itemsField.get(group);
		return items.get(index).getUiId();
	}

	@Test
	public void sizeAndGetStringContract() {
		ChoiceGroup popup = popup("A", "B");
		assertEquals(2, popup.size());
		assertEquals("A", popup.getString(0));
		try {
			popup.getString(2);
			assertTrue("out-of-bounds getString must throw", false);
		} catch (IndexOutOfBoundsException expected) {
			// contract
		}
	}
}
