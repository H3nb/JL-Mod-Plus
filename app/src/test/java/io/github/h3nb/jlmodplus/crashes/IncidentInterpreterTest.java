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

public class IncidentInterpreterTest {
	private static final String SESSION = "123e4567-e89b-12d3-a456-426614174000";
	private static final String EVENT = "223e4567-e89b-12d3-a456-426614174000";

	@Test
	public void realStartAppShapeKeepsPrimaryCauseAndControlledTerminationDistinct() {
		List<JavaDiagnosticStore.ThrowableData> chain = List.of(
				throwable("java.lang.NoClassDefFoundError",
						"Failed resolution of Lcom/skt/m/AudioSystem;",
						"GloftOTSP", "startApp"),
				throwable("java.lang.ClassNotFoundException",
						"Didn't find class \"com.skt.m.AudioSystem\"",
						"dalvik.system.BaseDexClassLoader", "findClass"));
		JavaDiagnosticStore.Snapshot java = javaEvidence(
				JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE, chain, 0, "GloftOTSP");
		ProcessExitStore.Snapshot exit = exit(
				ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL, true, 36);
		IncidentSummary incident = IncidentInterpreter.interpret(
				session(MidletSessionJournal.FailureBoundary.LIFECYCLE_START), java, exit);

		assertEquals(IncidentSummary.Category.MIDLET_LIFECYCLE, incident.category);
		assertEquals("startApp()", incident.operation);
		assertEquals("NoClassDefFoundError", incident.primaryFailure.simpleType());
		assertEquals("ClassNotFoundException", incident.underlyingCause.simpleType());
		assertEquals(IncidentSummary.FailureOrigin.MIDLET, incident.failureOrigin);
		assertEquals("Android 16 (SDK 36)", incident.androidLabel());
		assertTrue(incident.associatedProcessExit.controlledByJlMod);
		assertTrue(incident.associatedProcessExit.summary
				.contains("terminated the isolated MIDlet process"));
		assertTrue(incident.limitations.isEmpty());
		assertTrue(DiagnosticBundleFormat.incidentJson(incident)
				.contains("\"controlledByJlMod\": true"));
	}
	@Test
	public void obfuscatedLifecycleFrameCannotOverrideStructuredDestroyOperation() {
		JavaDiagnosticStore.ThrowableData failure = new JavaDiagnosticStore.ThrowableData(
				"java.lang.NullPointerException",
				"Attempt to get length of null array",
				List.of(
						new JavaDiagnosticStore.FrameData("a", "a", "SourceFile", 12, false),
						new JavaDiagnosticStore.FrameData("a", "a", "SourceFile", 34, false),
						new JavaDiagnosticStore.FrameData(
								"GloftOTSP", "destroyApp", "SourceFile", 42, false)));
		IncidentSummary incident = IncidentInterpreter.interpret(
				session(MidletSessionJournal.FailureBoundary.LIFECYCLE_DESTROY),
				javaEvidence(List.of(failure), 0, "GloftOTSP"),
				null);

		String description = GitHubDiagnosticIssue.description(incident, null);
		assertEquals("destroyApp()", incident.operation);
		assertTrue(description.contains("destroyApp() callback"));
		assertFalse(description.contains("a.a()"));
	}

	@Test
	public void provenGuestWorkerFallbackHandlesObfuscationButHostFrameStillWins() {
		JavaDiagnosticStore.Snapshot guest = javaEvidence(
				JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE,
				List.of(throwable("java.lang.NullPointerException", "boom", "bz", "a")),
				0,
				"game.Main",
				MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD);
		IncidentSummary guestIncident = IncidentInterpreter.interpret(null, guest, null);

		assertEquals(IncidentSummary.Category.MIDLET_CRASH, guestIncident.category);
		assertEquals(IncidentSummary.FailureOrigin.MIDLET, guestIncident.failureOrigin);

		JavaDiagnosticStore.Snapshot host = javaEvidence(
				JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE,
				List.of(throwable("java.lang.IllegalStateException", "host",
						"javax.microedition.lcdui.event.EventQueue", "run")),
				0,
				"game.Main",
				MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD);
		IncidentSummary hostIncident = IncidentInterpreter.interpret(null, host, null);

		assertEquals(IncidentSummary.Category.JL_MOD_PLUS, hostIncident.category);
		assertEquals(IncidentSummary.FailureOrigin.JL_MOD_PLUS, hostIncident.failureOrigin);
	}

	@Test
	public void authoritativeNativeAndAnrEvidencePrecedeNonLifecycleJavaCategory() {
		JavaDiagnosticStore.Snapshot java = javaEvidence(
				List.of(throwable("java.lang.IllegalStateException", "boom", "game.Main", "run")),
				0,
				"game.Main");

		assertEquals(IncidentSummary.Category.NATIVE_CRASH,
				IncidentInterpreter.interpret(
						null, java, exit(ProcessExitStore.REASON_CRASH_NATIVE,
								ProcessExitStore.SIGNAL_SEGV, false, 36)).category);
		assertEquals(IncidentSummary.Category.ANR,
				IncidentInterpreter.interpret(
						null, java, exit(ProcessExitStore.REASON_ANR, 0, false, 36)).category);
	}

	@Test
	public void midletMetadataDoesNotTurnHostFrameIntoGuestBlame() {
		JavaDiagnosticStore.ThrowableData host = throwable(
				"java.lang.IllegalStateException",
				"host",
				"io.github.h3nb.jlmodplus.runtime.HostBridge",
				"run");
		IncidentSummary incident = IncidentInterpreter.interpret(
				null, javaEvidence(List.of(host), 0, "game.Main"), null);

		assertEquals(IncidentSummary.Category.JL_MOD_PLUS, incident.category);
		assertEquals(IncidentSummary.FailureOrigin.JL_MOD_PLUS, incident.failureOrigin);
	}

	@Test
	public void unrelatedFrameWithMidletMetadataRemainsNeutral() {
		JavaDiagnosticStore.ThrowableData unknown = throwable(
				"java.lang.IllegalStateException", "boom", "third.party.Library", "run");
		IncidentSummary incident = IncidentInterpreter.interpret(
				null, javaEvidence(List.of(unknown), 0, "game.Main"), null);

		assertEquals(IncidentSummary.Category.JAVA_FAILURE, incident.category);
		assertEquals(IncidentSummary.FailureOrigin.UNKNOWN, incident.failureOrigin);
		assertTrue(GitHubDiagnosticIssue.description(incident, null)
				.contains("does not establish guest or emulator ownership"));
	}

	@Test
	public void directJavaEvidencePreservesLifecycleBoundaryWithoutJournal() {
		JavaDiagnosticStore.ThrowableData primary = throwable(
				"java.lang.NoClassDefFoundError", "missing", "GloftOTSP", "startApp");
		JavaDiagnosticStore.Snapshot java = new JavaDiagnosticStore.Snapshot(
				new File("journal-degraded.java.properties"),
				JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE,
				1000L,
				"io.github.h3nb.jlmodplus:midlet",
				"midlet",
				123,
				"MidletMain",
				17,
				5,
				SESSION,
				"OregonTrailAmericanSettler",
				"1.0",
				"GloftOTSP",
				"abc123",
				"1.0",
				"16",
				36,
				"POCO",
				"F7",
				"arm64-v8a",
				null,
				"stack",
				List.of(primary),
				0,
				EVENT,
				MidletSessionJournal.FailureBoundary.LIFECYCLE_START.name(),
				null);

		IncidentSummary incident = IncidentInterpreter.interpret(null, java, null);

		assertEquals(IncidentSummary.Category.MIDLET_LIFECYCLE, incident.category);
		assertEquals("startApp()", incident.operation);
		assertEquals("LIFECYCLE_START", incident.boundary);
		assertEquals(EVENT, incident.eventId);
		assertEquals(SESSION, incident.sessionId);
		assertEquals("NoClassDefFoundError", incident.primaryFailure.simpleType());
	}

	@Test
	public void handledSessionFailureDoesNotHideIndependentAndroidCrash() {
		JavaDiagnosticStore.Snapshot java = javaEvidence(
				JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE,
				List.of(throwable("java.lang.IllegalStateException", "boom", "GloftOTSP", "startApp")),
				0,
				"GloftOTSP");
		IncidentSummary incident = IncidentInterpreter.interpret(
				session(MidletSessionJournal.FailureBoundary.LIFECYCLE_START),
				java,
				exit(ProcessExitStore.REASON_CRASH, 0, true, 36));

		assertFalse(incident.associatedProcessExit.controlledByJlMod);
		assertEquals("Android reported a Java process crash.",
				incident.associatedProcessExit.summary);
	}

	@Test
	public void journalOnlyTerminalFailureExposesMissingJavaEvidence() {
		IncidentSummary incident = IncidentInterpreter.interpret(
				session(MidletSessionJournal.FailureBoundary.LIFECYCLE_START),
				null,
				exit(ProcessExitStore.REASON_CRASH, 0, true, 36));

		assertTrue(incident.primaryFailure == null);
		assertTrue(incident.limitations.stream()
				.anyMatch(value -> value.contains("Java evidence was not retained")));
	}

	@Test
	public void sigkillLimitationDependsOnPlatformCapability() {
		String unsupported = IncidentInterpreter.processExitLimitation(
				exit(ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL, false, 36));
		String supported = IncidentInterpreter.processExitLimitation(
				exit(ProcessExitStore.REASON_SIGNALED, ProcessExitStore.SIGNAL_KILL, true, 36));

		assertTrue(unsupported.contains("cannot reliably distinguish"));
		assertTrue(supported.contains("did not provide a more specific termination cause"));
		assertTrue(supported.contains("separately reportable"));
		assertTrue(supported.contains("was not classified as one"));
	}

	@Test
	public void processExitOnlyUsesExitTimestampAndStoredProvenance() {
		ProcessExitStore.Snapshot exit =
				exit(ProcessExitStore.REASON_ANR, 0, false, -1);
		IncidentSummary incident = IncidentInterpreter.interpret(cleanSession(), null, exit);
		String json = DiagnosticBundleFormat.incidentJson(incident);

		assertEquals(1100L, incident.incidentTimestampMillis);
		assertEquals(ProcessExitStore.SOURCE_APPLICATION_EXIT_INFO,
				incident.associatedProcessExit.source);
		assertTrue(json.contains("\"source\": \"android-application-exit-info\""));
	}

	@Test
	public void unknownFutureExitReasonSurvivesNumerically() {
		ProcessExitStore.Snapshot exit = exit(42, 0, false, 36);
		IncidentSummary incident = IncidentInterpreter.interpret(null, null, exit);

		assertEquals("Process termination (reason 42)",
				incident.associatedProcessExit.reasonLabel);
		assertEquals("Android reported process-exit reason 42.",
				incident.associatedProcessExit.summary);
	}

	@Test
	public void memoryLimiterSupportsDedicatedReasonAndDocumentedOtherMarker() {
		IncidentSummary dedicated = IncidentInterpreter.interpret(
				null, null, exit(ProcessExitStore.REASON_MEMORY_LIMITER, 0, false, 37));
		IncidentSummary marker = IncidentInterpreter.interpret(
				null, null, exit(ProcessExitStore.REASON_OTHER, 0, false, 37,
						"MemoryLimiter:AnonSwap threshold exceeded"));

		assertEquals(IncidentSummary.Category.PROCESS_EXIT, dedicated.category);
		assertEquals("Memory-limit termination", dedicated.associatedProcessExit.reasonLabel);
		assertTrue(dedicated.associatedProcessExit.summary.contains("system memory limit"));
		assertEquals("Memory-limit termination", marker.associatedProcessExit.reasonLabel);
		assertTrue(marker.associatedProcessExit.summary.contains("system memory limit"));
		assertEquals("MemoryLimiter:AnonSwap threshold exceeded",
				marker.associatedProcessExit.description);
	}

	@Test
	public void memoryEngineProcessExitUsesReadableSubjectAndStableRole() {
		ProcessExitStore.Snapshot original = exit(
				ProcessExitStore.REASON_CRASH, 0, false, 36, null);
		ProcessExitStore.Snapshot memoryEngine = new ProcessExitStore.Snapshot(
				original.recordFile,
				original.traceFile,
				original.key,
				original.source,
				original.timestampMillis,
				"io.github.h3nb.jlmodplus.debug:memory_engine",
				"memory_engine",
				original.pid,
				original.reason,
				original.status,
				original.importance,
				original.pssKb,
				original.rssKb,
				original.description,
				original.lowMemoryKillReportSupported,
				original.stateVersionCode,
				original.stateSdk,
				original.androidRelease,
				original.sessionId,
				original.deviceBrand,
				original.deviceModel,
				original.primaryAbi,
				original.traceKind,
				original.traceBytes,
				original.traceTruncated,
				original.anrType,
				original.anrTimeoutMillis,
				original.anrId,
				original.anrUserPerceptible,
				original.appContext);

		IncidentSummary incident = IncidentInterpreter.interpret(null, null, memoryEngine);

		assertEquals("Memory Engine", incident.subject);
		assertEquals("memory_engine · io.github.h3nb.jlmodplus.debug:memory_engine",
				incident.process);
		assertEquals("memory_engine", incident.associatedProcessExit.processRole);
	}

	@Test
	public void api28ProcessDisappearanceStatesPlatformLimitation() {
		ProcessExitStore.Snapshot exit =
				exit(ProcessExitStore.REASON_UNKNOWN, 0, false, 28);
		IncidentSummary incident = IncidentInterpreter.interpret(null, null, exit);

		assertEquals(IncidentSummary.Category.PROCESS_EXIT, incident.category);
		assertTrue(incident.limitations.stream()
				.anyMatch(value -> value.contains("Android API 28")));
		assertFalse(incident.associatedProcessExit.summary.toLowerCase().contains("anr"));
		assertFalse(incident.associatedProcessExit.summary.toLowerCase().contains("low-memory"));
	}

	private static JavaDiagnosticStore.ThrowableData throwable(
			String type, String message, String frameClass, String method) {
		return new JavaDiagnosticStore.ThrowableData(
				type,
				message,
				List.of(new JavaDiagnosticStore.FrameData(
						frameClass, method, "SourceFile", 42, false)));
	}

	private static JavaDiagnosticStore.Snapshot javaEvidence(
			List<JavaDiagnosticStore.ThrowableData> chain, int primaryIndex, String mainClass) {
		return javaEvidence(JavaDiagnosticStore.Kind.FATAL_UNCAUGHT, chain, primaryIndex, mainClass);
	}

	private static JavaDiagnosticStore.Snapshot javaEvidence(
			JavaDiagnosticStore.Kind kind, List<JavaDiagnosticStore.ThrowableData> chain,
			int primaryIndex, String mainClass) {
		return javaEvidence(kind, chain, primaryIndex, mainClass, null);
	}

	private static JavaDiagnosticStore.Snapshot javaEvidence(
			JavaDiagnosticStore.Kind kind, List<JavaDiagnosticStore.ThrowableData> chain,
			int primaryIndex, String mainClass,
			MidletSessionJournal.FailureBoundary boundary) {
		return new JavaDiagnosticStore.Snapshot(
				new File("incident.java.properties"),
				kind,
				1000L,
				"io.github.h3nb.jlmodplus:midlet",
				"midlet",
				123,
				"MidletMain",
				17,
				5,
				SESSION,
				"OregonTrailAmericanSettler",
				"1.0",
				mainClass,
				"abc123",
				"1.0",
				"16",
				36,
				"POCO",
				"F7",
				"arm64-v8a",
				null,
				"stack",
				chain,
				primaryIndex,
				null,
				boundary == null ? null : boundary.name(),
				null);
	}

	private static MidletSessionJournal.Snapshot session(
			MidletSessionJournal.FailureBoundary boundary) {
		return new MidletSessionJournal.Snapshot(
				2,
				SESSION,
				"io.github.h3nb.jlmodplus:midlet",
				123,
				900L,
				1L,
				1000L,
				2L,
				boundary == MidletSessionJournal.FailureBoundary.LIFECYCLE_DESTROY
						? MidletSessionJournal.Stage.STOPPING : MidletSessionJournal.Stage.STARTING,
				MidletSessionJournal.Outcome.UNEXPECTED_FAILURE,
				EVENT,
				boundary,
				"OregonTrailAmericanSettler",
				"Vendor",
				"1.0",
				"GloftOTSP",
				"1",
				"abc123");
	}

	private static MidletSessionJournal.Snapshot cleanSession() {
		return new MidletSessionJournal.Snapshot(
				2,
				SESSION,
				"io.github.h3nb.jlmodplus:midlet",
				123,
				900L,
				1L,
				1000L,
				2L,
				MidletSessionJournal.Stage.RUNNING,
				MidletSessionJournal.Outcome.NONE,
				null,
				null,
				"OregonTrailAmericanSettler",
				"Vendor",
				"1.0",
				"GloftOTSP",
				"1",
				"abc123");
	}

	private static ProcessExitStore.Snapshot exit(
			int reason, int status, boolean lmkSupported, int sdk) {
		return exit(reason, status, lmkSupported, sdk, null);
	}

	private static ProcessExitStore.Snapshot exit(
			int reason, int status, boolean lmkSupported, int sdk, String description) {
		String source = sdk >= 23 && sdk < 30
				? ProcessExitStore.SOURCE_LEGACY_PROCESS_DISAPPEARANCE
				: ProcessExitStore.SOURCE_APPLICATION_EXIT_INFO;
		return new ProcessExitStore.Snapshot(
				new File("exit.properties"),
				null,
				"key",
				source,
				1100L,
				"io.github.h3nb.jlmodplus:midlet",
				"midlet",
				123,
				reason,
				status,
				ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
				412L * 1024,
				596L * 1024,
				description,
				lmkSupported,
				1L,
				sdk,
				sdk == 36 ? "16" : null,
				SESSION,
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
}
