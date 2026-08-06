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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM characterization of the Alert timeout, command, and indicator contract. */
public class AlertModelTest {
	@Test
	public void constructorStoresState() {
		Alert alert = new Alert("Title", "Text", null, AlertType.INFO);
		assertEquals("Text", alert.getString());
		assertNull(alert.getImage());
		assertSame(AlertType.INFO, alert.getType());
		assertEquals(Alert.FOREVER, alert.getTimeout());
	}

	@Test
	public void defaultTimeoutIsForever() {
		assertEquals(Alert.FOREVER, new Alert("T").getDefaultTimeout());
	}

	@Test
	public void finiteTimeoutRequiresPositiveTimeoutAndNoCommands() {
		Alert alert = new Alert("T");
		alert.setTimeout(1000);
		assertTrue("positive timeout without commands must be finite", alert.finiteTimeout());
		alert.setTimeout(0);
		assertFalse(alert.finiteTimeout());
		alert.setTimeout(Alert.FOREVER);
		assertFalse(alert.finiteTimeout());
		alert.setTimeout(1000);
		alert.addCommand(new Command("OK", Command.OK, 0));
		assertFalse("commands make the alert modal", alert.finiteTimeout());
	}

	@Test
	public void dismissCommandIsIgnoredAndNotCounted() {
		Alert alert = new Alert("T");
		alert.setTimeout(1000);
		alert.addCommand(Alert.DISMISS_COMMAND);
		assertTrue("DISMISS_COMMAND must be ignored", alert.finiteTimeout());
	}

	@Test
	public void addCommandRejectsNullAndDuplicates() {
		Alert alert = new Alert("T");
		alert.setTimeout(1000);
		try {
			alert.addCommand(null);
			assertTrue("null command must be rejected", false);
		} catch (NullPointerException expected) {
			// contract
		}
		Command ok = new Command("OK", Command.OK, 0);
		alert.addCommand(ok);
		alert.addCommand(ok);
		alert.setTimeout(1000);
		assertFalse("duplicate add must not change command count", alert.finiteTimeout());
		alert.removeCommand(ok);
		assertTrue(alert.finiteTimeout());
	}

	@Test
	public void setStringAndImageUpdateState() {
		Alert alert = new Alert("T", "old", null, AlertType.WARNING);
		alert.setString("new");
		assertEquals("new", alert.getString());
		alert.setImage(null);
		assertNull(alert.getImage());
		alert.setType(AlertType.ERROR);
		assertSame(AlertType.ERROR, alert.getType());
	}

	@Test
	public void setIndicatorRejectsNonCompliantGauge() {
		Alert alert = new Alert("T");
		try {
			alert.setIndicator(new Gauge("labeled", false, 5, 0));
			assertTrue("indicator with a label must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
		try {
			alert.setIndicator(new Gauge(null, true, 5, 0));
			assertTrue("interactive indicator must be rejected", false);
		} catch (IllegalArgumentException expected) {
			// contract
		}
	}

	@Test
	public void setIndicatorAssignsAndReleasesOwner() {
		Alert alert = new Alert("T");
		Gauge gauge = new Gauge(null, false, 5, 0);
		alert.setIndicator(gauge);
		assertSame(gauge, alert.getIndicator());
		assertSame(alert, gauge.getOwner());
		alert.setIndicator(null);
		assertNull(alert.getIndicator());
		assertFalse("released indicator must not keep the owner", gauge.hasOwner());
	}

	@Test
	public void alertOwnedItemRestrictionsApply() {
		Alert alert = new Alert("T");
		Gauge gauge = new Gauge(null, false, 5, 0);
		alert.setIndicator(gauge);
		try {
			gauge.setLabel("late label");
			assertTrue("label change on alert-owned item must throw", false);
		} catch (IllegalStateException expected) {
			// contract
		}
		try {
			gauge.setPreferredSize(10, 10);
			assertTrue("size change on alert-owned item must throw", false);
		} catch (IllegalStateException expected) {
			// contract
		}
	}
}