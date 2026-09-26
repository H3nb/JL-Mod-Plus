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

import java.util.List;
import java.util.Locale;

/** Canonical-English developer-facing presentation of a structured incident. */
final class GitHubDiagnosticIssue {
	private static final int MAX_BREADCRUMBS = 5;

	private GitHubDiagnosticIssue() {}

	static String title(IncidentSummary incident, NativeTombstoneSummary.Summary nativeSummary) {
		String subject = value(incident.subject, defaultSubject(incident.category));
		return switch (incident.category) {
			case MIDLET_LIFECYCLE -> "[MIDlet lifecycle] " + subject + " — "
					+ javaFailureLabel(incident.rootFailure) + operationSuffix(incident);
			case MIDLET_CRASH -> "[MIDlet crash] " + subject + " — "
					+ javaFailureLabel(incident.rootFailure) + frameSuffix(incident.topRelevantFrame);
			case JL_MOD_PLUS -> "[JL-Mod Plus] " + javaFailureLabel(incident.rootFailure)
					+ frameSuffix(incident.topRelevantFrame);
			case NATIVE_CRASH -> "[Native crash] " + subject + " — "
					+ nativeFailureLabel(incident, nativeSummary)
					+ nativeFrameSuffix(nativeSummary);
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

		appendBullet(text, "Primary failure", primaryFailure(incident, nativeSummary));
		appendBullet(text, "MIDlet", incident.subject);
		appendBullet(text, "MIDlet version", incident.midletVersion);
		appendBullet(text, "Entrypoint", incident.entrypoint);
		appendBullet(text, "Failure operation", incident.operation);
		appendBullet(text, "Lifecycle stage", incident.lifecycleStage);
		appendBullet(text, "Relevant frame", incident.topRelevantFrame);
		appendBullet(text, "Build", incident.build);
		appendBullet(text, "Android/device", incident.environment);
		appendBullet(text, "Process", incident.process);
		appendBullet(text, "Diagnostic fingerprint", incident.fingerprint);
		appendBullet(text, "Diagnostic bundle", bundleFileName);

		if (incident.associatedProcessExit != null
				&& incident.category != IncidentSummary.Category.PROCESS_EXIT
				&& incident.category != IncidentSummary.Category.NATIVE_CRASH
				&& incident.category != IncidentSummary.Category.ANR) {
			IncidentSummary.ProcessExitEvidence exit = incident.associatedProcessExit;
			text.append("\n### Associated process-exit evidence\n");
			appendBullet(text, "Exit", processExitTitle(exit));
			appendBullet(text, "Process", processLabel(exit));
			appendBullet(text, "Cause", exit.cause);
		}

		appendBreadcrumbs(text, incident.breadcrumbs, incident.incidentTimestampMillis);
		return text.toString().trim();
	}

	static String reportMarkdown(IncidentSummary incident, NativeTombstoneSummary.Summary nativeSummary,
			String bundleFileName, String javaStackTrace) {
		StringBuilder text = new StringBuilder();
		text.append("# JL-Mod Plus Diagnostic Report\n\n");
		text.append(compactSummary(incident, nativeSummary, bundleFileName)).append("\n");
		if (incident.associatedProcessExit != null
				&& (incident.category == IncidentSummary.Category.PROCESS_EXIT
				|| incident.category == IncidentSummary.Category.NATIVE_CRASH
				|| incident.category == IncidentSummary.Category.ANR)) {
			IncidentSummary.ProcessExitEvidence exit = incident.associatedProcessExit;
			text.append("\n## Process Exit Evidence\n");
			appendBullet(text, "Exit", processExitTitle(exit));
			appendBullet(text, "Process", processLabel(exit));
			appendBullet(text, "Cause", exit.cause);
			appendBullet(text, "Description", exit.description);
		}
		if (nativeSummary != null) {
			String nativeText = NativeTombstoneSummary.format(nativeSummary);
			if (nativeText != null) {
				text.append("\n## Native Crash Evidence\n\n").append(nativeText.trim()).append("\n");
			}
		}
		if (javaStackTrace != null && !javaStackTrace.trim().isEmpty()) {
			text.append("\n## Java Stack Trace\n\n```text\n")
					.append(javaStackTrace.trim())
					.append("\n```\n");
		}
		return text.toString().trim() + "\n";
	}

	static String description(IncidentSummary incident, NativeTombstoneSummary.Summary nativeSummary) {
		String subject = value(incident.subject, defaultSubject(incident.category));
		switch (incident.category) {
			case MIDLET_LIFECYCLE -> {
				if (incident.rootFailure != null) {
					String frame = callSite(incident.rootFailure.frame);
					if (frame != null) {
						return "The MIDlet threw " + javaFailureLabel(incident.rootFailure)
								+ " while " + frame + " was being called during teardown.";
					}
					if (incident.operation != null) {
						return "The MIDlet threw " + javaFailureLabel(incident.rootFailure)
								+ " while " + incident.operation + " was being called.";
					}
				}
				return "JL-Mod Plus recorded an unexpected MIDlet lifecycle failure for " + subject + ".";
			}
			case MIDLET_CRASH -> {
				if (incident.rootFailure != null) {
					String frame = callSite(incident.topRelevantFrame);
					return "The MIDlet threw " + javaFailureLabel(incident.rootFailure)
							+ (frame == null ? "." : " in " + frame + ".");
				}
				return "JL-Mod Plus recorded an unexpected MIDlet failure for " + subject + ".";
			}
			case JL_MOD_PLUS -> {
				if (incident.rootFailure != null) {
					String frame = callSite(incident.topRelevantFrame);
					return "JL-Mod Plus threw " + javaFailureLabel(incident.rootFailure)
							+ (frame == null ? "." : " in " + frame + ".");
				}
				return "JL-Mod Plus recorded a Java diagnostic failure.";
			}
			case NATIVE_CRASH -> {
				String signal = nativeSignal(nativeSummary);
				StringBuilder result = new StringBuilder("The ")
						.append(isMidletSubject(incident) ? "MIDlet" : "JL-Mod Plus")
						.append(" process crashed");
				if (signal != null) result.append(" with ").append(signal);
				result.append('.');
				if (nativeSummary != null && nativeSummary.threadName != null) {
					result.append(" The crashing thread was ").append(nativeSummary.threadName).append('.');
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
						+ "; a retained trace is included in the diagnostic bundle when available.";
			}
			case PROCESS_EXIT -> {
				IncidentSummary.ProcessExitEvidence exit = incident.associatedProcessExit;
				return "Android recorded " + processExitTitle(exit) + " for " + subject + ".";
			}
		}
		throw new AssertionError(incident.category);
	}

	private static String primaryFailure(IncidentSummary incident,
			NativeTombstoneSummary.Summary nativeSummary) {
		if (incident.rootFailure != null) return javaFailureDetail(incident.rootFailure);
		if (incident.category == IncidentSummary.Category.NATIVE_CRASH) {
			return nativeFailureLabel(incident, nativeSummary);
		}
		if (incident.category == IncidentSummary.Category.ANR) return "ANR";
		return processExitTitle(incident.associatedProcessExit);
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

	private static String operationSuffix(IncidentSummary incident) {
		if (incident.operation != null) return " in " + incident.operation;
		return frameSuffix(incident.topRelevantFrame);
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
		return exit == null ? "native crash" : value(exit.statusLabel, value(exit.reasonLabel, "native crash"));
	}

	private static String nativeSignal(NativeTombstoneSummary.Summary summary) {
		if (summary == null) return null;
		String signal = summary.signalName;
		if (signal == null && summary.signalNumber != Integer.MIN_VALUE) {
			signal = Integer.toString(summary.signalNumber);
		}
		if (signal == null) return null;
		if (summary.signalCodeName != null) return signal + " (" + summary.signalCodeName + ")";
		return signal;
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
		String mechanism = value(exit.statusLabel, exit.reasonLabel);
		if (mechanism == null) mechanism = "process exit";
		if (exit.importance != null) mechanism += " while " + exit.importance;
		return mechanism;
	}

	private static String processLabel(IncidentSummary.ProcessExitEvidence exit) {
		if (exit == null) return null;
		if (exit.processRole == null) return exit.processName;
		if (exit.processName == null) return exit.processRole;
		return exit.processRole + " · " + exit.processName;
	}

	private static void appendBreadcrumbs(StringBuilder text, List<IncidentSummary.Breadcrumb> breadcrumbs,
			long incidentTime) {
		if (breadcrumbs == null || breadcrumbs.isEmpty()) return;
		int start = Math.max(0, breadcrumbs.size() - MAX_BREADCRUMBS);
		text.append("\n### Recent breadcrumbs\n");
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
		if (methodDot < 0) return method + "()";
		int classDot = method.lastIndexOf('.', methodDot - 1);
		String shortName = classDot < 0 ? method : method.substring(classDot + 1);
		return shortName + "()";
	}

	private static boolean isMidletSubject(IncidentSummary incident) {
		return incident.subject != null && !"JL-Mod Plus".equals(incident.subject);
	}

	private static String defaultSubject(IncidentSummary.Category category) {
		return switch (category) {
			case JL_MOD_PLUS -> "JL-Mod Plus";
			default -> "MIDlet process";
		};
	}

	private static String value(String value, String fallback) {
		return value == null || value.isEmpty() ? fallback : value;
	}
}
