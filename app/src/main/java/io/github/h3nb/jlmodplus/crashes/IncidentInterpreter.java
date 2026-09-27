/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import android.system.OsConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministic, offline interpretation of correlated diagnostic evidence. */
final class IncidentInterpreter {
	private IncidentInterpreter() {}

	static IncidentSummary interpret(MidletSessionJournal.Snapshot session,
			JavaDiagnosticStore.Snapshot java, ProcessExitStore.Snapshot exit) {
		IncidentSummary.JavaFailure primary = failure(java == null ? null : java.primaryThrowable());
		IncidentSummary.JavaFailure underlying =
				failure(java == null ? null : java.underlyingCause());
		String entrypoint = first(
				session == null ? null : session.mainClass,
				java == null ? null : java.midletMainClass);
		IncidentSummary.FailureOrigin origin = failureOrigin(
				java == null ? null : java.primaryThrowable(), entrypoint);
		boolean lifecycle = session != null && session.failureBoundary != null
				&& session.failureBoundary.name().startsWith("LIFECYCLE_");
		IncidentSummary.Category category;
		if (lifecycle) {
			category = IncidentSummary.Category.MIDLET_LIFECYCLE;
		} else if (java != null) {
			category = switch (origin) {
				case MIDLET -> IncidentSummary.Category.MIDLET_CRASH;
				case JL_MOD_PLUS -> IncidentSummary.Category.JL_MOD_PLUS;
				case UNKNOWN -> IncidentSummary.Category.JAVA_FAILURE;
			};
		} else if (exit != null && exit.reason == ProcessExitStore.REASON_CRASH_NATIVE) {
			category = IncidentSummary.Category.NATIVE_CRASH;
		} else if (exit != null && exit.reason == ProcessExitStore.REASON_ANR) {
			category = IncidentSummary.Category.ANR;
		} else {
			category = IncidentSummary.Category.PROCESS_EXIT;
		}

		String operation = lifecycleOperation(session == null ? null : session.failureBoundary);
		String boundary = session != null && session.failureBoundary != null
				? session.failureBoundary.name()
				: (java == null ? null : java.legacyBoundary);
		String stage = session == null || session.stage == null ? null : session.stage.name();
		String eventId = session == null ? (java == null ? null : java.legacyEventId)
				: session.failureEventId;
		String sessionId = session == null ? (java == null ? null : java.sessionId) : session.sessionId;
		String subject = first(
				session == null ? null : session.midletName,
				java == null ? null : java.midletName,
				exit == null ? null : exit.processRole,
				exit == null ? null : exit.processName,
				category == IncidentSummary.Category.JL_MOD_PLUS ? "JL-Mod Plus"
						: category == IncidentSummary.Category.JAVA_FAILURE ? "Java failure" : "process");
		String midletVersion = first(
				session == null ? null : session.midletVersion,
				java == null ? null : java.midletVersion);
		String jar = IncidentSummary.shortFingerprint(first(
				session == null ? null : session.jarSha256,
				java == null ? null : java.jarSha256));
		CrashContextStore.Snapshot appContext = java != null && java.appContext != null
				? java.appContext : (exit == null ? null : exit.appContext);
		String build = buildLabel(appContext, java == null ? null : java.appVersion);
		String androidRelease = java == null ? null : java.androidRelease;
		int androidSdk = java == null ? -1 : java.androidSdk;
		if (exit != null) {
			if (androidRelease == null) androidRelease = exit.androidRelease;
			if (androidSdk < 0) androidSdk = exit.stateSdk;
		}
		String device = joinDevice(
				java == null ? null : java.brand,
				java == null ? null : java.model);
		if (device == null && exit != null) device = joinDevice(exit.deviceBrand, exit.deviceModel);
		String abi = first(java == null ? null : java.primaryAbi,
				exit == null ? null : exit.primaryAbi);
		String process = processLabel(java, exit);
		String topFrame = primary == null ? null : primary.frame;
		IncidentSummary.ProcessExitEvidence processExit = processExitEvidence(exit);
		ArrayList<String> limitations = new ArrayList<>();
		if (processExit != null && processExit.limitation != null) {
			limitations.add(processExit.limitation);
		}
		if (exit != null && exit.reason == ProcessExitStore.REASON_CRASH_NATIVE
				&& !processExit.traceAvailable) {
			limitations.add("Android reported a native crash, but no retained tombstone was available.");
		}
		if (exit != null && exit.reason == ProcessExitStore.REASON_ANR
				&& !processExit.traceAvailable) {
			limitations.add("Android reported an ANR, but no retained text trace was available.");
		}

		return new IncidentSummary(
				category,
				origin,
				incidentTimestamp(session, java, exit),
				subject,
				primary,
				underlying,
				java == null ? null : java.kind.name(),
				operation,
				boundary,
				stage,
				topFrame,
				midletVersion,
				entrypoint,
				jar,
				build,
				androidRelease,
				androidSdk,
				device,
				abi,
				process,
				eventId,
				sessionId,
				breadcrumbs(appContext),
				processExit,
				limitations);
	}

	private static IncidentSummary.JavaFailure failure(JavaDiagnosticStore.ThrowableData value) {
		if (value == null) return null;
		JavaDiagnosticStore.FrameData frame = value.firstFrame();
		return new IncidentSummary.JavaFailure(
				value.className,
				value.message,
				frame == null ? null : frame.display());
	}

	static IncidentSummary.FailureOrigin failureOrigin(
			JavaDiagnosticStore.ThrowableData primary, String entrypoint) {
		if (primary == null || primary.firstFrame() == null) {
			return IncidentSummary.FailureOrigin.UNKNOWN;
		}
		String frameClass = primary.firstFrame().className;
		if (frameClass == null) return IncidentSummary.FailureOrigin.UNKNOWN;
		if (frameClass.startsWith("io.github.h3nb.jlmodplus.")
				|| frameClass.startsWith("ru.playsoftware.j2meloader.")
				|| frameClass.startsWith("javax.microedition.")) {
			return IncidentSummary.FailureOrigin.JL_MOD_PLUS;
		}
		if (entrypoint != null
				&& (frameClass.equals(entrypoint) || frameClass.startsWith(entrypoint + "$"))) {
			return IncidentSummary.FailureOrigin.MIDLET;
		}
		return IncidentSummary.FailureOrigin.UNKNOWN;
	}

	private static long incidentTimestamp(MidletSessionJournal.Snapshot session,
			JavaDiagnosticStore.Snapshot java, ProcessExitStore.Snapshot exit) {
		// Use the timestamp of the evidence that defines the incident. A later associated exit must
		// not rename a Java incident, while a process-exit-only record must use the OS/fallback exit
		// observation rather than an older session-journal update.
		if (java != null) return java.timestampMillis;
		if (exit != null) return exit.timestampMillis;
		return session == null ? 0 : session.updatedWallTimeMillis;
	}

	static String lifecycleOperation(MidletSessionJournal.FailureBoundary boundary) {
		if (boundary == null) return null;
		return switch (boundary) {
			case LIFECYCLE_START -> "startApp()";
			case LIFECYCLE_PAUSE -> "pauseApp()";
			case LIFECYCLE_DESTROY -> "destroyApp()";
			case LIFECYCLE_INIT -> "MIDlet initialization";
			case MIDLET_THREAD, UNCAUGHT_THREAD -> null;
		};
	}

	private static IncidentSummary.ProcessExitEvidence processExitEvidence(
			ProcessExitStore.Snapshot exit) {
		if (exit == null) return null;
		String status = ProcessExitStore.statusLabel(exit);
		String summary = processExitSummary(exit, status);
		String limitation = processExitLimitation(exit);
		return new IncidentSummary.ProcessExitEvidence(
				exit.source,
				exit.reason,
				exit.status,
				ProcessExitStore.reasonLabel(exit.reason),
				status,
				ProcessExitStore.importanceLabel(exit.importance),
				exit.processName,
				exit.processRole,
				exit.description,
				exit.stateSdk,
				exit.androidRelease,
				joinDevice(exit.deviceBrand, exit.deviceModel),
				exit.primaryAbi,
				exit.pssKb,
				exit.rssKb,
				exit.lowMemoryKillReportSupported,
				exit.traceKind,
				exit.traceFile != null && exit.traceBytes > 0,
				exit.traceTruncated,
				exit.anrType,
				exit.anrTimeoutMillis,
				exit.anrId,
				exit.anrUserPerceptible,
				summary,
				limitation);
	}

	static String processExitSummary(ProcessExitStore.Snapshot exit, String status) {
		if (exit == null) return null;
		return switch (exit.reason) {
			case ProcessExitStore.REASON_LOW_MEMORY -> "Android reported a low-memory kill.";
			case ProcessExitStore.REASON_ANR -> "Android reported an ANR.";
			case ProcessExitStore.REASON_CRASH_NATIVE -> "Android reported a native crash"
					+ signalSuffix(status) + ".";
			case ProcessExitStore.REASON_CRASH -> "Android reported a Java process crash.";
			case ProcessExitStore.REASON_SIGNALED -> "Android recorded "
					+ first(status, "signal " + exit.status) + ".";
			case ProcessExitStore.REASON_UNKNOWN -> "The process ended unexpectedly.";
			default -> "Android reported process-exit reason " + exit.reason + ".";
		};
	}

	static String processExitLimitation(ProcessExitStore.Snapshot exit) {
		if (exit == null) return null;
		if (exit.reason == ProcessExitStore.REASON_UNKNOWN
				&& exit.stateSdk >= 23 && exit.stateSdk < 30) {
			return "Exact OS termination reason is unavailable on Android API "
					+ exit.stateSdk + ".";
		}
		if (exit.reason == ProcessExitStore.REASON_SIGNALED && exit.status == OsConstants.SIGKILL) {
			if (exit.lowMemoryKillReportSupported) {
				return "Low-memory kills are separately reportable on this device; Android did not classify this exit as one.";
			}
			return "This device cannot reliably distinguish a low-memory kill from other SIGKILL terminations.";
		}
		if (exit.reason == ProcessExitStore.REASON_UNKNOWN) {
			return "Android did not provide a more specific termination reason.";
		}
		return null;
	}

	private static String signalSuffix(String status) {
		return status == null || status.isEmpty() || "0".equals(status) ? "" : " with " + status;
	}

	private static List<IncidentSummary.Breadcrumb> breadcrumbs(CrashContextStore.Snapshot context) {
		if (context == null || context.breadcrumbs.isEmpty()) return Collections.emptyList();
		ArrayList<IncidentSummary.Breadcrumb> result = new ArrayList<>(context.breadcrumbs.size());
		for (CrashContextStore.Breadcrumb item : context.breadcrumbs) {
			result.add(new IncidentSummary.Breadcrumb(
					item.wallTimeMillis, item.location, item.action, item.phase));
		}
		return result;
	}

	private static String buildLabel(CrashContextStore.Snapshot context, String fallbackVersion) {
		if (context != null) {
			String commit = IncidentSummary.shortFingerprint(context.buildCommit);
			if (commit != null || context.buildVariant != null) {
				StringBuilder value = new StringBuilder();
				if (commit != null) value.append(commit);
				if (context.buildVariant != null) {
					if (value.length() > 0) value.append(" · ");
					value.append(context.buildVariant);
				}
				return value.toString();
			}
		}
		return fallbackVersion;
	}

	private static String processLabel(JavaDiagnosticStore.Snapshot java, ProcessExitStore.Snapshot exit) {
		String role = first(java == null ? null : java.processRole,
				exit == null ? null : exit.processRole);
		String name = first(java == null ? null : java.processName,
				exit == null ? null : exit.processName);
		if (role == null) return name;
		if (name == null || name.equals(role)) return role;
		return role + " · " + name;
	}

	private static String joinDevice(String brand, String model) {
		if (brand == null) return model;
		if (model == null) return brand;
		return brand + " " + model;
	}

	private static String first(String... values) {
		for (String value : values) {
			if (value != null && !value.trim().isEmpty()) return value;
		}
		return null;
	}
}
