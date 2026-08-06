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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;

/** JVM characterization of the List model (no View is created in these tests). */
public class ListModelTest {
	private static List implicitList(String... elements) {
		return new List("Test", List.IMPLICIT, elements, null);
	}

	private static List multipleList(String... elements) {
		return new List("Test", List.MULTIPLE, elements, null);
	}

	private static List exclusiveList(String... elements) {
		return new List("Test", List.EXCLUSIVE, elements, null);
	}

	@Test
	public void constructorRejectsUnsupportedType() {
		try {
			new List("Test", Choice.POPUP);
			assertTrue("POPUP list type must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
	}

	@Test
	public void constructorRejectsNullElements() {
		try {
			new List("Test", List.IMPLICIT, null, null);
			assertTrue("NULL elements array must be rejected", false);
		} catch (NullPointerException expected) {
			// contract
		}
	}

	@Test
	public void constructorSelectsFirstForSingleSelectionTypes() {
		List list = implicitList("A", "B", "C");
		assertEquals(0, list.getSelectedIndex());
		list = exclusiveList("A", "B", "C");
		assertEquals(0, list.getSelectedIndex());
	}

	@Test
	public void appendAutoSelectsFirstExceptMultiple() {
		List implicit = new List("Test", List.IMPLICIT);
		assertEquals(-1, implicit.getSelectedIndex());
		assertEquals(0, implicit.append("A", null));
		assertEquals(0, implicit.getSelectedIndex());

		List multiple = new List("Test", List.MULTIPLE);
		assertEquals(0, multiple.append("A", null));
		assertEquals(-1, multiple.getSelectedIndex());
	}

	@Test
	public void appendAfterFirstDoesNotChangeSelection() {
		List list = implicitList("A");
		list.append("B", null);
		list.append("C", null);
		assertEquals(0, list.getSelectedIndex());
		list.setSelectedIndex(2, true);
		list.append("D", null);
		assertEquals(2, list.getSelectedIndex());
	}

	@Test
	public void setSelectedIndexMovesSingleSelection() {
		List list = implicitList("A", "B", "C");
		list.setSelectedIndex(1, true);
		assertEquals(1, list.getSelectedIndex());
		assertTrue(list.isSelected(1));
		assertFalse(list.isSelected(0));
		assertFalse(list.isSelected(2));
	}

	@Test
	public void setSelectedIndexDeselectIsNoOpForSingleSelection() {
		List list = implicitList("A", "B", "C");
		list.setSelectedIndex(1, true);
		list.setSelectedIndex(0, false);
		assertEquals(1, list.getSelectedIndex());
	}

	@Test
	public void setSelectedIndexOutOfBoundsThrows() {
		List list = implicitList("A", "B");
		try {
			list.setSelectedIndex(2, true);
			assertTrue("out-of-bounds selection must throw", false);
		} catch (IndexOutOfBoundsException expected) {
			// contract
		}
		try {
			list.setSelectedIndex(-1, true);
			assertTrue("negative selection must throw", false);
		} catch (IndexOutOfBoundsException expected) {
			// contract
		}
	}

	@Test
	public void setSelectedSameIndexStaysSelected() {
		List list = implicitList("A", "B", "C");
		list.setSelectedIndex(2, true);
		list.setSelectedIndex(2, true);
		assertEquals(2, list.getSelectedIndex());
	}

	@Test
	public void multipleGetSelectedIndexAlwaysMinusOne() {
		List list = multipleList("A", "B");
		list.setSelectedIndex(1, true);
		assertEquals(-1, list.getSelectedIndex());
	}

	@Test
	public void multipleToggleFlags() {
		List list = multipleList("A", "B", "C");
		boolean[] flags = new boolean[3];
		list.setSelectedFlags(new boolean[] {true, false, true});
		assertEquals(2, list.getSelectedFlags(flags));
		assertTrue(flags[0]);
		assertFalse(flags[1]);
		assertTrue(flags[2]);

		list.setSelectedIndex(1, true);
		list.setSelectedIndex(0, false);
		assertEquals(2, list.getSelectedFlags(flags));
		assertFalse(flags[0]);
		assertTrue(flags[1]);
		assertTrue(flags[2]);
	}

	@Test
	public void setSelectedFlagsPicksFirstTrueForSingleSelection() {
		List list = implicitList("A", "B", "C");
		list.setSelectedFlags(new boolean[] {false, true, false});
		assertEquals(1, list.getSelectedIndex());
	}

	@Test
	public void setSelectedFlagsShortArrayThrows() {
		List list = implicitList("A", "B");
		try {
			list.setSelectedFlags(new boolean[1]);
			assertTrue("short flag array must throw", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
	}

	@Test
	public void deleteSelectedItemSelectsNearestRemainingItem() {
		List list = implicitList("A", "B", "C");
		list.setSelectedIndex(1, true);
		list.delete(1);
		assertEquals("delete must re-select min(removed, new size - 1)", 1, list.getSelectedIndex());
		assertEquals("C", list.getString(1));

		list = implicitList("A");
		list.delete(0);
		assertEquals("delete last item clears selection", -1, list.getSelectedIndex());
	}

	@Test
	public void multipleDeleteDoesNotAutoSelectRemaining() {
		List list = multipleList("A", "B", "C");
		list.setSelectedIndex(1, true);
		list.delete(1);
		assertEquals(-1, list.getSelectedIndex());
		assertFalse(list.isSelected(1));
	}

	@Test
	public void deleteNonSelectedKeepsSelection() {
		List list = implicitList("A", "B", "C");
		list.setSelectedIndex(2, true);
		list.delete(0);
		assertEquals(1, list.getSelectedIndex());
	}

	@Test
	public void deleteOutOfBoundsThrows() {
		List list = implicitList("A");
		try {
			list.delete(1);
			assertTrue("out-of-bounds delete must throw", false);
		} catch (IndexOutOfBoundsException expected) {
			// contract
		}
	}

	@Test
	public void deleteAllClearsSelectionAndItems() {
		List list = implicitList("A", "B", "C");
		list.deleteAll();
		assertEquals(0, list.size());
		assertEquals(-1, list.getSelectedIndex());
	}

	@Test
	public void insertIntoEmptyListSelectsNewItem() {
		List list = new List("Test", List.IMPLICIT);
		list.insert(0, "A", null);
		assertEquals(0, list.getSelectedIndex());
	}

	@Test
	public void insertIntoNonEmptyListDoesNotChangeSelection() {
		List list = implicitList("A", "B");
		list.setSelectedIndex(1, true);
		list.insert(0, "X", null);
		assertEquals("insert shifts existing selection index", 2, list.getSelectedIndex());
		list.insert(3, "Y", null);
		assertEquals(2, list.getSelectedIndex());
	}

	@Test
	public void setUpdatesTextButKeepsSelection() {
		List list = implicitList("A", "B");
		list.setSelectedIndex(1, true);
		list.set(1, "B2", null);
		assertEquals("B2", list.getString(1));
		assertEquals(1, list.getSelectedIndex());
	}

	@Test
	public void staleClickCallbackIsIgnored() throws Exception {
		List list = implicitList("A", "B", "C");
		list.setSelectedIndex(2, true);
		long removedId = getItemUiId(list, 2);
		list.delete(2);
		int selectionAfterDelete = list.getSelectedIndex();
		Method method = List.class.getDeclaredMethod("onItemClick", long.class);
		method.setAccessible(true);
		method.invoke(list, removedId);
		assertEquals("stale click must not throw or change state", selectionAfterDelete, list.getSelectedIndex());
		assertEquals(2, list.size());
	}

	@Test
	public void staleLongClickCallbackIsIgnored() throws Exception {
		List list = implicitList("A", "B");
		long removedId = getItemUiId(list, 0);
		list.deleteAll();
		Method method = List.class.getDeclaredMethod("onItemLongClick", long.class);
		method.setAccessible(true);
		Object result = method.invoke(list, removedId);
		assertEquals(Boolean.FALSE, result);
		assertEquals(0, list.size());
	}

	@Test
	public void staleFocusCallbackIsIgnored() throws Exception {
		List list = implicitList("A", "B", "C");
		list.setSelectedIndex(1, true);
		long removedId = getItemUiId(list, 1);
		list.delete(1);
		int selectionAfterDelete = list.getSelectedIndex();
		Method method = List.class.getDeclaredMethod("onItemFocused", long.class);
		method.setAccessible(true);
		method.invoke(list, removedId);
		assertEquals("stale focus must not select a removed item", selectionAfterDelete, list.getSelectedIndex());
	}

	@Test
	public void focusCallbackSelectsNewItemForImplicitOnly() throws Exception {
		List implicit = implicitList("A", "B", "C");
		Method method = List.class.getDeclaredMethod("onItemFocused", long.class);
		method.setAccessible(true);
		method.invoke(implicit, getItemUiId(implicit, 2));
		assertEquals(2, implicit.getSelectedIndex());

		List exclusive = exclusiveList("A", "B", "C");
		method.invoke(exclusive, getItemUiId(exclusive, 2));
		assertEquals("focus must not drive EXCLUSIVE selection", 0, exclusive.getSelectedIndex());
	}

	@Test
	public void staleCallbackWithValidIndexRoutesToCorrectItem() throws Exception {
		List list = implicitList("A", "B", "C");
		list.setSelectedIndex(2, true);
		// Capture the uiId of the item currently at index 2.
		long itemId = getItemUiId(list, 2);
		// Remove item at index 0; the captured item now sits at index 1.
		list.delete(0);
		// A stale index-based callback would route index 2 to "C" and select the wrong item.
		Method method = List.class.getDeclaredMethod("onItemClick", long.class);
		method.setAccessible(true);
		method.invoke(list, itemId);
		assertEquals("callback must resolve by identity, not stale index", 1, list.getSelectedIndex());
		assertEquals("C", list.getString(1));
	}

	private static long getItemUiId(List list, int index) throws Exception {
		java.lang.reflect.Field itemsField = List.class.getDeclaredField("items");
		itemsField.setAccessible(true);
		@SuppressWarnings("unchecked")
		java.util.List<javax.microedition.lcdui.list.CompoundItem> items =
				(java.util.List<javax.microedition.lcdui.list.CompoundItem>) itemsField.get(list);
		return items.get(index).getUiId();
	}

	@Test
	public void sizeAndGetStringContract() {
		List list = implicitList("A", "B");
		assertEquals(2, list.size());
		assertEquals("A", list.getString(0));
		try {
			list.getString(2);
			assertTrue("out-of-bounds getString must throw", false);
		} catch (IndexOutOfBoundsException expected) {
			// contract
		}
	}
}
