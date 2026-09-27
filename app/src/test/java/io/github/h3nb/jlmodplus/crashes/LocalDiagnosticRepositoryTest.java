/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;

public class LocalDiagnosticRepositoryTest {
	private static final String SESSION = "123e4567-e89b-12d3-a456-426614174000";
	private static final String EVENT = "223e4567-e89b-12d3-a456-426614174000";

	@Test
	public void newFatalUsesStructuredSessionIdWithoutStackMarker() {
		assertTrue(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				javaEvidence(JavaDiagnosticStore.Kind.FATAL_UNCAUGHT, SESSION, null)));
	}

	@Test
	public void caughtReportWithSameSessionIsNotAbsorbedIntoFatalSession() {
		assertFalse(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				javaEvidence(JavaDiagnosticStore.Kind.CAUGHT_INSTALLER, SESSION, null)));
	}

	@Test
	public void legacyEvidenceCanUseExactEventMarkerAsCompatibilityKey() {
		assertTrue(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				javaEvidence(JavaDiagnosticStore.Kind.LEGACY_FATAL, null, EVENT)));
		assertFalse(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				javaEvidence(JavaDiagnosticStore.Kind.LEGACY_FATAL, null,
						"323e4567-e89b-12d3-a456-426614174000")));
	}

	@Test
	public void mismatchedNewSessionNeverCorrelatesByFatalKindAlone() {
		assertFalse(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				javaEvidence(JavaDiagnosticStore.Kind.FATAL_UNCAUGHT,
						"323e4567-e89b-12d3-a456-426614174000", null)));
	}

	private static MidletSessionJournal.Snapshot session(String sessionId, String eventId) {
		return new MidletSessionJournal.Snapshot(
				2,
				sessionId,
				"io.github.h3nb.jlmodplus:midlet",
				123,
				1L,
				1L,
				2L,
				2L,
				MidletSessionJournal.Stage.STARTING,
				MidletSessionJournal.Outcome.UNEXPECTED_FAILURE,
				eventId,
				MidletSessionJournal.FailureBoundary.LIFECYCLE_START,
				"Game",
				"Vendor",
				"1.0",
				"game.Main",
				"1",
				"abc123");
	}

	private static JavaDiagnosticStore.Snapshot javaEvidence(
			JavaDiagnosticStore.Kind kind, String sessionId, String legacyEventId) {
		JavaDiagnosticStore.ThrowableData failure = new JavaDiagnosticStore.ThrowableData(
				"java.lang.IllegalStateException",
				"boom",
				List.of(new JavaDiagnosticStore.FrameData(
						"game.Main", "startApp", "Main.java", 42, false)));
		return new JavaDiagnosticStore.Snapshot(
				new File("java-test.properties"),
				kind,
				2L,
				"io.github.h3nb.jlmodplus:midlet",
				"midlet",
				123,
				"MidletMain",
				1,
				5,
				sessionId,
				"Game",
				"1.0",
				"game.Main",
				"abc123",
				"1.0",
				"16",
				36,
				"POCO",
				"F7",
				"arm64-v8a",
				null,
				"stack",
				List.of(failure),
				0,
				legacyEventId,
				legacyEventId == null ? null : "LIFECYCLE_START",
				null);
	}
}
