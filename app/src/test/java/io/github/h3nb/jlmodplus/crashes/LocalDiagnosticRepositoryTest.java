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
	public void handledSessionFailureUsesStructuredSessionIdWithoutStackMarker() {
		assertTrue(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				javaEvidence(JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE, SESSION, EVENT)));
	}

	@Test
	public void modernProcessFatalIsIndependentEvenWithSameSessionId() {
		assertFalse(LocalDiagnosticRepository.shouldAttachToSession(
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
	public void legacyEvidenceRequiresExactWrapperEventAndBoundary() {
		assertTrue(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				legacySessionFailure(EVENT, "LIFECYCLE_START")));
		assertFalse(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				legacySessionFailure("323e4567-e89b-12d3-a456-426614174000",
						"LIFECYCLE_START")));
		assertFalse(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				legacySessionFailure(EVENT, "LIFECYCLE_PAUSE")));
		assertFalse(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				javaEvidence(JavaDiagnosticStore.Kind.LEGACY_FATAL, null, EVENT)));
	}

	@Test
	public void onlyControlledSigkillAttachesToHandledSessionFailure() {
		MidletSessionJournal.Snapshot session = session(SESSION, EVENT);
		assertTrue(LocalDiagnosticRepository.shouldAttachExitToSession(
				session, processExit(ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL)));
		assertFalse(LocalDiagnosticRepository.shouldAttachExitToSession(
				session, processExit(ProcessExitStore.REASON_CRASH, 0)));
		assertFalse(LocalDiagnosticRepository.shouldAttachExitToSession(
				session, processExit(ProcessExitStore.REASON_CRASH_NATIVE, ProcessExitStore.SIGNAL_SEGV)));
		assertFalse(LocalDiagnosticRepository.shouldAttachExitToSession(
				session, processExit(ProcessExitStore.REASON_ANR, 0)));
	}

	@Test
	public void processJavaExitRequiresExactPidSessionRoleAndJavaCrashReason() {
		JavaDiagnosticStore.Snapshot fatal =
				javaEvidence(JavaDiagnosticStore.Kind.FATAL_UNCAUGHT, SESSION, null);
		assertTrue(LocalDiagnosticRepository.shouldAttachExitToJava(
				fatal, processExit(ProcessExitStore.REASON_CRASH, 0)));
		assertFalse(LocalDiagnosticRepository.shouldAttachExitToJava(
				fatal, processExit(ProcessExitStore.REASON_CRASH_NATIVE, ProcessExitStore.SIGNAL_SEGV)));
		assertFalse(LocalDiagnosticRepository.shouldAttachExitToJava(
				fatal, processExit(ProcessExitStore.REASON_CRASH, 0, 124, SESSION)));
	}

	@Test
	public void migratedStandaloneJavaKeepsLegacyLogicalRecordId() {
		JavaDiagnosticStore.Snapshot migrated = javaEvidence(
				JavaDiagnosticStore.Kind.LEGACY_ACRA, null, null)
				.withLegacyRecordId("acra:old-report-id");

		assertTrue("acra:old-report-id".equals(
				LocalDiagnosticRepository.javaRecordId(migrated)));
	}

	@Test
	public void mismatchedNewSessionNeverCorrelatesByFatalKindAlone() {
		assertFalse(LocalDiagnosticRepository.shouldAttachToSession(
				session(SESSION, EVENT),
				javaEvidence(JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE,
						"323e4567-e89b-12d3-a456-426614174000", EVENT)));
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

	private static JavaDiagnosticStore.Snapshot legacySessionFailure(
			String eventId, String boundary) {
		JavaDiagnosticStore.ThrowableData wrapper = new JavaDiagnosticStore.ThrowableData(
				"javax.microedition.shell.MidletThread$SessionFailureException",
				"JL-Mod Plus session failure; eventId=" + eventId + "; boundary=" + boundary,
				List.of(new JavaDiagnosticStore.FrameData(
						"javax.microedition.shell.MidletThread", "run", "MidletThread.java", 42, false)));
		return new JavaDiagnosticStore.Snapshot(
				new File("legacy-test.properties"),
				JavaDiagnosticStore.Kind.LEGACY_FATAL,
				2L,
				"io.github.h3nb.jlmodplus:midlet",
				"midlet",
				123,
				"MidletMain",
				1,
				5,
				null,
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
				List.of(wrapper),
				0,
				eventId,
				boundary,
				null);
	}

	private static ProcessExitStore.Snapshot processExit(int reason, int status) {
		return processExit(reason, status, 123, SESSION);
	}

	private static ProcessExitStore.Snapshot processExit(
			int reason, int status, int pid, String sessionId) {
		return new ProcessExitStore.Snapshot(
				new File("exit.properties"),
				null,
				"1-123-" + reason + "-" + status,
				ProcessExitStore.SOURCE_APPLICATION_EXIT_INFO,
				3L,
				"io.github.h3nb.jlmodplus:midlet",
				"midlet",
				pid,
				reason,
				status,
				100,
				0,
				0,
				null,
				true,
				1,
				36,
				"16",
				sessionId,
				"POCO",
				"F7",
				"arm64-v8a",
				null,
				0,
				false,
				-1,
				-1,
				-1,
				null,
				null);
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
