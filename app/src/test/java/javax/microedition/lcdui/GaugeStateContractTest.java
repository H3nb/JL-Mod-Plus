/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class GaugeStateContractTest {
	@Test
	public void definiteValuesClampAndMaximumChangesAreConsistent() {
		Gauge gauge = new Gauge(null, false, 10, 8);
		gauge.setValue(-5);
		assertEquals(0, gauge.getValue());
		gauge.setValue(99);
		assertEquals(10, gauge.getValue());
		gauge.setValue(8);
		gauge.setMaxValue(3);
		assertEquals(3, gauge.getValue());
		gauge.setMaxValue(20);
		assertEquals(3, gauge.getValue());
	}

	@Test
	public void definiteAndIndefiniteTransitionsFollowMidp() {
		Gauge gauge = new Gauge(null, false, 10, 8);
		gauge.setMaxValue(Gauge.INDEFINITE);
		assertEquals(Gauge.CONTINUOUS_IDLE, gauge.getValue());
		gauge.setValue(Gauge.INCREMENTAL_UPDATING);
		gauge.setMaxValue(Gauge.INDEFINITE);
		assertEquals(Gauge.INCREMENTAL_UPDATING, gauge.getValue());
		gauge.setMaxValue(5);
		assertEquals(0, gauge.getValue());
		gauge.setMaxValue(Gauge.INDEFINITE);
		assertEquals(Gauge.CONTINUOUS_IDLE, gauge.getValue());
	}

	@Test
	public void invalidMaximumAndIndefiniteStateDoNotMutateGauge() {
		Gauge interactive = new Gauge(null, true, 1, 1);
		assertThrows(IllegalArgumentException.class, () -> interactive.setMaxValue(0));
		assertThrows(IllegalArgumentException.class, () -> interactive.setMaxValue(Gauge.INDEFINITE));
		assertEquals(1, interactive.getMaxValue());
		assertEquals(1, interactive.getValue());

		Gauge indefinite = new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_IDLE);
		for (int invalid : new int[]{-2, 4, Integer.MAX_VALUE}) {
			assertThrows(IllegalArgumentException.class, () -> indefinite.setValue(invalid));
			assertEquals(Gauge.CONTINUOUS_IDLE, indefinite.getValue());
		}
		assertThrows(IllegalArgumentException.class, () -> new Gauge(null, true, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new Gauge(null, false, 0, 0));
	}

	@Test
	public void definiteRangeTreatsIndefiniteConstantsAsOrdinaryValues() {
		Gauge gauge = new Gauge(null, true, 9, Gauge.INCREMENTAL_UPDATING);
		assertEquals(3, gauge.getValue());
		gauge.setValue(Gauge.INDEFINITE);
		assertEquals(0, gauge.getValue());
	}
}
