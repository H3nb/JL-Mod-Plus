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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM characterization of the Item base class, StringItem, Gauge, and Spacer. */
public class ItemModelTest {
	@Test
	public void stringItemStoresTextAndAppearanceMode() {
		StringItem item = new StringItem("Label", "Text", Item.HYPERLINK);
		assertEquals("Label", item.getLabel());
		assertEquals("Text", item.getText());
		assertEquals(Item.HYPERLINK, item.getAppearanceMode());
		item.setText("Updated");
		assertEquals("Updated", item.getText());
		item.setLabel("New");
		assertEquals("New", item.getLabel());
	}

	@Test
	public void stringItemFontIsAlwaysAvailable() {
		StringItem item = new StringItem("Label", "Text");
		assertNotNull(item.getFont());
		item.setFont(null);
		assertNotNull(item.getFont());
	}

	@Test
	public void layoutRoundTrip() {
		StringItem item = new StringItem(null, "Text");
		assertEquals(Item.LAYOUT_DEFAULT, item.getLayout());
		item.setLayout(Item.LAYOUT_CENTER | Item.LAYOUT_VCENTER);
		assertEquals(Item.LAYOUT_CENTER | Item.LAYOUT_VCENTER, item.getLayout());
	}

	@Test
	public void preferredSizeRoundTripAndValidation() {
		StringItem item = new StringItem(null, "Text");
		assertEquals(0, item.getPreferredWidth());
		assertEquals(0, item.getPreferredHeight());
		item.setPreferredSize(42, 24);
		assertEquals(42, item.getPreferredWidth());
		assertEquals(24, item.getPreferredHeight());
		item.setPreferredSize(-1, -1);
		assertEquals(0, item.getPreferredWidth());
		try {
			item.setPreferredSize(-2, 10);
			assertTrue("size below -1 must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
	}

	@Test
	public void addCommandRejectsNullAndDuplicates() {
		StringItem item = new StringItem(null, "Text");
		try {
			item.addCommand(null);
			assertTrue("null command must be rejected", false);
		} catch (NullPointerException expected) {
			// contract
		}
		Command ok = new Command("OK", Command.OK, 0);
		item.addCommand(ok);
		item.addCommand(ok);
		assertEquals(1, item.commands.size());
	}

	@Test
	public void setDefaultCommandMovesToFrontAndRemoveClearsIt() {
		StringItem item = new StringItem(null, "Text");
		Command first = new Command("First", Command.OK, 0);
		Command second = new Command("Second", Command.OK, 0);
		item.addCommand(first);
		item.setDefaultCommand(second);
		assertSame(second, item.commands.get(0));
		item.removeCommand(second);
		assertEquals(1, item.commands.size());
		assertFalse(item.commands.contains(second));
	}

	@Test
	public void gaugeStoresValueAndMax() {
		Gauge gauge = new Gauge("G", false, 10, 3);
		assertEquals(3, gauge.getValue());
		assertEquals(10, gauge.getMaxValue());
		assertFalse(gauge.isInteractive());
		gauge.setValue(7);
		assertEquals(7, gauge.getValue());
		gauge.setMaxValue(20);
		assertEquals(20, gauge.getMaxValue());
	}

	@Test
	public void gaugeIndefiniteState() {
		Gauge gauge = new Gauge("G", false, 10, 5);
		gauge.setMaxValue(Gauge.INDEFINITE);
		assertEquals(Gauge.INDEFINITE, gauge.getMaxValue());
	}

	@Test
	public void gaugeInteractiveFlagRoundTrip() {
		Gauge gauge = new Gauge("G", true, 10, 0);
		assertTrue(gauge.isInteractive());
	}

	@Test
	public void spacerAcceptsMinimumSize() {
		Spacer spacer = new Spacer(5, 7);
		// smoke: setMinimumSize only stores values with no getter to observe
		spacer.setMinimumSize(3, 4);
	}

	@Test
	public void itemWithoutOwnerHasNoOwner() {
		StringItem item = new StringItem(null, "Text");
		assertFalse(item.hasOwner());
		assertNull(item.getOwner());
	}
}