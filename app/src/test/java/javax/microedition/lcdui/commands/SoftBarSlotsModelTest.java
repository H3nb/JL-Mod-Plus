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

package javax.microedition.lcdui.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.microedition.lcdui.Command;

/** JVM characterization of the soft bar slot layout (no View is created in these tests). */
public class SoftBarSlotsModelTest {
	private static List<Command> commands(int count) {
		List<Command> commands = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			commands.add(new Command("C" + i, Command.ITEM, 1));
		}
		return commands;
	}

	@Test
	public void zeroCommandsProduceNoSlots() {
		List<SoftBarSlot> slots = ScreenSoftBarComposeViewKt.buildSoftBarSlots(Collections.emptyList());
		assertTrue(slots.isEmpty());
	}

	@Test
	public void singleCommandProducesOneItemSlotWithoutMenu() {
		List<SoftBarSlot> slots = ScreenSoftBarComposeViewKt.buildSoftBarSlots(commands(1));
		assertEquals(3, slots.size());
		assertTrue(slots.get(0) instanceof SoftBarSlot.Item);
		assertTrue(slots.get(1) instanceof SoftBarSlot.Empty);
		assertTrue(slots.get(2) instanceof SoftBarSlot.Empty);
	}

	@Test
	public void twoCommandsProduceTwoItemSlotsWithoutMenu() {
		List<SoftBarSlot> slots = ScreenSoftBarComposeViewKt.buildSoftBarSlots(commands(2));
		assertEquals(3, slots.size());
		assertTrue(slots.get(0) instanceof SoftBarSlot.Item);
		assertTrue(slots.get(1) instanceof SoftBarSlot.Empty);
		assertTrue(slots.get(2) instanceof SoftBarSlot.Item);
	}

	@Test
	public void threeCommandsProduceThreeItemSlotsWithoutMenu() {
		List<SoftBarSlot> slots = ScreenSoftBarComposeViewKt.buildSoftBarSlots(commands(3));
		assertEquals(3, slots.size());
		for (SoftBarSlot slot : slots) {
			assertTrue(slot instanceof SoftBarSlot.Item);
		}
	}

	@Test
	public void fourCommandsProduceTwoItemSlotsAndMenu() {
		List<SoftBarSlot> slots = ScreenSoftBarComposeViewKt.buildSoftBarSlots(commands(4));
		assertEquals(3, slots.size());
		assertTrue(slots.get(0) instanceof SoftBarSlot.Item);
		assertTrue(slots.get(1) instanceof SoftBarSlot.Item);
		assertTrue(slots.get(2) instanceof SoftBarSlot.Menu);
	}

	@Test
	public void fiveCommandsProduceTwoItemSlotsAndMenu() {
		List<SoftBarSlot> slots = ScreenSoftBarComposeViewKt.buildSoftBarSlots(commands(5));
		assertEquals(3, slots.size());
		assertTrue(slots.get(0) instanceof SoftBarSlot.Item);
		assertTrue(slots.get(1) instanceof SoftBarSlot.Item);
		assertTrue(slots.get(2) instanceof SoftBarSlot.Menu);
	}

	@Test
	public void menuSlotNeverAppearsForUpToThreeCommands() {
		for (int count = 0; count <= 3; count++) {
			List<SoftBarSlot> slots = ScreenSoftBarComposeViewKt.buildSoftBarSlots(commands(count));
			for (SoftBarSlot slot : slots) {
				assertFalse(slot instanceof SoftBarSlot.Menu);
			}
		}
	}
}
