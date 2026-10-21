/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class CommandArgumentValidationTest {
	@Test
	public void acceptsAllCommandTypesAndValidLabels() {
		for (int type = Command.SCREEN; type <= Command.ITEM; type++) {
			Command command = new Command("", null, type, -1);
			assertEquals("", command.getLabel());
			assertNull(command.getLongLabel());
			assertEquals(type, command.getCommandType());
			assertEquals(-1, command.getPriority());
		}
		assertEquals("Details", new Command("Short", "Details", Command.OK, 5).getLongLabel());
		assertEquals("Short", new Command("Short", Command.OK, 5).getLabel());
	}

	@Test
	public void rejectsNullShortLabelInBothConstructors() {
		assertThrows(NullPointerException.class, () -> new Command(null, Command.OK, 1));
		assertThrows(NullPointerException.class,
				() -> new Command(null, "Long", Command.OK, 1));
	}

	@Test
	public void rejectsTypesOutsideMidpRangeWithoutRestrictingPriority() {
		for (int type : new int[]{-1, 0, Command.ITEM + 1, Integer.MAX_VALUE}) {
			final int invalidType = type;
			assertThrows(IllegalArgumentException.class,
					() -> new Command("Run", invalidType, 0));
			assertThrows(IllegalArgumentException.class,
					() -> new Command("Run", null, invalidType, Integer.MIN_VALUE));
		}
	}
}
