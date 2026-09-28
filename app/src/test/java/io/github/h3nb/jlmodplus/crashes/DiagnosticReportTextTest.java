/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;

import org.junit.Test;

import java.io.File;
import java.util.List;

public class DiagnosticReportTextTest {
	@Test
	public void localReportUsesOneFailureHierarchyAndControlledRuntimeTermination() {
		IncidentSummary incident = realIncident();
		String result = DiagnosticReportText.build(
				incident,
				"java.lang.NoClassDefFoundError\nCaused by: java.lang.ClassNotFoundException");

		int primary = result.indexOf("Primary Failure");
		int underlying = result.indexOf("Underlying Cause");
		int termination = result.indexOf("Runtime Termination");
		assertTrue(primary >= 0);
		assertTrue(underlying > primary);
		assertTrue(termination > underlying);
		assertTrue(result.contains("NoClassDefFoundError"));
		assertTrue(result.contains("ClassNotFoundException"));
		assertTrue(result.contains("Android 16 (SDK 36)"));
		assertTrue(result.contains(
				"Termination: JL-Mod Plus terminated the isolated MIDlet process"));
		assertFalse(result.contains("Associated Process Termination"));
		assertFalse(result.contains("OS limitation:"));
		assertFalse(result.contains("Failure: SIGKILL"));
		assertFalse(result.contains("Cause: unknown"));
	}

	@Test
	public void unexplainedSigkillLimitationAppearsOnlyInEvidenceLimitations() {
		IncidentSummary base = realIncident();
		ProcessExitStore.Snapshot exit = new ProcessExitStore.Snapshot(
				new File("exit-unexplained"), null, "key-unexplained",
				ProcessExitStore.SOURCE_APPLICATION_EXIT_INFO,
				1100L, "pkg:midlet", "midlet", 123,
				ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL,
				ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
				100, 200, null, true, 1, 36, "16",
				base.sessionId, "POCO", "F7", "arm64-v8a",
				null, 0, false, -1, -1, -1, null, null);
		JavaDiagnosticStore.ThrowableData primary = new JavaDiagnosticStore.ThrowableData(
				"java.lang.IllegalStateException", "host",
				List.of(new JavaDiagnosticStore.FrameData(
						"io.github.h3nb.jlmodplus.Host", "run", "Host.java", 1, false)));
		JavaDiagnosticStore.Snapshot java = new JavaDiagnosticStore.Snapshot(
				new File("host.properties"), JavaDiagnosticStore.Kind.FATAL_UNCAUGHT,
				1000L, "pkg:midlet", "midlet", 123, "Host", 1, 5,
				base.sessionId, "Game", "1.0", "game.Main", "abc", "1.0", "16", 36,
				"POCO", "F7", "arm64-v8a", null, "stack",
				List.of(primary), 0, null, null, null);
		IncidentSummary incident = IncidentInterpreter.interpret(null, java, exit);
		String result = DiagnosticReportText.build(incident, "stack");
		String marker = "Low-memory kills are separately reportable on this device";

		assertTrue(result.contains("Associated Process Termination"));
		assertTrue(result.contains("Evidence Limitations"));
		assertEquals(1, occurrences(result, marker));
		assertFalse(result.contains("OS limitation:"));
	}

	@Test
	public void nativeSummaryAppendsAsTechnicalEvidence() {
		String report = "JL-Mod Plus Diagnostic Report\n\nSummary\nNative crash";
		String nativeSummary = "Native crash details\nSignal: SIGSEGV (11)";

		String result = DiagnosticReportText.withNativeSummary(report, nativeSummary);

		assertTrue(result.startsWith(report));
		assertTrue(result.contains("Native Crash Evidence"));
		assertTrue(result.endsWith(nativeSummary));
	}

	private static int occurrences(String text, String marker) {
		int count = 0;
		int index = 0;
		while ((index = text.indexOf(marker, index)) >= 0) {
			count++;
			index += marker.length();
		}
		return count;
	}

	private static IncidentSummary realIncident() {
		JavaDiagnosticStore.ThrowableData primary = new JavaDiagnosticStore.ThrowableData(
				"java.lang.NoClassDefFoundError", "Failed resolution",
				List.of(new JavaDiagnosticStore.FrameData(
						"game.Main", "startApp", "Main.java", 42, false)));
		JavaDiagnosticStore.ThrowableData underlying = new JavaDiagnosticStore.ThrowableData(
				"java.lang.ClassNotFoundException", "Missing class",
				List.of(new JavaDiagnosticStore.FrameData(
						"dalvik.system.BaseDexClassLoader", "findClass",
						"BaseDexClassLoader.java", 259, false)));
		JavaDiagnosticStore.Snapshot java = new JavaDiagnosticStore.Snapshot(
				new File("java.properties"), JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE,
				1000L, "pkg:midlet", "midlet", 123, "MidletMain", 1, 5,
				"123e4567-e89b-12d3-a456-426614174000",
				"Game", "1.0", "game.Main", "abc", "1.0", "16", 36,
				"POCO", "F7", "arm64-v8a", null, "stack",
				List.of(primary, underlying), 0, null, null, null);
		MidletSessionJournal.Snapshot session = new MidletSessionJournal.Snapshot(
				2, "123e4567-e89b-12d3-a456-426614174000", "pkg:midlet", 123,
				900L, 1L, 1000L, 2L, MidletSessionJournal.Stage.STARTING,
				MidletSessionJournal.Outcome.UNEXPECTED_FAILURE,
				"223e4567-e89b-12d3-a456-426614174000",
				MidletSessionJournal.FailureBoundary.LIFECYCLE_START,
				"Game", "Vendor", "1.0", "game.Main", "1", "abc");
		ProcessExitStore.Snapshot exit = new ProcessExitStore.Snapshot(
				new File("exit"), null, "key",
				ProcessExitStore.SOURCE_APPLICATION_EXIT_INFO,
				1100L, "pkg:midlet", "midlet", 123,
				ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL,
				ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
				100, 200, null, true, 1, 36, "16",
				session.sessionId, "POCO", "F7", "arm64-v8a",
				null, 0, false, -1, -1, -1, null, null);
		return IncidentInterpreter.interpret(session, java, exit);
	}
}
