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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Small immutable projection of one correlated diagnostic incident.
 *
 * <p>The source of truth remains {@link LocalDiagnosticRepository.Record} and its retained
 * evidence. This class contains only stable facts needed by export/reporting surfaces.</p>
 */
final class IncidentSummary {
	enum Category {
		MIDLET_LIFECYCLE,
		MIDLET_CRASH,
		JL_MOD_PLUS,
		NATIVE_CRASH,
		ANR,
		PROCESS_EXIT
	}

	static final class JavaFailure {
		final String type;
		final String message;
		final String frame;

		JavaFailure(String type, String message, String frame) {
			this.type = clean(type);
			this.message = clean(message);
			this.frame = clean(frame);
		}

		String simpleType() {
			if (type == null) return null;
			int dot = Math.max(type.lastIndexOf('.'), type.lastIndexOf('$'));
			return dot >= 0 && dot + 1 < type.length() ? type.substring(dot + 1) : type;
		}
	}

	static final class Breadcrumb {
		final long wallTimeMillis;
		final String location;
		final String action;
		final String phase;

		Breadcrumb(long wallTimeMillis, String location, String action, String phase) {
			this.wallTimeMillis = wallTimeMillis;
			this.location = clean(location);
			this.action = clean(action);
			this.phase = clean(phase);
		}
	}

	static final class ProcessExitEvidence {
		final int reason;
		final int status;
		final String reasonLabel;
		final String statusLabel;
		final String importance;
		final String cause;
		final String processName;
		final String processRole;
		final String description;
		final int sdk;
		final String device;

		ProcessExitEvidence(int reason, int status, String reasonLabel, String statusLabel,
				String importance, String cause, String processName, String processRole,
				String description, int sdk, String device) {
			this.reason = reason;
			this.status = status;
			this.reasonLabel = clean(reasonLabel);
			this.statusLabel = clean(statusLabel);
			this.importance = clean(importance);
			this.cause = clean(cause);
			this.processName = clean(processName);
			this.processRole = clean(processRole);
			this.description = clean(description);
			this.sdk = sdk;
			this.device = clean(device);
		}
	}

	final Category category;
	final long incidentTimestampMillis;
	final String subject;
	final JavaFailure rootFailure;
	final String operation;
	final String lifecycleStage;
	final String topRelevantFrame;
	final String midletVersion;
	final String entrypoint;
	final String jarFingerprint;
	final String build;
	final String environment;
	final String process;
	final List<Breadcrumb> breadcrumbs;
	final ProcessExitEvidence associatedProcessExit;
	final String fingerprint;

	IncidentSummary(Category category, long incidentTimestampMillis, String subject,
			JavaFailure rootFailure, String operation, String lifecycleStage,
			String topRelevantFrame, String midletVersion, String entrypoint,
			String jarFingerprint, String build, String environment, String process,
			List<Breadcrumb> breadcrumbs, ProcessExitEvidence associatedProcessExit) {
		this.category = category == null ? Category.JL_MOD_PLUS : category;
		this.incidentTimestampMillis = Math.max(0L, incidentTimestampMillis);
		this.subject = clean(subject);
		this.rootFailure = rootFailure;
		this.operation = clean(operation);
		this.lifecycleStage = clean(lifecycleStage);
		this.topRelevantFrame = clean(topRelevantFrame);
		this.midletVersion = clean(midletVersion);
		this.entrypoint = clean(entrypoint);
		this.jarFingerprint = clean(jarFingerprint);
		this.build = clean(build);
		this.environment = clean(environment);
		this.process = clean(process);
		this.breadcrumbs = breadcrumbs == null
				? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(breadcrumbs));
		this.associatedProcessExit = associatedProcessExit;
		this.fingerprint = fingerprint(this);
	}

	String bundleFileName() {
		SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(new Date(incidentTimestampMillis)) + "-" + fingerprint + ".zip";
	}

	static JavaFailure analyzeJavaFailure(String stackTrace) {
		if (stackTrace == null || stackTrace.trim().isEmpty()) return null;
		String[] lines = stackTrace.split("\\r?\\n");
		String headline = null;
		String deepestCause = null;
		int deepestCauseLine = -1;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].trim();
			if (line.isEmpty() || line.startsWith("eventId=") || "jlamf".equals(line)) continue;
			if (line.startsWith("Caused by:")) {
				String cause = clean(line.substring("Caused by:".length()));
				if (cause != null) {
					deepestCause = cause;
					deepestCauseLine = i;
				}
				continue;
			}
			if (headline == null && !line.startsWith("at ") && !line.startsWith("...")) {
				headline = line;
			}
		}

		String selected = deepestCause != null ? deepestCause : headline;
		if (selected == null) return null;
		String frame = firstFrame(lines, deepestCauseLine >= 0 ? deepestCauseLine + 1 : 0);
		if (frame == null && deepestCauseLine > 0) frame = firstFrame(lines, 0);

		int colon = selected.indexOf(':');
		String type = colon < 0 ? selected : selected.substring(0, colon);
		String message = colon < 0 ? null : selected.substring(colon + 1);
		type = clean(type);
		if (type != null && type.indexOf(' ') >= 0) {
			// A non-Throwable wrapper headline is less useful than an actual cause, but when no
			// cause exists retain it verbatim rather than inventing an exception type.
			message = clean(selected);
			type = null;
		}
		return new JavaFailure(type, message, frame);
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

	private static String firstFrame(String[] lines, int start) {
		for (int i = Math.max(0, start); i < lines.length; i++) {
			String line = lines[i].trim();
			if (line.startsWith("Caused by:")) break;
			if (line.startsWith("at ") && line.length() > 3) return line.substring(3);
		}
		return null;
	}

	private static String fingerprint(IncidentSummary incident) {
		StringBuilder canonical = new StringBuilder();
		appendCanonical(canonical, incident.category.name());
		appendCanonical(canonical, incident.subject);
		appendCanonical(canonical, incident.rootFailure == null ? null : incident.rootFailure.type);
		appendCanonical(canonical, incident.operation);
		appendCanonical(canonical, normalizeFrame(incident.topRelevantFrame));
		appendCanonical(canonical, incident.entrypoint);
		appendCanonical(canonical, incident.jarFingerprint);
		if (incident.associatedProcessExit != null
				&& (incident.category == Category.NATIVE_CRASH
				|| incident.category == Category.ANR
				|| incident.category == Category.PROCESS_EXIT)) {
			appendCanonical(canonical, Integer.toString(incident.associatedProcessExit.reason));
			appendCanonical(canonical, Integer.toString(incident.associatedProcessExit.status));
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(8);
			for (int i = 0; i < 4; i++) {
				result.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError("SHA-256 unavailable", impossible);
		}
	}

	private static void appendCanonical(StringBuilder target, String value) {
		target.append(value == null ? "-" : value.trim()).append('\n');
	}

	private static String normalizeFrame(String frame) {
		if (frame == null) return null;
		int paren = frame.indexOf('(');
		return paren < 0 ? frame : frame.substring(0, paren);
	}

	static String shortFingerprint(String value) {
		String cleaned = clean(value);
		if (cleaned == null || "unknown".equalsIgnoreCase(cleaned)) return null;
		return cleaned.length() <= 12 ? cleaned : cleaned.substring(0, 12);
	}

	static String clean(String value) {
		if (value == null) return null;
		String result = value.replace('\r', ' ').replace('\n', ' ').trim();
		return result.isEmpty() ? null : result;
	}
}
