/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Versioned, transparent public diagnostic bundle format. */
final class DiagnosticBundleFormat {
	static final int FORMAT_VERSION = 2;
	static final String REPORT_ENTRY = "report.md";
	static final String INCIDENT_ENTRY = "incident.json";
	static final String ANR_ENTRY = "evidence/anr-trace.txt";
	static final String TOMBSTONE_ENTRY = "evidence/tombstone-summary.txt";
	static final int MAX_TEXT_ENTRY_BYTES = 256 * 1024;
	static final String TRUNCATION_MARKER = "\n\n[... diagnostic entry truncated ...]\n";

	interface ValueSanitizer {
		String sanitize(String value);
	}

	private static final ValueSanitizer IDENTITY = value -> value;

	private DiagnosticBundleFormat() {}

	static void write(OutputStream destination, String reportMarkdown, String incidentJson,
			String anrTrace, String tombstoneSummary) throws IOException {
		if (destination == null) throw new IOException("Missing bundle destination");
		ZipOutputStream zip = new ZipOutputStream(destination);
		writeRequired(zip, REPORT_ENTRY, reportMarkdown);
		writeRequired(zip, INCIDENT_ENTRY, incidentJson);
		writeOptional(zip, ANR_ENTRY, anrTrace);
		writeOptional(zip, TOMBSTONE_ENTRY, tombstoneSummary);
		zip.finish();
		zip.flush();
	}

	static String contentFingerprint(String reportMarkdown, String incidentJson,
			String anrTrace, String tombstoneSummary) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateDigest(digest, REPORT_ENTRY, reportMarkdown);
			updateDigest(digest, INCIDENT_ENTRY, incidentJson);
			updateDigest(digest, ANR_ENTRY, optionalText(anrTrace));
			updateDigest(digest, TOMBSTONE_ENTRY, optionalText(tombstoneSummary));
			byte[] hash = digest.digest();
			StringBuilder result = new StringBuilder(hash.length * 2);
			for (byte value : hash) result.append(String.format(Locale.US, "%02x", value & 0xff));
			return result.toString();
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError("SHA-256 unavailable", impossible);
		}
	}

	private static String optionalText(String text) {
		return text == null || text.trim().isEmpty() ? null : text;
	}

	private static void updateDigest(MessageDigest digest, String name, String text) {
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		digest.update((byte) (nameBytes.length >>> 8));
		digest.update((byte) nameBytes.length);
		digest.update(nameBytes);
		if (text == null) {
			digest.update((byte) 0);
			return;
		}
		digest.update((byte) 1);
		byte[] bytes = boundedBytes(text);
		digest.update((byte) (bytes.length >>> 24));
		digest.update((byte) (bytes.length >>> 16));
		digest.update((byte) (bytes.length >>> 8));
		digest.update((byte) bytes.length);
		digest.update(bytes);
	}

	static String incidentJson(IncidentSummary incident) {
		return incidentJson(incident, null, IDENTITY);
	}

	static String incidentJson(IncidentSummary incident, NativeTombstoneSummary.Summary nativeSummary) {
		return incidentJson(incident, nativeSummary, IDENTITY);
	}

	static String incidentJson(IncidentSummary incident,
			NativeTombstoneSummary.Summary nativeSummary, ValueSanitizer sanitizer) {
		if (sanitizer == null) sanitizer = IDENTITY;
		StringBuilder json = new StringBuilder(2048);
		json.append("{\n");
		field(json, "formatVersion", Integer.toString(FORMAT_VERSION), false, true);
		field(json, "category", incident.category.name(), true, true);
		field(json, "failureOrigin", incident.failureOrigin.name(), true, true);
		field(json, "timestampMillis", Long.toString(incident.incidentTimestampMillis), false, true);
		field(json, "subject", safe(sanitizer, incident.subject), true, true);
		appendFailure(json, "primaryFailure", incident.primaryFailure, sanitizer, true);
		appendFailure(json, "underlyingCause", incident.underlyingCause, sanitizer, true);
		field(json, "javaEvidenceKind", incident.javaEvidenceKind, true, true);
		field(json, "javaFailureSource", incident.primaryFailure == null ? null : "java-evidence", true, true);
		field(json, "operation", safe(sanitizer, incident.operation), true, true);
		field(json, "operationSource", incident.operation == null ? null : "session-journal", true, true);
		field(json, "boundary", safe(sanitizer, incident.boundary), true, true);
		field(json, "lifecycleStage", safe(sanitizer, incident.lifecycleStage), true, true);
		field(json, "midletVersion", safe(sanitizer, incident.midletVersion), true, true);
		field(json, "entrypoint", safe(sanitizer, incident.entrypoint), true, true);
		field(json, "jarFingerprint", safe(sanitizer, incident.jarFingerprint), true, true);
		field(json, "build", safe(sanitizer, incident.build), true, true);
		field(json, "androidRelease", safe(sanitizer, incident.androidRelease), true, true);
		field(json, "androidSdk", incident.androidSdk < 0 ? null : Integer.toString(incident.androidSdk),
				false, true);
		field(json, "device", safe(sanitizer, incident.device), true, true);
		field(json, "primaryAbi", safe(sanitizer, incident.primaryAbi), true, true);
		field(json, "process", safe(sanitizer, incident.process), true, true);
		field(json, "eventId", safe(sanitizer, incident.eventId), true, true);
		field(json, "sessionId", safe(sanitizer, incident.sessionId), true, true);
		field(json, "fingerprint", incident.fingerprint, true,
				incident.associatedProcessExit != null || nativeSummary != null || !incident.limitations.isEmpty());
		if (incident.associatedProcessExit != null) {
			appendProcessExit(json, incident.associatedProcessExit, sanitizer,
					nativeSummary != null || !incident.limitations.isEmpty());
		}
		if (nativeSummary != null) {
			appendNative(json, nativeSummary, sanitizer, !incident.limitations.isEmpty());
		}
		if (!incident.limitations.isEmpty()) {
			json.append("  \"limitations\": [\n");
			for (int i = 0; i < incident.limitations.size(); i++) {
				json.append("    \"").append(escape(safe(sanitizer, incident.limitations.get(i)))).append("\"");
				if (i + 1 < incident.limitations.size()) json.append(',');
				json.append('\n');
			}
			json.append("  ]\n");
		}
		json.append("}\n");
		return json.toString();
	}

	private static void appendFailure(StringBuilder json, String name, IncidentSummary.JavaFailure failure,
			ValueSanitizer sanitizer, boolean comma) {
		if (failure == null) {
			field(json, name, null, true, comma);
			return;
		}
		json.append("  \"").append(name).append("\": {\n");
		field(json, "type", safe(sanitizer, failure.type), true, true, 4);
		field(json, "message", safe(sanitizer, failure.message), true, true, 4);
		field(json, "frame", safe(sanitizer, failure.frame), true, false, 4);
		json.append("  }").append(comma ? ',' : ' ').append('\n');
	}

	private static void appendProcessExit(StringBuilder json, IncidentSummary.ProcessExitEvidence exit,
			ValueSanitizer sanitizer, boolean comma) {
		json.append("  \"associatedProcessExit\": {\n");
		field(json, "source", exit.source, true, true, 4);
		field(json, "reason", Integer.toString(exit.reason), false, true, 4);
		field(json, "status", Integer.toString(exit.status), false, true, 4);
		field(json, "reasonLabel", safe(sanitizer, exit.reasonLabel), true, true, 4);
		field(json, "statusLabel", safe(sanitizer, exit.statusLabel), true, true, 4);
		field(json, "importance", safe(sanitizer, exit.importance), true, true, 4);
		field(json, "summary", safe(sanitizer, exit.summary), true, true, 4);
		field(json, "description", safe(sanitizer, exit.description), true, true, 4);
		field(json, "pssKb", exit.pssKb > 0 ? Long.toString(exit.pssKb) : null, false, true, 4);
		field(json, "rssKb", exit.rssKb > 0 ? Long.toString(exit.rssKb) : null, false, true, 4);
		field(json, "lowMemoryKillReportSupported",
				Boolean.toString(exit.lowMemoryKillReportSupported), false, true, 4);
		field(json, "traceKind", exit.traceKind, true, true, 4);
		field(json, "traceAvailable", Boolean.toString(exit.traceAvailable), false, true, 4);
		field(json, "traceTruncated", Boolean.toString(exit.traceTruncated), false, true, 4);
		field(json, "anrType", exit.anrType >= 0 ? Integer.toString(exit.anrType) : null, false, true, 4);
		field(json, "anrTimeoutMillis", exit.anrTimeoutMillis >= 0
				? Long.toString(exit.anrTimeoutMillis) : null, false, true, 4);
		field(json, "anrId", exit.anrId >= 0 ? Integer.toString(exit.anrId) : null, false, true, 4);
		field(json, "anrUserPerceptible", exit.anrUserPerceptible == null ? null
				: Boolean.toString(exit.anrUserPerceptible), false, true, 4);
		field(json, "controlledByJlMod", Boolean.toString(exit.controlledByJlMod),
				false, false, 4);
		json.append("  }").append(comma ? ',' : ' ').append('\n');
	}

	private static void appendNative(StringBuilder json, NativeTombstoneSummary.Summary nativeSummary,
			ValueSanitizer sanitizer, boolean comma) {
		json.append("  \"nativeCrash\": {\n");
		field(json, "source", "android-tombstone", true, true, 4);
		field(json, "signalNumber", number(nativeSummary.signalNumber), false, true, 4);
		field(json, "signalName", safe(sanitizer, nativeSummary.signalName), true, true, 4);
		field(json, "signalCode", number(nativeSummary.signalCode), false, true, 4);
		field(json, "signalCodeName", safe(sanitizer, nativeSummary.signalCodeName), true, true, 4);
		field(json, "cause", safe(sanitizer, nativeSummary.cause), true, true, 4);
		field(json, "crashingThread", safe(sanitizer, nativeSummary.threadName), true, true, 4);
		field(json, "topProjectFrame", safe(sanitizer, topProjectFrame(nativeSummary)), true, false, 4);
		json.append("  }").append(comma ? ',' : ' ').append('\n');
	}

	private static String safe(ValueSanitizer sanitizer, String value) {
		return value == null ? null : sanitizer.sanitize(value);
	}

	private static String number(int value) {
		return value == Integer.MIN_VALUE ? null : Integer.toString(value);
	}

	private static String topProjectFrame(NativeTombstoneSummary.Summary summary) {
		for (NativeTombstoneSummary.Frame frame : summary.frames) {
			String function = frame.functionName;
			if (function != null && (function.startsWith("io.github.h3nb.jlmodplus.")
					|| function.startsWith("ru.playsoftware.j2meloader.")
					|| function.startsWith("javax.microedition."))) {
				return function;
			}
		}
		return null;
	}

	private static void writeRequired(ZipOutputStream zip, String name, String text) throws IOException {
		if (text == null || text.isEmpty()) throw new IOException("Missing required bundle entry: " + name);
		writeEntry(zip, name, text);
	}

	private static void writeOptional(ZipOutputStream zip, String name, String text) throws IOException {
		if (text != null && !text.trim().isEmpty()) writeEntry(zip, name, text);
	}

	private static void writeEntry(ZipOutputStream zip, String name, String text) throws IOException {
		byte[] bytes = boundedBytes(text);
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(0L);
		zip.putNextEntry(entry);
		zip.write(bytes);
		zip.closeEntry();
	}

	static boolean matchesContentFingerprint(InputStream source, String expectedFingerprint)
			throws IOException {
		if (source == null || expectedFingerprint == null) return false;
		String report = null;
		String incident = null;
		String anrTrace = null;
		String tombstone = null;
		try (ZipInputStream zip = new ZipInputStream(source)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				String value = readTextEntry(zip);
				switch (name) {
					case REPORT_ENTRY -> {
						if (report != null) return false;
						report = value;
					}
					case INCIDENT_ENTRY -> {
						if (incident != null) return false;
						incident = value;
					}
					case ANR_ENTRY -> {
						if (anrTrace != null) return false;
						anrTrace = value;
					}
					case TOMBSTONE_ENTRY -> {
						if (tombstone != null) return false;
						tombstone = value;
					}
					default -> { return false; }
				}
			}
		}
		return report != null && incident != null && expectedFingerprint.equals(
				contentFingerprint(report, incident, anrTrace, tombstone));
	}

	private static String readTextEntry(ZipInputStream zip) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int total = 0;
		int count;
		while ((count = zip.read(buffer)) != -1) {
			total += count;
			if (total > MAX_TEXT_ENTRY_BYTES) {
				throw new IOException("Diagnostic bundle entry exceeds retention bound");
			}
			output.write(buffer, 0, count);
		}
		return new String(output.toByteArray(), StandardCharsets.UTF_8);
	}

	private static byte[] boundedBytes(String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		if (bytes.length <= MAX_TEXT_ENTRY_BYTES) return bytes;
		byte[] marker = TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8);
		int cut = MAX_TEXT_ENTRY_BYTES - marker.length;
		while (cut > 0 && (bytes[cut] & 0xc0) == 0x80) cut--;
		byte[] bounded = new byte[cut + marker.length];
		System.arraycopy(bytes, 0, bounded, 0, cut);
		System.arraycopy(marker, 0, bounded, cut, marker.length);
		return bounded;
	}

	private static void field(StringBuilder json, String name, String value, boolean quote,
			boolean comma) {
		field(json, name, value, quote, comma, 2);
	}

	private static void field(StringBuilder json, String name, String value, boolean quote,
			boolean comma, int indent) {
		for (int i = 0; i < indent; i++) json.append(' ');
		json.append('"').append(escape(name)).append("\": ");
		if (value == null) {
			json.append("null");
		} else if (quote) {
			json.append('"').append(escape(value)).append('"');
		} else {
			json.append(value);
		}
		if (comma) json.append(',');
		json.append('\n');
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder(value.length() + 16);
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			switch (ch) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\b' -> escaped.append("\\b");
				case '\f' -> escaped.append("\\f");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (ch < 0x20) escaped.append(String.format(Locale.US, "\\u%04x", (int) ch));
					else escaped.append(ch);
				}
			}
		}
		return escaped.toString();
	}
}
