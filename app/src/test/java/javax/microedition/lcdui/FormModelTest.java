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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM characterization of the Form item container and ownership contract. */
public class FormModelTest {
	@Test
	public void emptyConstructorCreatesEmptyForm() {
		Form form = new Form("Title");
		assertEquals(0, form.size());
	}

	@Test
	public void nullElementArrayCreatesEmptyForm() {
		Form form = new Form("Title", null);
		assertEquals(0, form.size());
	}

	@Test
	public void constructorRejectsNullItem() {
		try {
			new Form("Title", new Item[] {new StringItem(null, "a"), null});
			assertTrue("null item must be rejected", false);
		} catch (NullPointerException expected) {
			// contract
		}
	}

	@Test
	public void constructorRejectsAlreadyOwnedItem() {
		StringItem first = new StringItem(null, "a");
		Form owner = new Form("T", new Item[] {first});
		try {
			new Form("T", new Item[] {first});
			assertTrue("already owned item must be rejected", false);
		} catch (IllegalStateException expected) {
			// contract
		}
		assertSame(owner, first.getOwner());
	}

	@Test
	public void appendTextCreatesStringItemAndOwnsIt() {
		Form form = new Form("T");
		int index = form.append("text");
		assertEquals(0, index);
		assertEquals(1, form.size());
		StringItem item = (StringItem) form.get(0);
		assertEquals("text", item.getText());
		assertSame(form, item.getOwner());
	}

	@Test
	public void appendRejectsOwnedItem() {
		Form form = new Form("T");
		StringItem item = new StringItem(null, "a");
		form.append(item);
		try {
			form.append(item);
			assertTrue("double append must be rejected", false);
		} catch (IllegalStateException expected) {
			// contract
		}
	}

	@Test
	public void insertShiftsIndexes() {
		Form form = new Form("T");
		form.append("a");
		form.append("c");
		form.insert(1, new StringItem(null, "b"));
		assertEquals(3, form.size());
		assertEquals("b", ((StringItem) form.get(1)).getText());
	}

	@Test
	public void setReplacesAndReleasesPreviousOwner() {
		Form form = new Form("T");
		StringItem oldItem = new StringItem(null, "old");
		form.append(oldItem);
		StringItem newItem = new StringItem(null, "new");
		form.set(0, newItem);
		assertSame(newItem, form.get(0));
		assertSame(form, newItem.getOwner());
		assertFalse("replaced item must be released", oldItem.hasOwner());
	}

	@Test
	public void deleteRemovesAndReleasesItem() {
		Form form = new Form("T");
		StringItem item = new StringItem(null, "a");
		form.append(item);
		form.delete(0);
		assertEquals(0, form.size());
		assertFalse("deleted item must be released", item.hasOwner());
	}

	@Test
	public void deleteAllReleasesAllItems() {
		Form form = new Form("T");
		StringItem a = new StringItem(null, "a");
		StringItem b = new StringItem(null, "b");
		form.append(a);
		form.append(b);
		form.deleteAll();
		assertEquals(0, form.size());
		assertFalse(a.hasOwner());
		assertFalse(b.hasOwner());
	}

	@Test
	public void notifyStateChangedThrowsOutsideForm() {
		StringItem item = new StringItem(null, "a");
		try {
			item.notifyStateChanged();
			assertTrue("state change without Form owner must throw", false);
		} catch (IllegalStateException expected) {
			// contract
		}
	}

	@Test
	public void postStateChangedIsSilentOutsideForm() {
		// smoke: package-private hook must be a silent no-op without a Form owner
		StringItem item = new StringItem(null, "a");
		item.postStateChanged();
	}

	@Test
	public void setItemStateListenerAcceptsNullAndListener() {
		Form form = new Form("T");
		// smoke: the listener is stored in a private field with no getter
		form.setItemStateListener(null);
		form.setItemStateListener(item -> {
		});
	}
}