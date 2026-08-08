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

package javax.microedition.media.camera;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.microedition.media.MediaException;

/** Parser for the supported JPEG subset of VideoControl.getSnapshot(). */
public final class SnapshotEncodingParser {
	private SnapshotEncodingParser() {
	}

	public static SnapshotRequest parse(String imageType) throws MediaException {
		int defaultQuality = CameraRuntimeConfig.jpegQuality();
		if (imageType == null) {
			return SnapshotRequest.defaultRequest(defaultQuality);
		}
		if (imageType.isEmpty()) {
			throw new MediaException("snapshot encoding must not be empty");
		}
		// Common vendor-compatible shorthand used by real MIDlets.
		if ("jpeg".equalsIgnoreCase(imageType)) {
			return SnapshotRequest.defaultRequest(defaultQuality);
		}

		String encoding = null;
		Integer width = null;
		Integer height = null;
		int quality = defaultQuality;
		Set<String> seen = new HashSet<>();
		for (String pair : imageType.split("&", -1)) {
			int equals = pair.indexOf('=');
			if (equals <= 0 || equals == pair.length() - 1) {
				throw new MediaException("malformed snapshot parameter");
			}
			String key = decode(pair.substring(0, equals));
			String value = decode(pair.substring(equals + 1));
			String normalizedKey = key.toLowerCase(Locale.ROOT);
			if (!seen.add(normalizedKey) || key.isEmpty() || value.isEmpty()) {
				throw new MediaException("duplicate or empty snapshot parameter: " + key);
			}
			switch (normalizedKey) {
				case "encoding" -> {
					if (!"jpeg".equalsIgnoreCase(value)) {
						throw new MediaException("Unsupported snapshot encoding: " + value);
					}
					encoding = "jpeg";
				}
				case "width" -> width = parsePositive(value, "width");
				case "height" -> height = parsePositive(value, "height");
				case "quality" -> quality = parseQuality(value);
				default -> throw new MediaException("Unknown snapshot parameter: " + key);
			}
		}
		if (encoding == null) {
			throw new MediaException("snapshot encoding is required");
		}
		if ((width == null) != (height == null)) {
			throw new MediaException("width and height must be specified together");
		}
		if (width == null) {
			return SnapshotRequest.defaultRequest(quality);
		}
		if (!CameraConfiguration.isWithinVirtualLimits(width, height)) {
			throw new MediaException("Requested snapshot is outside the virtual limits");
		}
		return new SnapshotRequest(width, height, false, quality);
	}

	private static int parsePositive(String value, String name) throws MediaException {
		try {
			int parsed = Integer.parseInt(value);
			if (parsed <= 0) {
				throw new MediaException(name + " must be positive");
			}
			return parsed;
		} catch (NumberFormatException e) {
			throw new MediaException("invalid " + name);
		}
	}

	private static int parseQuality(String value) throws MediaException {
		int quality = parsePositive(value, "quality");
		if (quality > 100) {
			throw new MediaException("quality must be between 1 and 100");
		}
		return quality;
	}

	private static String decode(String value) throws MediaException {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException | IllegalArgumentException e) {
			throw new MediaException("invalid percent-encoding");
		}
	}
}
