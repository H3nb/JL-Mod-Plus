/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.widget.ProgressBar;
import android.widget.Spinner;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MidpStageBDeviceTest {
	@Test
	public void gaugeAppliesStateAndEveryIncrementalPulseOnAndroidProgressBar() {
		Gauge gauge = new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_IDLE);
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			gauge.progressBar = new ProgressBar(
					InstrumentationRegistry.getInstrumentation().getTargetContext(),
					null, android.R.attr.progressBarStyleHorizontal);
		});
		gauge.setValue(Gauge.CONTINUOUS_RUNNING);
		idle();
		assertTrue(gauge.progressBar.isIndeterminate());

		gauge.setValue(Gauge.INCREMENTAL_IDLE);
		idle();
		assertFalse(gauge.progressBar.isIndeterminate());
		assertEquals(0, gauge.progressBar.getProgress());

		gauge.setValue(Gauge.INCREMENTAL_UPDATING);
		idle();
		int first = gauge.progressBar.getProgress();
		gauge.setValue(Gauge.INCREMENTAL_UPDATING);
		idle();
		assertEquals(first % 4 + 1, gauge.progressBar.getProgress());

		gauge.setMaxValue(8);
		idle();
		assertEquals(0, gauge.getValue());
		assertEquals(0, gauge.progressBar.getProgress());
		assertEquals(8, gauge.progressBar.getMax());

		gauge.clearItemContentView();
		gauge.setValue(5);
		idle();
		assertEquals(5, gauge.getValue());
	}

	@Test
	public void selectionSurvivesAndroidAdapterChangesAndPopupRebind() {
		ChoiceGroup popup = new ChoiceGroup(null, Choice.POPUP,
				new String[]{"A", "B", "C"}, null);
		final Spinner[] view = new Spinner[1];
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
				view[0] = (Spinner) popup.getItemContentView());
		assertNotNull(view[0]);

		popup.setSelectedIndex(2, true);
		idle();
		assertEquals(2, popup.getSelectedIndex());
		popup.insert(0, "Before", null);
		idle();
		assertEquals("C", popup.getString(popup.getSelectedIndex()));
		assertEquals(popup.getSelectedIndex(), view[0].getSelectedItemPosition());

		popup.delete(popup.getSelectedIndex());
		idle();
		assertEquals("B", popup.getString(popup.getSelectedIndex()));
		assertEquals(popup.getSelectedIndex(), view[0].getSelectedItemPosition());

		popup.clearItemContentView();
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
				view[0] = (Spinner) popup.getItemContentView());
		idle();
		assertEquals(popup.getSelectedIndex(), view[0].getSelectedItemPosition());
	}

	@Test
	public void listRetainsSelectionAndNonImplicitSelectCommandIsNoOp() {
		List implicit = new List("List", Choice.IMPLICIT, new String[]{"A", "B", "C"}, null);
		implicit.setSelectedIndex(2, true);
		implicit.insert(0, "Before", null);
		assertEquals("C", implicit.getString(implicit.getSelectedIndex()));
		implicit.delete(implicit.getSelectedIndex());
		assertEquals("B", implicit.getString(implicit.getSelectedIndex()));
		implicit.deleteAll();
		implicit.setSelectedFlags(new boolean[0]);
		assertEquals(-1, implicit.getSelectedIndex());

		List exclusive = new List("Exclusive", Choice.EXCLUSIVE,
				new String[]{"Only"}, null);
		Command custom = new Command("Open", Command.ITEM, 1);
		exclusive.setSelectCommand(custom);
		assertFalse(exclusive.commands.contains(custom));
	}

	@Test
	public void listSelectCommandRoleAndOrdinaryMembershipRemainIndependent() {
		CapturingList list = new CapturingList();
		list.append("Item", null);
		Command a = new Command("Open", Command.ITEM, 1);
		Command b = new Command("Open", Command.ITEM, 2);
		list.setSelectCommand(a);
		list.setSelectCommand(b);
		assertTrue(list.commands.contains(a));
		assertTrue(list.commands.contains(b));
		list.onItemClick(null, null, 0, 0);
		assertEquals(b, list.lastCommand);
		assertEquals(1, list.calls);

		list.setSelectCommand(null);
		list.onItemClick(null, null, 0, 0);
		assertEquals(1, list.calls);
		list.setSelectCommand(List.SELECT_COMMAND);
		list.onItemClick(null, null, 0, 0);
		assertEquals(List.SELECT_COMMAND, list.lastCommand);
		assertEquals(2, list.calls);

		list.setSelectCommand(b);
		list.removeCommand(b);
		list.onItemClick(null, null, 0, 0);
		assertEquals(2, list.calls);
		assertFalse(list.commands.contains(b));
		assertTrue(list.commands.contains(a));
	}

	private static void idle() {
		InstrumentationRegistry.getInstrumentation().waitForIdleSync();
	}

	private static final class CapturingList extends List {
		Command lastCommand;
		int calls;

		CapturingList() {
			super("Commands", Choice.IMPLICIT);
		}

		@Override
		public void fireCommandAction(Command command) {
			lastCommand = command;
			calls++;
		}
	}
}
