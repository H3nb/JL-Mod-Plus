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

/** Immutable, regenerable semantic interpretation of one correlated diagnostic record. */
final class IncidentSummary {
	enum Category {
		MIDLET_LIFECYCLE,
		MIDLET_CRASH,
		JAVA_FAILURE,
		JL_MOD_PLUS,
		NATIVE_CRASH,
		ANR,
		PROCESS_EXIT
	}

	enum FailureOrigin {
		MIDLET,
		JL_MOD_PLUS,
		UNKNOWN
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

		boolean sameFailure(JavaFailure other) {
			return other != null
					&& equal(type, other.type)
					&& equal(message, other.message);
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
		final String source;
		final int reason;
		final int status;
		final String reasonLabel;
		final String statusLabel;
		final String importance;
		final String processName;
		final String processRole;
		final String description;
		final int sdk;
		final String androidRelease;
		final String device;
		final String primaryAbi;
		final long pssKb;
		final long rssKb;
		final boolean lowMemoryKillReportSupported;
		final String traceKind;
		final boolean traceAvailable;
		final boolean traceTruncated;
		final int anrType;
		final long anrTimeoutMillis;
		final int anrId;
		final Boolean anrUserPerceptible;
		final boolean controlledByJlMod;
		final String summary;
		final String limitation;

		ProcessExitEvidence(String source, int reason, int status, String reasonLabel, String statusLabel,
				String importance, String processName, String processRole, String description,
				int sdk, String androidRelease, String device, String primaryAbi,
				long pssKb, long rssKb, boolean lowMemoryKillReportSupported,
				String traceKind, boolean traceAvailable, boolean traceTruncated,
				int anrType, long anrTimeoutMillis, int anrId, Boolean anrUserPerceptible,
				boolean controlledByJlMod, String summary, String limitation) {
			this.source = clean(source);
			this.reason = reason;
			this.status = status;
			this.reasonLabel = clean(reasonLabel);
			this.statusLabel = clean(statusLabel);
			this.importance = clean(importance);
			this.processName = clean(processName);
			this.processRole = clean(processRole);
			this.description = clean(description);
			this.sdk = sdk;
			this.androidRelease = clean(androidRelease);
			this.device = clean(device);
			this.primaryAbi = clean(primaryAbi);
			this.pssKb = Math.max(0, pssKb);
			this.rssKb = Math.max(0, rssKb);
			this.lowMemoryKillReportSupported = lowMemoryKillReportSupported;
			this.traceKind = clean(traceKind);
			this.traceAvailable = traceAvailable;
			this.traceTruncated = traceTruncated;
			this.anrType = anrType;
			this.anrTimeoutMillis = anrTimeoutMillis;
			this.anrId = anrId;
			this.anrUserPerceptible = anrUserPerceptible;
			this.controlledByJlMod = controlledByJlMod;
			this.summary = clean(summary);
			this.limitation = clean(limitation);
		}
	}

	final Category category;
	final FailureOrigin failureOrigin;
	final long incidentTimestampMillis;
	final String subject;
	final JavaFailure primaryFailure;
	final JavaFailure underlyingCause;
	final String javaEvidenceKind;
	final String operation;
	final String boundary;
	final String lifecycleStage;
	final String topRelevantFrame;
	final String midletVersion;
	final String entrypoint;
	final String jarFingerprint;
	final String build;
	final String androidRelease;
	final int androidSdk;
	final String device;
	final String primaryAbi;
	final String process;
	final String eventId;
	final String sessionId;
	final List<Breadcrumb> breadcrumbs;
	final ProcessExitEvidence associatedProcessExit;
	final List<String> limitations;
	final String fingerprint;

	IncidentSummary(Category category, FailureOrigin failureOrigin, long incidentTimestampMillis,
			String subject, JavaFailure primaryFailure, JavaFailure underlyingCause,
			String javaEvidenceKind, String operation, String boundary, String lifecycleStage,
			String topRelevantFrame, String midletVersion, String entrypoint, String jarFingerprint,
			String build, String androidRelease, int androidSdk, String device, String primaryAbi,
			String process, String eventId, String sessionId, List<Breadcrumb> breadcrumbs,
			ProcessExitEvidence associatedProcessExit, List<String> limitations) {
		this.category = category == null ? Category.JAVA_FAILURE : category;
		this.failureOrigin = failureOrigin == null ? FailureOrigin.UNKNOWN : failureOrigin;
		this.incidentTimestampMillis = Math.max(0, incidentTimestampMillis);
		this.subject = clean(subject);
		this.primaryFailure = primaryFailure;
		this.underlyingCause = primaryFailure != null && primaryFailure.sameFailure(underlyingCause)
				? null : underlyingCause;
		this.javaEvidenceKind = clean(javaEvidenceKind);
		this.operation = clean(operation);
		this.boundary = clean(boundary);
		this.lifecycleStage = clean(lifecycleStage);
		this.topRelevantFrame = clean(topRelevantFrame);
		this.midletVersion = clean(midletVersion);
		this.entrypoint = clean(entrypoint);
		this.jarFingerprint = clean(jarFingerprint);
		this.build = clean(build);
		this.androidRelease = clean(androidRelease);
		this.androidSdk = androidSdk;
		this.device = clean(device);
		this.primaryAbi = clean(primaryAbi);
		this.process = clean(process);
		this.eventId = clean(eventId);
		this.sessionId = clean(sessionId);
		this.breadcrumbs = breadcrumbs == null
				? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(breadcrumbs));
		this.associatedProcessExit = associatedProcessExit;
		this.limitations = limitations == null
				? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(limitations));
		this.fingerprint = fingerprint(this);
	}

	String bundleFileName() {
		SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(new Date(incidentTimestampMillis)) + "-" + fingerprint + ".zip";
	}

	boolean handledMidletSessionFailure() {
		return JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE.name().equals(javaEvidenceKind);
	}

	String androidLabel() {
		if (androidRelease != null && androidSdk >= 0) {
			return "Android " + androidRelease + " (SDK " + androidSdk + ")";
		}
		if (androidRelease != null) return "Android " + androidRelease;
		return androidSdk >= 0 ? "Android SDK " + androidSdk : null;
	}

	private static String fingerprint(IncidentSummary incident) {
		StringBuilder canonical = new StringBuilder();
		appendCanonical(canonical, incident.category.name());
		appendCanonical(canonical, incident.failureOrigin.name());
		appendCanonical(canonical, incident.subject);
		appendCanonical(canonical, incident.primaryFailure == null ? null : incident.primaryFailure.type);
		appendCanonical(canonical, incident.operation);
		appendCanonical(canonical, incident.boundary);
		appendCanonical(canonical, normalizeFrame(incident.topRelevantFrame));
		appendCanonical(canonical, incident.entrypoint);
		appendCanonical(canonical, incident.jarFingerprint);
		if (incident.primaryFailure == null && incident.associatedProcessExit != null) {
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

	private static boolean equal(String left, String right) {
		return left == null ? right == null : left.equals(right);
	}
}
