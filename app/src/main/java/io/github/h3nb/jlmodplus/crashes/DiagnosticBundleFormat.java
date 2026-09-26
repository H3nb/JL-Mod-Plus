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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Versioned, transparent public diagnostic bundle format. */
final class DiagnosticBundleFormat {
	static final int FORMAT_VERSION = 1;
	static final String REPORT_ENTRY = "report.md";
	static final String INCIDENT_ENTRY = "incident.json";
	static final String ANR_ENTRY = "evidence/anr-trace.txt";
	static final String TOMBSTONE_ENTRY = "evidence/tombstone-summary.txt";
	static final int MAX_TEXT_ENTRY_BYTES = 256 * 1024;

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

	static String incidentJson(IncidentSummary incident) {
		return incidentJson(incident, null);
	}

	static String incidentJson(IncidentSummary incident, NativeTombstoneSummary.Summary nativeSummary) {
		boolean hasExit = incident.associatedProcessExit != null;
		boolean hasNative = nativeSummary != null;
		StringBuilder json = new StringBuilder(1024);
		json.append("{\n");
		field(json, "formatVersion", Integer.toString(FORMAT_VERSION), false, true);
		field(json, "category", incident.category.name(), true, true);
		field(json, "timestampMillis", Long.toString(incident.incidentTimestampMillis), false, true);
		field(json, "subject", incident.subject, true, true);
		field(json, "failureType", incident.rootFailure == null ? null : incident.rootFailure.type, true, true);
		field(json, "failureMessage", incident.rootFailure == null ? null : incident.rootFailure.message, true, true);
		field(json, "operation", incident.operation, true, true);
		field(json, "lifecycleStage", incident.lifecycleStage, true, true);
		field(json, "topRelevantFrame", incident.topRelevantFrame, true, true);
		field(json, "midletVersion", incident.midletVersion, true, true);
		field(json, "entrypoint", incident.entrypoint, true, true);
		field(json, "jarFingerprint", incident.jarFingerprint, true, true);
		field(json, "build", incident.build, true, true);
		field(json, "environment", incident.environment, true, true);
		field(json, "process", incident.process, true, true);
		field(json, "fingerprint", incident.fingerprint, true, hasExit || hasNative);
		if (hasExit) {
			IncidentSummary.ProcessExitEvidence exit = incident.associatedProcessExit;
			json.append("  \"associatedProcessExit\": {\n");
			field(json, "reason", Integer.toString(exit.reason), false, true, 4);
			field(json, "status", Integer.toString(exit.status), false, true, 4);
			field(json, "reasonLabel", exit.reasonLabel, true, true, 4);
			field(json, "statusLabel", exit.statusLabel, true, true, 4);
			field(json, "importance", exit.importance, true, true, 4);
			field(json, "cause", exit.cause, true, true, 4);
			field(json, "processName", exit.processName, true, true, 4);
			field(json, "processRole", exit.processRole, true, false, 4);
			json.append("  }").append(hasNative ? ',' : ' ').append('\n');
		}
		if (hasNative) {
			json.append("  \"nativeCrash\": {\n");
			field(json, "signalNumber", number(nativeSummary.signalNumber), false, true, 4);
			field(json, "signalName", nativeSummary.signalName, true, true, 4);
			field(json, "signalCode", number(nativeSummary.signalCode), false, true, 4);
			field(json, "signalCodeName", nativeSummary.signalCodeName, true, true, 4);
			field(json, "cause", nativeSummary.cause, true, true, 4);
			field(json, "crashingThread", nativeSummary.threadName, true, true, 4);
			field(json, "topProjectFrame", topProjectFrame(nativeSummary), true, false, 4);
			json.append("  }\n");
		}
		json.append("}\n");
		return json.toString();
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
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_TEXT_ENTRY_BYTES) {
			throw new IOException("Diagnostic bundle entry exceeds retention bound: " + name);
		}
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(0L);
		zip.putNextEntry(entry);
		zip.write(bytes);
		zip.closeEntry();
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
					if (ch < 0x20) {
						escaped.append(String.format(java.util.Locale.US, "\\u%04x", (int) ch));
					} else {
						escaped.append(ch);
					}
				}
			}
		}
		return escaped.toString();
	}
}
