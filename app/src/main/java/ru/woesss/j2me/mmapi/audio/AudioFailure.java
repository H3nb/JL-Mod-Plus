/*
 * Copyright 2026 H3NB
 *
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

package ru.woesss.j2me.mmapi.audio;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A bounded, privacy-conscious description of one audio playback failure.
 *
 * <p>The report deliberately stores only the source basename, not a full
 * filesystem path or the media bytes. It is safe to persist and share after
 * the user reviews it.</p>
 */
public final class AudioFailure {
	public enum Phase {
		CREATE,
		REALIZE,
		PREFETCH,
		START,
		RUNTIME
	}

	private static final int MAX_FIELD_LENGTH = 160;

	private final long timestampMillis;
	private final String source;
	private final String contentType;
	private final String backend;
	private final Phase phase;
	private final String code;
	private final String exceptionType;
	private final String detail;

	private AudioFailure(long timestampMillis, String source, String contentType,
			String backend, Phase phase, String code, String exceptionType, String detail) {
		this.timestampMillis = timestampMillis;
		this.source = limit(source, "unknown");
		this.contentType = limit(contentType, "unknown");
		this.backend = limit(backend, "unknown");
		this.phase = phase == null ? Phase.RUNTIME : phase;
		this.code = limit(code, "unknown");
		this.exceptionType = limit(exceptionType, "none");
		this.detail = sanitizeDetail(detail);
	}

	public static AudioFailure create(String locator, String contentType, String backend,
			Phase phase, String code, Throwable error) {
		String exceptionType = error == null ? null : error.getClass().getName();
		String detail = error == null ? null : error.getMessage();
		return new AudioFailure(System.currentTimeMillis(), displaySource(locator),
				contentType, backend, phase, code, exceptionType, detail);
	}

	public static AudioFailure createWithDetail(String source, String contentType, String backend,
			Phase phase, String code, String detail) {
		return new AudioFailure(System.currentTimeMillis(), displaySource(source),
				contentType, backend, phase, code, null, detail);
	}

	public long getTimestampMillis() {
		return timestampMillis;
	}

	public String getSource() {
		return source;
	}

	public String getContentType() {
		return contentType;
	}

	public String getBackend() {
		return backend;
	}

	public Phase getPhase() {
		return phase;
	}

	public String getCode() {
		return code;
	}

	public String getExceptionType() {
		return exceptionType;
	}

	public String getDetail() {
		return detail;
	}

	/**
	 * Returns a stable key used only for short-lived notification deduplication.
	 */
	public String getNotificationKey() {
		return source + '|' + contentType + '|' + backend + '|' + phase + '|' + code;
	}

	public String toReportText() {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
		format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
		StringBuilder report = new StringBuilder(768);
		report.append("JL-Mod Plus audio failure report\n");
		report.append("Timestamp (UTC): ").append(format.format(new Date(timestampMillis))).append('\n');
		report.append("Source: ").append(source).append('\n');
		report.append("Declared MIME: ").append(contentType).append('\n');
		report.append("Backend: ").append(backend).append('\n');
		report.append("Phase: ").append(phase).append('\n');
		report.append("Error code: ").append(code).append('\n');
		report.append("Exception: ").append(exceptionType).append('\n');
		report.append("Detail: ").append(detail).append('\n');
		return report.toString();
	}

	private static String displaySource(String locator) {
		if (locator == null || locator.trim().isEmpty()) {
			return "unknown";
		}
		String value = locator.trim();
		int query = value.indexOf('?');
		if (query >= 0) {
			value = value.substring(0, query);
		}
		if (value.startsWith("device://")) {
			return limit(value, "unknown");
		}
		value = value.replace('\\', '/');
		int slash = value.lastIndexOf('/');
		if (slash >= 0 && slash + 1 < value.length()) {
			value = value.substring(slash + 1);
		}
		if (value.startsWith("file:")) {
			value = new File(value).getName();
		}
		return limit(value, "unknown");
	}

	private static String limit(String value, String fallback) {
		String normalized = value == null || value.trim().isEmpty() ? fallback : value.trim();
		if (normalized.length() <= MAX_FIELD_LENGTH) {
			return normalized;
		}
		return normalized.substring(0, MAX_FIELD_LENGTH - 1) + "…";
	}

	private static String sanitizeDetail(String value) {
		if (value == null || value.trim().isEmpty()) {
			return "No additional detail";
		}
		String sanitized = value.trim().replaceAll("(?i)(?:[a-z]:)?[/\\\\][^\\s,;]+", "[path]");
		return limit(sanitized, "No additional detail");
	}
}
