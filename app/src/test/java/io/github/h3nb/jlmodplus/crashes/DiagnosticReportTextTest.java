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

import android.app.ActivityManager;
import android.system.OsConstants;

import org.junit.Test;

import java.io.File;
import java.util.List;

public class DiagnosticReportTextTest {
	@Test
	public void localReportUsesOneFailureHierarchyAndAssociatedTermination() {
		IncidentSummary incident = realIncident();
		String result = DiagnosticReportText.build(
				incident,
				"java.lang.NoClassDefFoundError\nCaused by: java.lang.ClassNotFoundException");

		int primary = result.indexOf("Primary Failure");
		int underlying = result.indexOf("Underlying Cause");
		int termination = result.indexOf("Associated Process Termination");
		assertTrue(primary >= 0);
		assertTrue(underlying > primary);
		assertTrue(termination > underlying);
		assertTrue(result.contains("NoClassDefFoundError"));
		assertTrue(result.contains("ClassNotFoundException"));
		assertTrue(result.contains("Android 16 (SDK 36)"));
		assertTrue(result.contains("Termination: Android recorded SIGKILL"));
		assertFalse(result.contains("Failure: SIGKILL"));
		assertFalse(result.contains("Cause: unknown"));
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
				new File("java.properties"), JavaDiagnosticStore.Kind.FATAL_UNCAUGHT,
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
				new File("exit"), null, "key", 1100L, "pkg:midlet", "midlet", 123,
				ProcessExitStore.REASON_SIGNALED, OsConstants.SIGKILL,
				ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
				100, 200, null, true, 1, 36, "16",
				session.sessionId, "POCO", "F7", "arm64-v8a",
				null, 0, false, -1, -1, -1, null, null);
		return IncidentInterpreter.interpret(session, java, exit);
	}
}
