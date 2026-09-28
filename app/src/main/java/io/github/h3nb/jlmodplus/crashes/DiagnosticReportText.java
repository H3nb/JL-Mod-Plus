/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import java.util.List;

/** Human-readable local presentation derived from the same interpretation used for export. */
final class DiagnosticReportText {
	private DiagnosticReportText() {}

	static String build(LocalDiagnosticRepository.Record record) {
		if (record == null || record.getIncidentSummary() == null) return "";
		IncidentSummary incident = record.getIncidentSummary();
		return build(incident, record.getStackTrace());
	}

	static String build(IncidentSummary incident, String stack) {
		if (incident == null) return "";
		StringBuilder text = new StringBuilder("JL-Mod Plus Diagnostic Report\n\n");
		text.append("Summary\n")
				.append(GitHubDiagnosticIssue.description(incident, null)).append("\n");

		appendSection(text, "Primary Failure", failure(incident.primaryFailure));
		appendSection(text, "Underlying Cause", failure(incident.underlyingCause));

		text.append("\nIncident Context\n");
		appendLine(text, isMidletIncident(incident) ? "MIDlet" : "Subject", incident.subject);
		appendLine(text, "MIDlet version", incident.midletVersion);
		appendLine(text, "Entrypoint", incident.entrypoint);
		appendLine(text, "Operation", incident.operation);
		appendLine(text, "Lifecycle boundary", incident.boundary);
		appendLine(text, "Lifecycle stage", incident.lifecycleStage);
		appendLine(text, "Failure origin", origin(incident.failureOrigin));
		appendLine(text, "Event ID", incident.eventId);
		appendLine(text, "Session ID", incident.sessionId);

		text.append("\nEnvironment\n");
		appendLine(text, "Build", incident.build);
		appendLine(text, "Android", incident.androidLabel());
		appendLine(text, "Device", incident.device);
		appendLine(text, "Primary ABI", incident.primaryAbi);
		appendLine(text, "Process", incident.process);

		if (incident.associatedProcessExit != null) {
			IncidentSummary.ProcessExitEvidence exit = incident.associatedProcessExit;
			text.append(exit.controlledByJlMod
					? "\nRuntime Termination\n" : "\nAssociated Process Termination\n");
			appendLine(text, "Termination", exit.summary);
			appendLine(text, "Importance", exit.importance);
			if (!exit.controlledByJlMod) {
				appendLine(text, "OS termination detail", exit.description);
			}
			if (exit.pssKb > 0 || exit.rssKb > 0) {
				StringBuilder memory = new StringBuilder();
				if (exit.pssKb > 0) memory.append("PSS ").append(exit.pssKb).append(" kB");
				if (exit.rssKb > 0) {
					if (memory.length() > 0) memory.append(" · ");
					memory.append("RSS ").append(exit.rssKb).append(" kB");
				}
				appendLine(text, "Memory sample", memory.toString());
			}
			if (exit.anrType >= 0) {
				appendLine(text, "ANR type", Integer.toString(exit.anrType));
				appendLine(text, "ANR event ID", exit.anrId >= 0 ? Integer.toString(exit.anrId) : null);
				appendLine(text, "ANR timeout", exit.anrTimeoutMillis >= 0
						? exit.anrTimeoutMillis + " ms" : null);
				appendLine(text, "ANR user perceptible", exit.anrUserPerceptible == null
						? null : Boolean.toString(exit.anrUserPerceptible));
			}
		} else if (incident.handledMidletSessionFailure()) {
			text.append("\nRuntime Termination\n");
			appendLine(text, "Termination",
					"JL-Mod Plus terminated the isolated MIDlet process after recording "
							+ "the fatal session failure.");
		}

		if (!incident.breadcrumbs.isEmpty()) {
			text.append("\nRecent App Context\n");
			for (IncidentSummary.Breadcrumb item : incident.breadcrumbs) {
				text.append("- ").append(item.location == null ? "unknown" : item.location);
				if (item.action != null) text.append(" · ").append(item.action);
				if (item.phase != null) text.append(" · ").append(item.phase);
				text.append('\n');
			}
		}

		if (!incident.limitations.isEmpty()) {
			text.append("\nEvidence Limitations\n");
			for (String limitation : incident.limitations) text.append("- ").append(limitation).append('\n');
		}

		if (stack != null && !stack.trim().isEmpty()) {
			text.append("\nTechnical Evidence\nJava stack trace:\n")
					.append(stack.trim()).append('\n');
		}
		return text.toString().trim();
	}

	static String buildBatch(List<LocalDiagnosticRepository.Record> records) {
		StringBuilder text = new StringBuilder();
		for (LocalDiagnosticRepository.Record record : records) {
			if (text.length() > 0) text.append("\n\n---\n\n");
			text.append(build(record));
		}
		return text.toString();
	}

	static String withNativeSummary(String report, String nativeSummary) {
		if (nativeSummary == null || nativeSummary.trim().isEmpty()) return report;
		String base = report == null ? "" : report.trim();
		String heading = "\n\nNative Crash Evidence\n";
		return base + heading + nativeSummary.trim();
	}

	private static String failure(IncidentSummary.JavaFailure value) {
		if (value == null) return null;
		String type = value.simpleType();
		if (type == null) type = value.type;
		return value.message == null ? type : type + ": " + value.message;
	}

	private static String origin(IncidentSummary.FailureOrigin value) {
		return switch (value) {
			case MIDLET -> "MIDlet/guest frame";
			case JL_MOD_PLUS -> "JL-Mod Plus/emulator frame";
			case UNKNOWN -> null;
		};
	}

	private static boolean isMidletIncident(IncidentSummary incident) {
		return incident.category == IncidentSummary.Category.MIDLET_LIFECYCLE
				|| incident.category == IncidentSummary.Category.MIDLET_CRASH
				|| incident.entrypoint != null;
	}

	private static void appendSection(StringBuilder text, String title, String value) {
		if (value == null) return;
		text.append("\n").append(title).append("\n").append(value).append("\n");
	}

	private static void appendLine(StringBuilder text, String label, String value) {
		if (value != null && !value.isEmpty()) text.append(label).append(": ").append(value).append('\n');
	}
}
