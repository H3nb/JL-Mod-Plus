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

import android.content.Context;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.h3nb.jlmodplus.config.Config;

/** Explicit privacy contract applied only to diagnostics exported outside the app. */
final class DiagnosticExportSanitizer {
	private static final Pattern HTTP_URI = Pattern.compile("(?i)\\bhttps?://[^\\s]+");
	private static final Pattern PRIVATE_URI = Pattern.compile(
			"(?i)\\b(?:file|content|ftp|ftps|sftp|jar|mailto|tel|geo|market|intent):(?:/{1,3})?\\S+");
	private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)\\b[A-Z]:\\\\\\S+");
	private static final Pattern PRIVATE_UNIX_PATH = Pattern.compile(
			"(?<![A-Za-z0-9:/])(?:"
					+ "/storage/emulated/[0-9]+/[^\\s]+"
					+ "|/storage/self/primary/[^\\s]+"
					+ "|/sdcard/[^\\s]+"
					+ "|/mnt/sdcard/[^\\s]+"
					+ "|/mnt/media_rw/[^\\s]+"
					+ "|/data/user/[0-9]+/[^\\s]+"
					+ "|/data/data/[^\\s]+"
					+ "|/home/[^\\s]+"
					+ "|/Users/[^\\s]+"
					+ ")");

	private DiagnosticExportSanitizer() {}

	static String sanitize(Context context, String text) {
		if (context == null) return sanitize(text, null, null);
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
		if (text == null || text.isEmpty()) return text;

		String sanitized = replacePath(text, emulatorDir, "<emulator-dir>");
		sanitized = replacePath(sanitized, appDataDir, "<app-data>");
		sanitized = sanitizeHttpUris(sanitized);
		sanitized = PRIVATE_URI.matcher(sanitized).replaceAll("<uri>");
		sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll("<user-path>");
		sanitized = PRIVATE_UNIX_PATH.matcher(sanitized).replaceAll("<user-path>");
		return sanitized;
	}

	private static String sanitizeHttpUris(String text) {
		Matcher matcher = HTTP_URI.matcher(text);
		StringBuffer output = new StringBuffer(text.length());
		while (matcher.find()) {
			String token = matcher.group();
			String trailing = "";
			while (!token.isEmpty()) {
				char last = token.charAt(token.length() - 1);
				if (last != '.' && last != ',' && last != ';' && last != ')'
						&& last != ']' && last != '}') break;
				trailing = last + trailing;
				token = token.substring(0, token.length() - 1);
			}
			matcher.appendReplacement(output,
					Matcher.quoteReplacement(sanitizeHttpUri(token) + trailing));
		}
		matcher.appendTail(output);
		return output.toString();
	}

	private static String sanitizeHttpUri(String value) {
		try {
			URI uri = new URI(value);
			if (uri.getHost() == null) return "<uri>";
			return new URI(
					uri.getScheme().toLowerCase(java.util.Locale.ROOT),
					null,
					uri.getHost(),
					uri.getPort(),
					uri.getRawPath(),
					null,
					null).toASCIIString();
		} catch (URISyntaxException | IllegalArgumentException e) {
			int sensitive = firstPositive(value.indexOf('?'), value.indexOf('#'));
			String withoutSecrets = sensitive < 0 ? value : value.substring(0, sensitive);
			int credentials = withoutSecrets.indexOf('@');
			int scheme = withoutSecrets.indexOf("://");
			if (credentials > scheme + 3) {
				withoutSecrets = withoutSecrets.substring(0, scheme + 3)
						+ withoutSecrets.substring(credentials + 1);
			}
			return withoutSecrets;
		}
	}

	private static int firstPositive(int first, int second) {
		if (first < 0) return second;
		if (second < 0) return first;
		return Math.min(first, second);
	}

	private static String replacePath(String text, String path, String replacement) {
		if (path == null) return text;
		String normalized = path.trim();
		while (normalized.length() > 1
				&& (normalized.endsWith("/") || normalized.endsWith("\\"))) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		if (normalized.isEmpty()) return text;
		return text.replace(normalized, replacement);
	}
}
