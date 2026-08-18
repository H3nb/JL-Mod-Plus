/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.crashes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MidletSessionTerminalClassifierTest {
	@Test
	public void completedSessionIsTerminal() {
		assertTrue(MidletSessionTerminalClassifier.isTerminal(
				MidletSessionJournal.Stage.COMPLETED,
				MidletSessionJournal.Outcome.USER_STOP,
				false));
	}

	@Test
	public void unexpectedFailureIsTerminalBeforeCompletedStage() {
		assertTrue(MidletSessionTerminalClassifier.isTerminal(
				MidletSessionJournal.Stage.RUNNING,
				MidletSessionJournal.Outcome.UNEXPECTED_FAILURE,
				false));
	}

	@Test
	public void confirmedGoneProcessMakesIncompleteSessionTerminal() {
		assertTrue(MidletSessionTerminalClassifier.isTerminal(
				MidletSessionJournal.Stage.PAUSED,
				MidletSessionJournal.Outcome.NONE,
				true));
	}

	@Test
	public void livePausedSessionIsNotTerminal() {
		assertFalse(MidletSessionTerminalClassifier.isTerminal(
				MidletSessionJournal.Stage.PAUSED,
				MidletSessionJournal.Outcome.NONE,
				false));
	}

	@Test
	public void intentionalOutcomeAloneIsNotEnoughWhileProcessMayContinue() {
		assertFalse(MidletSessionTerminalClassifier.isTerminal(
				MidletSessionJournal.Stage.STOPPING,
				MidletSessionJournal.Outcome.MIDLET_REQUEST,
				false));
	}
}
