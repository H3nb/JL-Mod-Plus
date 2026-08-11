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

import android.content.Context;

import java.util.regex.Pattern;

import ru.playsoftware.j2meloader.config.Config;

/** Second privacy pass applied only to text copied/shared outside the app. */
final class DiagnosticExportSanitizer {
	private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|file)://\\S+");
	private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)\\b[A-Z]:\\\\\\S+");
	private static final Pattern UNIX_PATH = Pattern.compile("(?<![A-Za-z0-9:/])/(?:[^\\s]+)");

	private DiagnosticExportSanitizer() {}

	static String sanitize(Context context, String text) {
		String appDataDir = context.getApplicationInfo().dataDir;
		String emulatorDir;
		try {
			emulatorDir = Config.getEmulatorDir();
		} catch (RuntimeException e) {
			emulatorDir = null;
		}
		return sanitize(text, emulatorDir, appDataDir);
	}

	static String sanitize(String text, String emulatorDir, String appDataDir) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		String sanitized = URL.matcher(text).replaceAll("<url>");
		sanitized = replacePath(sanitized, emulatorDir, "<emulator-dir>");
		sanitized = replacePath(sanitized, appDataDir, "<app-data>");
		sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll("<path>");
		sanitized = UNIX_PATH.matcher(sanitized).replaceAll("<path>");
		return sanitized;
	}

	private static String replacePath(String text, String path, String replacement) {
		if (path == null) {
			return text;
		}
		String normalized = path.trim();
		while (normalized.length() > 1 && (normalized.endsWith("/") || normalized.endsWith("\\"))) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized.isEmpty() ? text : text.replace(normalized, replacement);
	}
}
