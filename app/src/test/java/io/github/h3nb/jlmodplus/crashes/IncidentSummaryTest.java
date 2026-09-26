/*
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

package io.github.h3nb.jlmodplus.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class IncidentSummaryTest {
	@Test
	public void lifecycleFixtureUsesDeepestMeaningfulCauseAndSupportedOperation() {
		String stack = "eventId=event-137; boundary=LIFECYCLE_DESTROY\n"
				+ "jlamf\n"
				+ "Caused by: java.lang.RuntimeException: Failed destroyApp\n"
				+ "\tat javax.microedition.shell.MidletThread.stop(MidletThread.java:100)\n"
				+ "Caused by: java.lang.NullPointerException: Attempt to get length of null array\n"
				+ "\tat GloftOTSP.destroyApp(SourceFile:42)\n";
		IncidentSummary.JavaFailure failure = IncidentSummary.analyzeJavaFailure(stack);
		IncidentSummary.ProcessExitEvidence exit = new IncidentSummary.ProcessExitEvidence(
				ProcessExitStore.REASON_SIGNALED,
				9,
				"Signal termination",
				"SIGKILL (9)",
				"cached",
				"unknown",
				"io.github.h3nb.jlmodplus:midlet",
				"midlet",
				null,
				36,
				"Example device");
		IncidentSummary incident = new IncidentSummary(
				IncidentSummary.Category.MIDLET_LIFECYCLE,
				1_790_431_195_427L,
				"OregonTrailAmericanSettler",
				failure,
				"destroyApp()",
				"STOPPING",
				failure.frame,
				"1.0.0",
				"GloftOTSP",
				"c06a9388af38",
				"deadbeef · emulatorDebug",
				"Android SDK 36 · Example device",
				"midlet",
				Collections.emptyList(),
				exit);

		assertEquals("java.lang.NullPointerException", failure.type);
		assertEquals("GloftOTSP.destroyApp(SourceFile:42)", failure.frame);
		assertEquals(
				"[MIDlet lifecycle] OregonTrailAmericanSettler — NullPointerException in destroyApp()",
				GitHubDiagnosticIssue.title(incident, null));
		String description = GitHubDiagnosticIssue.description(incident, null);
		assertTrue(description.contains("NullPointerException"));
		assertTrue(description.contains("GloftOTSP.destroyApp()"));
		assertTrue(description.contains("during teardown"));

		String summary = GitHubDiagnosticIssue.compactSummary(
				incident, null, incident.bundleFileName());
		assertTrue(summary.contains("Associated process-exit evidence"));
		assertTrue(summary.contains("Cause:** unknown"));
		assertFalse(summary.toLowerCase(java.util.Locale.ROOT).contains("caused the java"));
		assertFalse(summary.toLowerCase(java.util.Locale.ROOT).contains("low-memory"));
	}

	@Test
	public void internalCorrelationWrapperIsNotReportedAsRootFailure() {
		IncidentSummary.JavaFailure failure = IncidentSummary.analyzeJavaFailure(
				"eventId=e1; boundary=UNCAUGHT_THREAD\njlamf\n"
						+ "java.lang.IllegalStateException: useful\n"
						+ "\tat example.Game.run(Game.java:42)");

		assertEquals("java.lang.IllegalStateException", failure.type);
		assertEquals("useful", failure.message);
	}

	@Test
	public void laterAssociatedExitDoesNotChangeJavaIncidentIdentity() {
		IncidentSummary withoutExit = minimalWithExit(null);
		IncidentSummary.ProcessExitEvidence exit = new IncidentSummary.ProcessExitEvidence(
				ProcessExitStore.REASON_SIGNALED,
				9,
				"Signal termination",
				"SIGKILL (9)",
				"cached",
				"unknown",
				"io.github.h3nb.jlmodplus:midlet",
				"midlet",
				null,
				36,
				"Device");
		IncidentSummary withExit = minimalWithExit(exit);

		assertEquals(withoutExit.fingerprint, withExit.fingerprint);
		assertEquals(withoutExit.bundleFileName(), withExit.bundleFileName());
	}

	@Test
	public void equivalentEvidenceHasStableFingerprintAndFilename() {
		IncidentSummary first = minimal(0L, "java.lang.IllegalStateException: boom");
		IncidentSummary second = minimal(0L, "java.lang.IllegalStateException: different message");

		assertEquals(first.fingerprint, second.fingerprint);
		assertEquals(first.bundleFileName(), second.bundleFileName());
		assertTrue(first.bundleFileName().startsWith("19700101-000000-000-"));
		assertTrue(first.bundleFileName().endsWith(".zip"));
	}

	private static IncidentSummary minimalWithExit(
			IncidentSummary.ProcessExitEvidence exit) {
		IncidentSummary.JavaFailure failure = IncidentSummary.analyzeJavaFailure(
				"java.lang.IllegalStateException: boom\n\tat example.Game.run(Game.java:42)");
		return new IncidentSummary(
				IncidentSummary.Category.MIDLET_CRASH,
				1234L,
				"Game",
				failure,
				null,
				null,
				failure.frame,
				"1.0",
				"example.Game",
				"abc123",
				"deadbeef · emulatorDebug",
				"Android 16 · Device",
				"midlet",
				Collections.emptyList(),
				exit);
	}

	private static IncidentSummary minimal(long timestamp, String failureLine) {
		IncidentSummary.JavaFailure failure = IncidentSummary.analyzeJavaFailure(
				failureLine + "\n\tat example.Game.run(Game.java:42)");
		return new IncidentSummary(
				IncidentSummary.Category.MIDLET_CRASH,
				timestamp,
				"Game",
				failure,
				null,
				null,
				failure.frame,
				"1.0",
				"example.Game",
				"abc123",
				"deadbeef · emulatorDebug",
				"Android 16 · Device",
				"midlet",
				Collections.emptyList(),
				null);
	}
}
