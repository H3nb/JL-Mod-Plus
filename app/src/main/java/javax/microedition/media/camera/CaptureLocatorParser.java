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

/** Parser for the supported MMAPI live-capture locator subset. */
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
		LogicalCameraDevice logicalCamera = resolveLogicalCamera(device);
		boolean image = CaptureRequest.DEVICE_IMAGE.equals(device);
		boolean audioVideo = CaptureRequest.DEVICE_AUDIO_VIDEO.equals(device);

		String encoding = image
				? CaptureRequest.JPEG_ENCODING : CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE;
		int width = CameraRuntimeConfig.defaultWidth();
		int height = CameraRuntimeConfig.defaultHeight();
		boolean explicitDimensions = false;
		boolean explicitEncoding = false;
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
						encoding = normalizeEncoding(device, value);
						explicitEncoding = true;
					}
					case "width" -> requestedWidth = parsePositive(value, "width");
					case "height" -> requestedHeight = parsePositive(value, "height");
					case "fps" -> parsePositiveNumber(value, "fps");
					default -> throw new IllegalArgumentException("Unknown capture parameter: " + key);
				}
			}
			if ((requestedWidth == null) != (requestedHeight == null)) {
				throw new IllegalArgumentException("width and height must be specified together");
			}
			if (requestedWidth != null) {
				width = requestedWidth;
				height = requestedHeight;
				explicitDimensions = true;
			}
		}

		if (CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE.equals(encoding)
				&& explicitDimensions && (audioVideo || explicitEncoding)) {
			throw new MediaException("Explicit video recording dimensions are not supported");
		}
		if (!CameraRuntimeConfig.acceptsDimensions(width, height)) {
			throw new MediaException("Requested camera source is outside the virtual limits");
		}
		return new CaptureRequest(locator, device, logicalCamera, encoding, width, height,
				explicitDimensions);
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

	private static String normalizeEncoding(String device, String value) throws MediaException {
		if (CaptureRequest.DEVICE_AUDIO_VIDEO.equals(device)) {
			if (CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE.equalsIgnoreCase(value)
					|| "mp4".equalsIgnoreCase(value)) {
				return CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE;
			}
			throw new MediaException("Unsupported capture encoding: " + value);
		}
		if (CaptureRequest.DEVICE_IMAGE.equals(device)) {
			if (CaptureRequest.JPEG_ENCODING.equalsIgnoreCase(value)) {
				return CaptureRequest.JPEG_ENCODING;
			}
			throw new MediaException("Unsupported capture encoding: " + value);
		}
		if (CaptureRequest.JPEG_ENCODING.equalsIgnoreCase(value)) {
			return CaptureRequest.JPEG_ENCODING;
		}
		if (CaptureRequest.GRAY8_ENCODING.equalsIgnoreCase(value)) {
			return CaptureRequest.GRAY8_ENCODING;
		}
		if (CaptureRequest.RGB888_ENCODING.equalsIgnoreCase(value)) {
			return CaptureRequest.RGB888_ENCODING;
		}
		if (CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE.equalsIgnoreCase(value)
				|| "mp4".equalsIgnoreCase(value)) {
			return CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE;
		}
		throw new MediaException("Unsupported capture encoding: " + value);
	}

	private static LogicalCameraDevice resolveLogicalCamera(String device) throws MediaException {
		return switch (device) {
			case CaptureRequest.DEVICE_VIDEO, CaptureRequest.DEVICE_IMAGE -> LogicalCameraDevice.DEFAULT;
			case CaptureRequest.DEVICE_REAR -> LogicalCameraDevice.REAR;
			case CaptureRequest.DEVICE_FRONT -> LogicalCameraDevice.FRONT;
			case CaptureRequest.DEVICE_AUDIO_VIDEO -> LogicalCameraDevice.DEFAULT;
			default -> throw new MediaException("Unsupported capture device: " + device);
		};
	}

	private static void validateDevice(String device) {
		if (device == null || device.isEmpty()) {
			throw new IllegalArgumentException("capture device must not be empty");
		}
		if (CaptureRequest.DEVICE_AUDIO_VIDEO.equals(device)) {
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

	/** Validates the MMAPI pos_number grammar (digits with an optional fractional part). */
	private static void parsePositiveNumber(String value, String name) {
		int dot = -1;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '.') {
				if (dot >= 0 || i == 0 || i == value.length() - 1) {
					throw new IllegalArgumentException("invalid " + name);
				}
				dot = i;
			} else if (!Character.isDigit(c)) {
				throw new IllegalArgumentException("invalid " + name);
			}
		}
		try {
			if (Double.parseDouble(value) <= 0.0) {
				throw new IllegalArgumentException(name + " must be positive");
			}
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
