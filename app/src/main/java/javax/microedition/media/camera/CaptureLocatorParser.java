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

/** Strict parser for the supported subset of the MMAPI capture locator grammar. */
public final class CaptureLocatorParser {
	private static final String PREFIX = "capture://";

	private CaptureLocatorParser() {
	}

	public static CaptureRequest parse(String locator) throws MediaException {
		if (locator == null) {
			throw new IllegalArgumentException("locator must not be null");
		}
		if (!locator.startsWith(PREFIX)) {
			throw new IllegalArgumentException("not a capture locator");
		}

		String remainder = locator.substring(PREFIX.length());
		int queryIndex = remainder.indexOf('?');
		String device = queryIndex < 0 ? remainder : remainder.substring(0, queryIndex);
		validateDevice(device);
		if (!CaptureRequest.DEVICE_VIDEO.equals(device)
				&& !CaptureRequest.DEVICE_AUDIO_VIDEO.equals(device)) {
			throw new MediaException("Unsupported capture device: " + device);
		}

		String encoding = CaptureRequest.DEVICE_AUDIO_VIDEO.equals(device)
				? CaptureRequest.DEFAULT_RECORDING_ENCODING : CaptureRequest.DEFAULT_ENCODING;
		int width = CaptureRequest.DEFAULT_WIDTH;
		int height = CaptureRequest.DEFAULT_HEIGHT;
		if (queryIndex >= 0) {
			String query = remainder.substring(queryIndex + 1);
			if (query.isEmpty()) {
				throw new IllegalArgumentException("capture query must not be empty");
			}
			Set<String> seen = new HashSet<>();
			Integer requestedWidth = null;
			Integer requestedHeight = null;
			for (String pair : query.split("&", -1)) {
				int equals = pair.indexOf('=');
				if (equals <= 0 || equals == pair.length() - 1) {
					throw new IllegalArgumentException("malformed capture parameter");
				}
				String key = decode(pair.substring(0, equals));
				String value = decode(pair.substring(equals + 1));
				String normalizedKey = key.toLowerCase(Locale.ROOT);
				if (!seen.add(normalizedKey) || key.isEmpty() || value.isEmpty()) {
					throw new IllegalArgumentException("duplicate or empty capture parameter: " + key);
				}
				switch (normalizedKey) {
					case "encoding" -> {
						String expected = CaptureRequest.DEVICE_AUDIO_VIDEO.equals(device)
								? CaptureRequest.DEFAULT_RECORDING_ENCODING : CaptureRequest.DEFAULT_ENCODING;
						if (!expected.equalsIgnoreCase(value)) {
							throw new MediaException("Unsupported capture encoding: " + value);
						}
						encoding = expected;
					}
					case "width" -> requestedWidth = parsePositive(value, "width");
					case "height" -> requestedHeight = parsePositive(value, "height");
					case "fps" -> {
						parsePositive(value, "fps");
						throw new MediaException("Frame-rate selection is not supported");
					}
					default -> throw new IllegalArgumentException("Unknown capture parameter: " + key);
				}
			}
			if ((requestedWidth == null) != (requestedHeight == null)) {
				throw new IllegalArgumentException("width and height must be specified together");
			}
			if (requestedWidth != null) {
				width = requestedWidth;
				height = requestedHeight;
			}
		}

		validateDimensions(width, height);
		return new CaptureRequest(locator, device, encoding, width, height);
	}

	/** Returns the device component for Manager routing without requesting permission. */
	public static String deviceOf(String locator) {
		if (locator == null || !locator.startsWith(PREFIX)) {
			throw new IllegalArgumentException("not a capture locator");
		}
		String remainder = locator.substring(PREFIX.length());
		int queryIndex = remainder.indexOf('?');
		String device = queryIndex < 0 ? remainder : remainder.substring(0, queryIndex);
		validateDevice(device);
		return device;
	}

	private static void validateDevice(String device) {
		if (device == null || device.isEmpty()) {
			throw new IllegalArgumentException("capture device must not be empty");
		}
		if ("audio_video".equals(device)) {
			return;
		}
		for (int i = 0; i < device.length(); i++) {
			char c = device.charAt(i);
			if (!Character.isLetterOrDigit(c)) {
				throw new IllegalArgumentException("malformed capture device");
			}
		}
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

	private static void validateDimensions(int width, int height) throws MediaException {
		if (width > CaptureRequest.MAX_WIDTH || height > CaptureRequest.MAX_HEIGHT
				|| (long) width * height > CaptureRequest.MAX_PIXEL_COUNT) {
			throw new MediaException("Requested camera source is outside the virtual limits");
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
