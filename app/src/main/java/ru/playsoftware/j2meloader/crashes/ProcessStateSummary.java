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

package ru.playsoftware.j2meloader.crashes;

import java.nio.charset.StandardCharsets;

/** Pure codec for Android's <=128-byte ApplicationExitInfo process state summary. */
final class ProcessStateSummary {
	static final int MAX_BYTES = 128;
	private static final String PREFIX_V1 = "jlp1";
	private static final String PREFIX_V2 = "jlp2";

	private ProcessStateSummary() {}

	static byte[] build(String runId, String buildCommit, int sdk, String sessionId,
			String location, String action, String phase) {
		StringBuilder text = new StringBuilder(96).append(PREFIX_V2);
		appendIfFits(text, "u", runId);
		appendIfFits(text, "sdk", Integer.toString(sdk));
		appendIfFits(text, "s", sessionId);
		appendIfFits(text, "b", shortCommit(buildCommit));
		appendIfFits(text, "c", location);
		appendIfFits(text, "a", action);
		appendIfFits(text, "p", phase);
		return text.toString().getBytes(StandardCharsets.US_ASCII);
	}

	static Data parse(byte[] bytes) {
		if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
			return Data.empty();
		}
		String[] fields = new String(bytes, StandardCharsets.US_ASCII).split("\\|");
		if (fields.length == 0 || (!PREFIX_V1.equals(fields[0]) && !PREFIX_V2.equals(fields[0]))) {
			return Data.empty();
		}
		long versionCode = -1;
		int sdk = -1;
		String sessionId = null;
		String runId = null;
		String buildCommit = null;
		String location = null;
		String action = null;
		String phase = null;
		for (int i = 1; i < fields.length; i++) {
			int equals = fields[i].indexOf('=');
			if (equals <= 0 || equals == fields[i].length() - 1) {
				continue;
			}
			String key = fields[i].substring(0, equals);
			String value = fields[i].substring(equals + 1);
			try {
				switch (key) {
					case "vc" -> versionCode = Long.parseLong(value);
					case "sdk" -> sdk = Integer.parseInt(value);
					case "s" -> sessionId = MidletFailureRecovery.isSafeEventId(value) ? value : null;
					case "u" -> runId = CrashContextStore.isSafeRunId(value) ? value : null;
					case "b" -> buildCommit = safeToken(value, 12);
					case "c" -> location = CrashContextStore.normalizeToken(
							value, CrashContextStore.MAX_LOCATION_LENGTH);
					case "a" -> action = CrashContextStore.normalizeToken(
							value, CrashContextStore.MAX_ACTION_LENGTH);
					case "p" -> phase = CrashContextStore.normalizeToken(
							value, CrashContextStore.MAX_PHASE_LENGTH);
					default -> { }
				}
			} catch (NumberFormatException ignored) {}
		}
		return new Data(versionCode, sdk, sessionId, runId, buildCommit, location, action, phase);
	}

	private static void appendIfFits(StringBuilder text, String key, String value) {
		String safe = safeToken(value, MAX_BYTES);
		if (safe == null) {
			return;
		}
		int oldLength = text.length();
		text.append('|').append(key).append('=').append(safe);
		if (text.toString().getBytes(StandardCharsets.US_ASCII).length > MAX_BYTES) {
			text.setLength(oldLength);
		}
	}

	private static String shortCommit(String value) {
		String safe = safeToken(value, 40);
		if (safe == null || "unknown".equals(safe)) {
			return safe;
		}
		return safe.length() <= 8 ? safe : safe.substring(0, 8);
	}

	private static String safeToken(String value, int maxLength) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		int limit = Math.min(value.length(), maxLength);
		StringBuilder safe = new StringBuilder(limit);
		for (int i = 0; i < limit; i++) {
			char c = value.charAt(i);
			if (c < 0x21 || c > 0x7e || c == '|' || c == '=') {
				return null;
			}
			safe.append(c);
		}
		return safe.length() == 0 ? null : safe.toString();
	}

	static final class Data {
		final long versionCode;
		final int sdk;
		final String sessionId;
		final String runId;
		final String buildCommit;
		final String location;
		final String action;
		final String phase;

		Data(long versionCode, int sdk, String sessionId, String runId, String buildCommit,
				 String location, String action, String phase) {
			this.versionCode = versionCode;
			this.sdk = sdk;
			this.sessionId = sessionId;
			this.runId = runId;
			this.buildCommit = buildCommit;
			this.location = location;
			this.action = action;
			this.phase = phase;
		}

		static Data empty() {
			return new Data(-1, -1, null, null, null, null, null, null);
		}
	}
}
