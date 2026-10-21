/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChoiceGroupSelectionContractTest {
	@Test
	public void popupKeepsSelectionByElementAndPicksNeighborWhenDeleted() {
		ChoiceGroup choice = new ChoiceGroup(null, Choice.POPUP,
				new String[]{"A", "B", "C", "D"}, null);
		choice.setSelectedIndex(2, true);
		choice.delete(0);
		assertEquals("C", choice.getString(choice.getSelectedIndex()));
		choice.delete(choice.getSelectedIndex());
		assertEquals("D", choice.getString(choice.getSelectedIndex()));
		choice.delete(1);
		assertEquals("B", choice.getString(choice.getSelectedIndex()));
		choice.delete(0);
		assertEquals(-1, choice.getSelectedIndex());
	}

	@Test
	public void emptySelectionFlagsAreLegalAndSingleChoiceAllFalsePicksFirst() {
		ChoiceGroup choice = new ChoiceGroup(null, Choice.POPUP);
		choice.setSelectedFlags(new boolean[0]);
		assertEquals(-1, choice.getSelectedIndex());
		choice.append("A", null);
		choice.append("B", null);
		choice.setSelectedFlags(new boolean[]{false, false});
		assertEquals(0, choice.getSelectedIndex());
		choice.setSelectedFlags(new boolean[]{true, true});
		assertEquals(0, choice.getSelectedIndex());
		assertThrows(IllegalArgumentException.class,
				() -> choice.setSelectedFlags(new boolean[1]));
		assertThrows(NullPointerException.class,
				() -> choice.setSelectedFlags(null));
	}

	@Test
	public void deselectingSingleChoiceDoesNotBreakItsInvariant() {
		ChoiceGroup choice = new ChoiceGroup(null, Choice.POPUP,
				new String[]{"A", "B"}, null);
		choice.setSelectedIndex(0, false);
		assertTrue(choice.isSelected(0));
		assertFalse(choice.isSelected(1));
		choice.insert(0, "Before", null);
		assertEquals("A", choice.getString(choice.getSelectedIndex()));
	}
}
