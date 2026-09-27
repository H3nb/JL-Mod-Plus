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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.Test;

public class DiagnosticBundleFormatTest {
	@Test
	public void bundleIsReadableVersionedAndAddsOnlyAvailableEvidence() throws Exception {
		String json = DiagnosticBundleFormat.incidentJson(minimalIncident());
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		DiagnosticBundleFormat.write(bytes, "# report\n", json, "main thread trace", null);

		Map<String, String> entries = unzip(bytes.toByteArray());
		assertEquals(3, entries.size());
		assertEquals("# report\n", entries.get(DiagnosticBundleFormat.REPORT_ENTRY));
		assertTrue(entries.get(DiagnosticBundleFormat.INCIDENT_ENTRY)
				.contains("\"formatVersion\": 2"));
		assertEquals("main thread trace", entries.get(DiagnosticBundleFormat.ANR_ENTRY));
		assertFalse(entries.containsKey(DiagnosticBundleFormat.TOMBSTONE_ENTRY));
	}

	@Test
	public void incidentJsonKeepsEnvironmentAndFailureProvenanceSeparate() {
		IncidentSummary incident = minimalIncident();
		String json = DiagnosticBundleFormat.incidentJson(incident);

		assertTrue(json.contains("\"androidRelease\": \"16\""));
		assertTrue(json.contains("\"androidSdk\": 36"));
		assertTrue(json.contains("\"javaFailureSource\": \"java-evidence\""));
		assertTrue(json.contains("\"operationSource\": \"session-journal\""));
		assertTrue(json.contains("\"primaryFailure\": {"));
		assertTrue(json.contains("\"underlyingCause\": {"));
	}

	@Test
	public void sanitizesValuesBeforeJsonEscaping() {
		IncidentSummary base = minimalIncident();
		IncidentSummary incident = new IncidentSummary(
				base.category, base.failureOrigin, base.incidentTimestampMillis, base.subject,
				new IncidentSummary.JavaFailure(
						"java.lang.IllegalStateException",
						"failed at content://private.provider/item/42",
						"example.Game.run(Game.java:42)"),
				base.underlyingCause, base.javaEvidenceKind, base.operation, base.boundary,
				base.lifecycleStage, base.topRelevantFrame, base.midletVersion, base.entrypoint,
				base.jarFingerprint, base.build, base.androidRelease, base.androidSdk,
				base.device, base.primaryAbi, base.process, base.eventId, base.sessionId,
				base.breadcrumbs, base.associatedProcessExit, base.limitations);

		String json = DiagnosticBundleFormat.incidentJson(
				incident, null, value -> DiagnosticExportSanitizer.sanitize(value, null, null));

		assertTrue(json.contains("\"message\": \"failed at <uri>\""));
		assertFalse(json.contains("private.provider"));
	}

	@Test
	public void contentFingerprintChangesWhenExportedEvidenceChanges() {
		String first = DiagnosticBundleFormat.contentFingerprint(
				"# report\n", "{\"formatVersion\":2}\n", null, null);
		String same = DiagnosticBundleFormat.contentFingerprint(
				"# report\n", "{\"formatVersion\":2}\n", null, null);
		String withAnr = DiagnosticBundleFormat.contentFingerprint(
				"# report\n", "{\"formatVersion\":2}\n", "ANR trace", null);

		assertEquals(first, same);
		assertEquals(first, DiagnosticBundleFormat.contentFingerprint(
				"# report\n", "{\"formatVersion\":2}\n", "   ", null));
		assertFalse(first.equals(withAnr));
	}

	@Test
	public void oversizedTextEntryIsTruncatedInsteadOfBlockingBundle() throws Exception {
		String oversized = "x".repeat(DiagnosticBundleFormat.MAX_TEXT_ENTRY_BYTES + 1024);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DiagnosticBundleFormat.write(
				bytes, oversized, "{\"formatVersion\":2}\n", null, null);

		String report = unzip(bytes.toByteArray()).get(DiagnosticBundleFormat.REPORT_ENTRY);
		assertTrue(report.endsWith(DiagnosticBundleFormat.TRUNCATION_MARKER));
		assertTrue(report.getBytes(StandardCharsets.UTF_8).length
				<= DiagnosticBundleFormat.MAX_TEXT_ENTRY_BYTES);
	}

	@Test
	public void reuseValidationRejectsExternallyModifiedBundle() throws Exception {
		String report = "# report\n";
		String incident = "{\"formatVersion\":2}\n";
		String fingerprint = DiagnosticBundleFormat.contentFingerprint(report, incident, null, null);
		ByteArrayOutputStream original = new ByteArrayOutputStream();
		DiagnosticBundleFormat.write(original, report, incident, null, null);
		assertTrue(DiagnosticBundleFormat.matchesContentFingerprint(
				new ByteArrayInputStream(original.toByteArray()), fingerprint));

		ByteArrayOutputStream modified = new ByteArrayOutputStream();
		DiagnosticBundleFormat.write(modified, "# changed\n", incident, null, null);
		assertFalse(DiagnosticBundleFormat.matchesContentFingerprint(
				new ByteArrayInputStream(modified.toByteArray()), fingerprint));
	}

	@Test
	public void tombstoneSummaryIsPublishedAsTextNotOpaqueProtobuf() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DiagnosticBundleFormat.write(
				bytes,
				"# report\n",
				"{\"formatVersion\":2}\n",
				null,
				"Native crash details\nSignal: SIGSEGV (11)");

		Map<String, String> entries = unzip(bytes.toByteArray());
		assertTrue(entries.containsKey(DiagnosticBundleFormat.TOMBSTONE_ENTRY));
		assertFalse(entries.keySet().stream().anyMatch(name -> name.endsWith(".pb")));
		assertFalse(entries.keySet().stream().anyMatch(name -> name.endsWith(".proto")));
	}

	private static IncidentSummary minimalIncident() {
		return new IncidentSummary(
				IncidentSummary.Category.MIDLET_LIFECYCLE,
				IncidentSummary.FailureOrigin.MIDLET,
				1234L,
				"Game",
				new IncidentSummary.JavaFailure(
						"java.lang.NoClassDefFoundError", "Failed resolution",
						"game.Main.startApp(Main.java:42)"),
				new IncidentSummary.JavaFailure(
						"java.lang.ClassNotFoundException", "Missing class",
						"dalvik.system.BaseDexClassLoader.findClass(BaseDexClassLoader.java:259)"),
				JavaDiagnosticStore.Kind.FATAL_UNCAUGHT.name(),
				"startApp()",
				"LIFECYCLE_START",
				"STARTING",
				"game.Main.startApp(Main.java:42)",
				"1.0",
				"game.Main",
				"abc123",
				"deadbeef · emulatorDebug",
				"16",
				36,
				"POCO F7",
				"arm64-v8a",
				"midlet · pkg:midlet",
				"event",
				"session",
				Collections.emptyList(),
				null,
				Collections.emptyList());
	}

	private static Map<String, String> unzip(byte[] bytes) throws Exception {
		Map<String, String> result = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(
				new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			byte[] buffer = new byte[1024];
			while ((entry = zip.getNextEntry()) != null) {
				ByteArrayOutputStream content = new ByteArrayOutputStream();
				int count;
				while ((count = zip.read(buffer)) != -1) content.write(buffer, 0, count);
				result.put(entry.getName(), content.toString(StandardCharsets.UTF_8));
			}
		}
		return result;
	}
}
