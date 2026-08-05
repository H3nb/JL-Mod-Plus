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

/** Strict parser for the initial JPEG subset of VideoControl.getSnapshot(). */
public final class SnapshotEncodingParser {
	private SnapshotEncodingParser() {
	}

	public static SnapshotRequest parse(String imageType) throws MediaException {
		if (imageType == null) {
			return new SnapshotRequest(CaptureRequest.DEFAULT_WIDTH, CaptureRequest.DEFAULT_HEIGHT, true);
		}
		if (imageType.isEmpty()) {
			throw new IllegalArgumentException("snapshot encoding must not be empty");
		}

		String encoding = null;
		Integer width = null;
		Integer height = null;
		Set<String> seen = new HashSet<>();
		for (String pair : imageType.split("&", -1)) {
			int equals = pair.indexOf('=');
			if (equals <= 0 || equals == pair.length() - 1) {
				throw new IllegalArgumentException("malformed snapshot parameter");
			}
			String key = decode(pair.substring(0, equals));
			String value = decode(pair.substring(equals + 1));
			String normalizedKey = key.toLowerCase(Locale.ROOT);
			if (!seen.add(normalizedKey) || key.isEmpty() || value.isEmpty()) {
				throw new IllegalArgumentException("duplicate or empty snapshot parameter: " + key);
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
				default -> throw new IllegalArgumentException("Unknown snapshot parameter: " + key);
			}
		}
		if (encoding == null) {
			throw new IllegalArgumentException("snapshot encoding is required");
		}
		if ((width == null) != (height == null)) {
			throw new IllegalArgumentException("width and height must be specified together");
		}
		int resolvedWidth = width == null ? CaptureRequest.DEFAULT_WIDTH : width;
		int resolvedHeight = height == null ? CaptureRequest.DEFAULT_HEIGHT : height;
		if (resolvedWidth > CaptureRequest.MAX_WIDTH || resolvedHeight > CaptureRequest.MAX_HEIGHT
				|| (long) resolvedWidth * resolvedHeight > CaptureRequest.MAX_PIXEL_COUNT) {
			throw new MediaException("Requested snapshot is outside the virtual limits");
		}
		return new SnapshotRequest(resolvedWidth, resolvedHeight);
	}

	private static int parsePositive(String value, String name) {
		try {
			int parsed = Integer.parseInt(value);
			if (parsed <= 0) {
				throw new IllegalArgumentException(name + " must be positive");
			}
			return parsed;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("invalid " + name, e);
		}
	}

	private static String decode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException | IllegalArgumentException e) {
			throw new IllegalArgumentException("invalid percent-encoding", e);
		}
	}
}
