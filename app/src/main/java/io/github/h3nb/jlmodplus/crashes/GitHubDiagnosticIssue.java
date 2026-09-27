/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import java.util.List;
import java.util.Locale;

/** Canonical-English developer-facing presentation of one interpreted incident. */
final class GitHubDiagnosticIssue {
	private static final int MAX_BREADCRUMBS = 5;

	private GitHubDiagnosticIssue() {}

	static String title(IncidentSummary incident, NativeTombstoneSummary.Summary nativeSummary) {
		String subject = value(incident.subject, defaultSubject(incident.category));
		String failure = javaFailureLabel(incident.primaryFailure);
		return switch (incident.category) {
			case MIDLET_LIFECYCLE -> "[MIDlet lifecycle] " + subject + " — "
					+ failure + operationSuffix(incident);
			case MIDLET_CRASH -> "[MIDlet crash] " + subject + " — "
					+ failure + frameSuffix(incident.topRelevantFrame);
			case JL_MOD_PLUS -> "[JL-Mod Plus] " + failure + frameSuffix(incident.topRelevantFrame);
			case NATIVE_CRASH -> "[Native crash] " + subject + " — "
					+ nativeFailureLabel(incident, nativeSummary) + nativeFrameSuffix(nativeSummary);
			case ANR -> "[ANR] " + subject + " — Android reported an unresponsive process";
			case PROCESS_EXIT -> "[Process exit] " + subject + " — "
					+ processExitTitle(incident.associatedProcessExit);
		};
	}

	static String compactSummary(IncidentSummary incident, NativeTombstoneSummary.Summary nativeSummary,
			String bundleFileName) {
		StringBuilder text = new StringBuilder();
		text.append("### Summary\n");
		text.append(description(incident, nativeSummary)).append("\n\n");

		appendBullet(text, "Primary failure", javaFailureDetail(incident.primaryFailure));
		appendBullet(text, "Underlying cause", javaFailureDetail(incident.underlyingCause));
		appendBullet(text, "Failure origin", failureOrigin(incident.failureOrigin));
		appendBullet(text, isMidletSubject(incident) ? "MIDlet" : "Subject", incident.subject);
		appendBullet(text, "MIDlet version", incident.midletVersion);
		appendBullet(text, "Entrypoint", incident.entrypoint);
		appendBullet(text, "Operation", incident.operation);
		appendBullet(text, "Lifecycle boundary", incident.boundary);
		appendBullet(text, "Lifecycle stage", incident.lifecycleStage);
		appendBullet(text, "Build", incident.build);
		appendBullet(text, "Android", incident.androidLabel());
		appendBullet(text, "Device", incident.device);
		appendBullet(text, "Process", incident.process);
		if (incident.associatedProcessExit != null) {
			text.append("\n### Associated process termination\n");
			appendBullet(text, "Termination", incident.associatedProcessExit.summary);
			appendBullet(text, "Importance", incident.associatedProcessExit.importance);
			appendBullet(text, "OS limitation", incident.associatedProcessExit.limitation);
		}
		appendBullet(text, "Diagnostic fingerprint", incident.fingerprint);
		appendBullet(text, "Diagnostic bundle", bundleFileName);
		appendBreadcrumbs(text, incident.breadcrumbs, incident.incidentTimestampMillis);
		return text.toString().trim();
	}

	static String reportMarkdown(IncidentSummary incident, NativeTombstoneSummary.Summary nativeSummary,
			String bundleFileName, String javaStackTrace) {
		StringBuilder text = new StringBuilder();
		text.append("# JL-Mod Plus Diagnostic Report\n\n")
				.append(compactSummary(incident, nativeSummary, bundleFileName))
				.append("\n");

		if (incident.eventId != null || incident.sessionId != null) {
			text.append("\n## Correlation\n");
			appendBullet(text, "Event ID", incident.eventId);
			appendBullet(text, "Session ID", incident.sessionId);
		}
		IncidentSummary.ProcessExitEvidence exit = incident.associatedProcessExit;
		if (exit != null) {
			text.append("\n## OS Process Exit Evidence\n");
			appendBullet(text, "Reason code", Integer.toString(exit.reason));
			appendBullet(text, "Reason", exit.reasonLabel);
			appendBullet(text, "Status", exit.statusLabel);
			appendBullet(text, "Importance", exit.importance);
			appendBullet(text, "Process", processLabel(exit));
			appendBullet(text, "System description", exit.description);
			appendMemory(text, exit);
			if (exit.anrType >= 0) {
				appendBullet(text, "ANR type", Integer.toString(exit.anrType));
				appendBullet(text, "ANR event ID", exit.anrId >= 0 ? Integer.toString(exit.anrId) : null);
				appendBullet(text, "ANR timeout", exit.anrTimeoutMillis >= 0
						? exit.anrTimeoutMillis + " ms" : null);
				appendBullet(text, "ANR user perceptible", exit.anrUserPerceptible == null
						? null : Boolean.toString(exit.anrUserPerceptible));
			}
			appendBullet(text, "Trace kind", exit.traceKind);
			appendBullet(text, "Trace truncated", exit.traceAvailable
					? Boolean.toString(exit.traceTruncated) : null);
		}
		if (nativeSummary != null) {
			String nativeText = NativeTombstoneSummary.format(nativeSummary);
			if (nativeText != null) {
				text.append("\n## Native Crash Evidence\n\n")
						.append(nativeText.trim()).append("\n");
			}
		}
		if (javaStackTrace != null && !javaStackTrace.trim().isEmpty()) {
			text.append("\n## Java Stack Trace\n\n```text\n")
					.append(javaStackTrace.trim())
					.append("\n```\n");
		}
		if (!incident.limitations.isEmpty()) {
			text.append("\n## Evidence Limitations\n");
			for (String limitation : incident.limitations) {
				text.append("- ").append(limitation).append('\n');
			}
		}
		return text.toString().trim() + "\n";
	}

	static String description(IncidentSummary incident, NativeTombstoneSummary.Summary nativeSummary) {
		String subject = value(incident.subject, defaultSubject(incident.category));
		switch (incident.category) {
			case MIDLET_LIFECYCLE -> {
				String failure = javaFailureLabel(incident.primaryFailure);
				if (incident.operation != null && incident.operation.endsWith("()")) {
					String context = "destroyApp()".equals(incident.operation)
							? " during MIDlet teardown" : " during MIDlet lifecycle handling";
					return failure + " occurred while " + subject + "'s "
							+ incident.operation + " callback was running" + context + ".";
				}
				if (incident.operation != null) {
					return failure + " occurred during " + incident.operation + " for " + subject + ".";
				}
				return "JL-Mod Plus recorded an unexpected MIDlet lifecycle failure for " + subject + ".";
			}
			case MIDLET_CRASH -> {
				String frame = callSite(incident.topRelevantFrame);
				return javaFailureLabel(incident.primaryFailure) + " occurred in the MIDlet"
						+ (frame == null ? "." : " at " + frame + ".");
			}
			case JL_MOD_PLUS -> {
				String frame = callSite(incident.topRelevantFrame);
				return "JL-Mod Plus recorded " + javaFailureLabel(incident.primaryFailure)
						+ (frame == null ? "." : " at " + frame + ".");
			}
			case NATIVE_CRASH -> {
				StringBuilder result = new StringBuilder("Android reported a native crash for ")
						.append(subject);
				String signal = nativeSignal(nativeSummary);
				if (signal != null) result.append(" with ").append(signal);
				result.append('.');
				if (nativeSummary != null && nativeSummary.threadName != null) {
					result.append(" The crashing thread was ")
							.append(nativeSummary.threadName).append('.');
				}
				String frame = topProjectFrame(nativeSummary);
				if (frame != null) {
					result.append(" The first JL-Mod Plus frame in the captured backtrace was ")
							.append(callSite(frame)).append('.');
				}
				return result.toString();
			}
			case ANR -> {
				return "Android reported an ANR for " + subject
						+ "; retained trace evidence is included when available.";
			}
			case PROCESS_EXIT -> {
				IncidentSummary.ProcessExitEvidence exit = incident.associatedProcessExit;
				return exit == null || exit.summary == null
						? "JL-Mod Plus recorded an unexpected process exit for " + subject + "."
						: exit.summary;
			}
		}
		throw new AssertionError(incident.category);
	}

	private static String javaFailureDetail(IncidentSummary.JavaFailure failure) {
		if (failure == null) return null;
		String type = javaFailureLabel(failure);
		return failure.message == null ? type : type + ": " + failure.message;
	}

	private static String javaFailureLabel(IncidentSummary.JavaFailure failure) {
		if (failure == null) return "Java failure";
		return value(failure.simpleType(), "Java failure");
	}

	private static String failureOrigin(IncidentSummary.FailureOrigin origin) {
		return switch (origin) {
			case MIDLET -> "MIDlet/guest frame";
			case JL_MOD_PLUS -> "JL-Mod Plus/emulator frame";
			case UNKNOWN -> null;
		};
	}

	private static String operationSuffix(IncidentSummary incident) {
		return incident.operation == null ? frameSuffix(incident.topRelevantFrame)
				: " in " + incident.operation;
	}

	private static String frameSuffix(String frame) {
		String call = callSite(frame);
		return call == null ? "" : " in " + call;
	}

	private static String nativeFrameSuffix(NativeTombstoneSummary.Summary summary) {
		String frame = topProjectFrame(summary);
		return frame == null ? "" : " in " + callSite(frame);
	}

	private static String nativeFailureLabel(IncidentSummary incident,
			NativeTombstoneSummary.Summary summary) {
		String signal = nativeSignal(summary);
		if (signal != null) return signal;
		IncidentSummary.ProcessExitEvidence exit = incident.associatedProcessExit;
		return exit == null ? "native crash" : value(exit.statusLabel, "native crash");
	}

	private static String nativeSignal(NativeTombstoneSummary.Summary summary) {
		if (summary == null) return null;
		String signal = summary.signalName;
		if (signal == null && summary.signalNumber != Integer.MIN_VALUE) {
			signal = Integer.toString(summary.signalNumber);
		}
		if (signal == null) return null;
		return summary.signalCodeName == null ? signal : signal + " (" + summary.signalCodeName + ")";
	}

	private static String topProjectFrame(NativeTombstoneSummary.Summary summary) {
		if (summary == null) return null;
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

	private static String processExitTitle(IncidentSummary.ProcessExitEvidence exit) {
		if (exit == null) return "process exit";
		if (exit.statusLabel != null && (exit.reason == ProcessExitStore.REASON_SIGNALED
				|| exit.reason == ProcessExitStore.REASON_CRASH_NATIVE)) {
			return exit.statusLabel;
		}
		return value(exit.reasonLabel, "process exit");
	}

	private static String processLabel(IncidentSummary.ProcessExitEvidence exit) {
		if (exit == null) return null;
		if (exit.processRole == null) return exit.processName;
		if (exit.processName == null) return exit.processRole;
		return exit.processRole + " · " + exit.processName;
	}

	private static void appendMemory(StringBuilder text, IncidentSummary.ProcessExitEvidence exit) {
		if (exit.pssKb <= 0 && exit.rssKb <= 0) return;
		StringBuilder sample = new StringBuilder();
		if (exit.pssKb > 0) sample.append("PSS ").append(exit.pssKb).append(" kB");
		if (exit.rssKb > 0) {
			if (sample.length() > 0) sample.append(" · ");
			sample.append("RSS ").append(exit.rssKb).append(" kB");
		}
		appendBullet(text, "Memory sample", sample.toString());
	}

	private static void appendBreadcrumbs(StringBuilder text, List<IncidentSummary.Breadcrumb> breadcrumbs,
			long incidentTime) {
		if (breadcrumbs == null || breadcrumbs.isEmpty()) return;
		int start = Math.max(0, breadcrumbs.size() - MAX_BREADCRUMBS);
		text.append("\n### Recent app context\n");
		for (int i = start; i < breadcrumbs.size(); i++) {
			IncidentSummary.Breadcrumb item = breadcrumbs.get(i);
			text.append("- ");
			String age = relativeAge(incidentTime, item.wallTimeMillis);
			if (age != null) text.append(age).append(" · ");
			text.append(value(item.location, "unknown"));
			if (item.action != null) text.append(" · ").append(item.action);
			if (item.phase != null) text.append(" · ").append(item.phase);
			text.append('\n');
		}
	}

	private static String relativeAge(long incidentTime, long value) {
		if (incidentTime <= 0 || value <= 0 || incidentTime < value) return null;
		long delta = incidentTime - value;
		if (delta < 1000) return delta + " ms before failure";
		if (delta < 60_000) return String.format(Locale.US, "%.1f s before failure", delta / 1000.0);
		return (delta / 60_000) + " min before failure";
	}

	private static void appendBullet(StringBuilder text, String label, String value) {
		if (value != null && !value.isEmpty()) {
			text.append("- **").append(label).append(":** ").append(value).append('\n');
		}
	}

	private static String callSite(String frame) {
		String cleaned = IncidentSummary.clean(frame);
		if (cleaned == null) return null;
		int paren = cleaned.indexOf('(');
		String method = paren < 0 ? cleaned : cleaned.substring(0, paren);
		int methodDot = method.lastIndexOf('.');
		if (methodDot < 0) return method.endsWith("()") ? method : method + "()";
		int classDot = method.lastIndexOf('.', methodDot - 1);
		String shortName = classDot < 0 ? method : method.substring(classDot + 1);
		return shortName + "()";
	}

	private static boolean isMidletSubject(IncidentSummary incident) {
		return incident.category == IncidentSummary.Category.MIDLET_LIFECYCLE
				|| incident.category == IncidentSummary.Category.MIDLET_CRASH
				|| incident.entrypoint != null;
	}

	private static String defaultSubject(IncidentSummary.Category category) {
		return switch (category) {
			case JL_MOD_PLUS -> "JL-Mod Plus";
			case MIDLET_LIFECYCLE, MIDLET_CRASH -> "MIDlet";
			case NATIVE_CRASH, ANR, PROCESS_EXIT -> "process";
		};
	}

	private static String value(String value, String fallback) {
		return value == null || value.isEmpty() ? fallback : value;
	}
}
