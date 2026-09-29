/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;


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
		MidletSessionJournal.FailureBoundary effectiveBoundary =
				session != null && session.failureBoundary != null
						? session.failureBoundary
						: storedBoundary(java == null ? null : java.legacyBoundary);
		IncidentSummary.FailureOrigin origin = failureOrigin(
				java == null ? null : java.primaryThrowable(),
				entrypoint,
				java == null ? null : java.kind,
				effectiveBoundary);
		boolean lifecycle = effectiveBoundary != null
				&& effectiveBoundary.name().startsWith("LIFECYCLE_");
		IncidentSummary.Category category;
		if (lifecycle) {
			category = IncidentSummary.Category.MIDLET_LIFECYCLE;
		} else if (exit != null && exit.reason == ProcessExitStore.REASON_CRASH_NATIVE) {
			category = IncidentSummary.Category.NATIVE_CRASH;
		} else if (exit != null && exit.reason == ProcessExitStore.REASON_ANR) {
			category = IncidentSummary.Category.ANR;
		} else if (java != null) {
			category = switch (origin) {
				case MIDLET -> IncidentSummary.Category.MIDLET_CRASH;
				case JL_MOD_PLUS -> IncidentSummary.Category.JL_MOD_PLUS;
				case UNKNOWN -> IncidentSummary.Category.JAVA_FAILURE;
			};
		} else {
			category = IncidentSummary.Category.PROCESS_EXIT;
		}

		String operation = lifecycleOperation(effectiveBoundary);
		String boundary = effectiveBoundary == null
				? (java == null ? null : java.legacyBoundary)
				: effectiveBoundary.name();
		String stage = session == null || session.stage == null ? null : session.stage.name();
		String eventId = session == null ? (java == null ? null : java.legacyEventId)
				: session.failureEventId;
		String sessionId = session == null ? (java == null ? null : java.sessionId) : session.sessionId;
		String subject = first(
				session == null ? null : session.midletName,
				java == null ? null : java.midletName,
				exit == null ? null : processSubject(exit.processRole),
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
		boolean handledMidletFailure = java != null
				&& java.kind == JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE;
		boolean controlledTermination = handledMidletFailure && isControlledTerminationExit(exit);
		IncidentSummary.ProcessExitEvidence processExit =
				processExitEvidence(exit, controlledTermination);
		ArrayList<String> limitations = new ArrayList<>();
		String exitLimitation = controlledTermination ? null : processExitLimitation(exit);
		if (exitLimitation != null) {
			limitations.add(exitLimitation);
		}
		if (session != null
				&& session.outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE
				&& java == null) {
			limitations.add("JL-Mod Plus recorded a terminal MIDlet Java failure, "
					+ "but the corresponding Java evidence was not retained.");
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
			JavaDiagnosticStore.ThrowableData primary,
			String entrypoint,
			JavaDiagnosticStore.Kind kind,
			MidletSessionJournal.FailureBoundary boundary) {
		if (primary != null && primary.firstFrame() != null) {
			String frameClass = primary.firstFrame().className;
			if (frameClass != null) {
				// Proven framework origin wins over session ownership. The fallback below exists
				// only for an uncaught worker already proven to be guest-owned at capture time.
				if (isFrameworkClass(frameClass)) {
					return IncidentSummary.FailureOrigin.JL_MOD_PLUS;
				}
				if (entrypoint != null
						&& (frameClass.equals(entrypoint) || frameClass.startsWith(entrypoint + "$"))) {
					return IncidentSummary.FailureOrigin.MIDLET;
				}
			}
		}
		if (kind == JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE
				&& boundary == MidletSessionJournal.FailureBoundary.UNCAUGHT_THREAD) {
			return IncidentSummary.FailureOrigin.MIDLET;
		}
		return IncidentSummary.FailureOrigin.UNKNOWN;
	}

	static boolean isFrameworkClass(String className) {
		return className != null
				&& (className.startsWith("io.github.h3nb.jlmodplus.")
				|| className.startsWith("ru.playsoftware.j2meloader.")
				|| className.startsWith("javax.microedition."));
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

	private static MidletSessionJournal.FailureBoundary storedBoundary(String value) {
		if (value == null) return null;
		try {
			return MidletSessionJournal.FailureBoundary.valueOf(value);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
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
			ProcessExitStore.Snapshot exit, boolean controlledByJlMod) {
		if (exit == null) return null;
		String status = ProcessExitStore.statusLabel(exit);
		String summary = controlledByJlMod
				? "JL-Mod Plus terminated the isolated MIDlet process after recording "
						+ "the fatal session failure."
				: processExitSummary(exit, status);
		return new IncidentSummary.ProcessExitEvidence(
				exit.source,
				exit.reason,
				exit.status,
				ProcessExitStore.reasonLabel(exit),
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
				controlledByJlMod,
				summary);
	}

	static boolean isControlledTerminationExit(ProcessExitStore.Snapshot exit) {
		return ProcessExitStore.isControlledRuntimeShutdown(exit);
	}

	static String processExitSummary(ProcessExitStore.Snapshot exit, String status) {
		if (exit == null) return null;
		if (ProcessExitStore.isMemoryLimiterTermination(exit.reason, exit.description)) {
			return "Android reported that the process exceeded its system memory limit.";
		}
		return switch (exit.reason) {
			case ProcessExitStore.REASON_LOW_MEMORY -> "Android reported a low-memory kill.";
			case ProcessExitStore.REASON_ANR -> "Android reported an ANR.";
			case ProcessExitStore.REASON_CRASH_NATIVE -> "Android reported a native crash"
					+ signalSuffix(status) + ".";
			case ProcessExitStore.REASON_CRASH -> "Android reported a Java process crash.";
			case ProcessExitStore.REASON_SIGNALED -> "Android recorded "
					+ first(status, "signal " + exit.status) + ".";
			case ProcessExitStore.REASON_INITIALIZATION_FAILURE ->
					"Android reported a process initialization failure.";
			case ProcessExitStore.REASON_EXCESSIVE_RESOURCE_USAGE ->
					"Android reported termination for excessive resource usage.";
			case ProcessExitStore.REASON_DEPENDENCY_DIED ->
					"Android reported that a required process dependency died.";
			case ProcessExitStore.REASON_FREEZER ->
					"Android reported an app-freezer termination.";
			case ProcessExitStore.REASON_EXIT_SELF ->
					"The process exited itself with status " + exit.status + ".";
			case ProcessExitStore.REASON_OTHER ->
					"Android reported another system termination.";
			case ProcessExitStore.REASON_UNKNOWN ->
					"Android recorded an unexpected process termination but did not provide "
							+ "a specific reason.";
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
		if (exit.reason == ProcessExitStore.REASON_SIGNALED && exit.status == ProcessExitStore.SIGNAL_KILL) {
			if (exit.lowMemoryKillReportSupported) {
				return "Android did not provide a more specific termination cause. "
						+ "Low-memory kills are separately reportable on this device, "
						+ "and this exit was not classified as one.";
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

	private static String processSubject(String role) {
		return "memory_engine".equals(role) ? "Memory Engine" : role;
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
