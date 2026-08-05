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

/** Immutable normalized request for a JSR-135 live video locator. */
public final class CaptureRequest {
	public static final String CONTENT_TYPE = "video/preview";
	public static final String DEVICE_VIDEO = "video";
	public static final String DEVICE_AUDIO_VIDEO = "audio_video";
	public static final String DEFAULT_ENCODING = "jpeg";
	public static final String DEFAULT_RECORDING_ENCODING = "mp4";
	/** Miami Nights 2 and the local camera fixtures expect the portrait default. */
	public static final int DEFAULT_WIDTH = 480;
	public static final int DEFAULT_HEIGHT = 640;
	/** Per-axis virtual limit; the pixel budget keeps portrait and landscape symmetric. */
	public static final int MAX_WIDTH = 2048;
	public static final int MAX_HEIGHT = 2048;
	/** Bounded physical target used before orientation normalization and virtual resizing. */
	public static final int PHYSICAL_CAPTURE_WIDTH = 1536;
	public static final int PHYSICAL_CAPTURE_HEIGHT = 2048;
	public static final long MAX_PIXEL_COUNT = (long) PHYSICAL_CAPTURE_WIDTH * PHYSICAL_CAPTURE_HEIGHT;

	private final String locator;
	private final String device;
	private final String encoding;
	private final int width;
	private final int height;

	CaptureRequest(String locator, String device, String encoding, int width, int height) {
		this.locator = locator;
		this.device = device;
		this.encoding = encoding;
		this.width = width;
		this.height = height;
	}

	public String getLocator() {
		return locator;
	}

	public String getDevice() {
		return device;
	}

	public String getEncoding() {
		return encoding;
	}

	public boolean isAudioVideo() {
		return DEVICE_AUDIO_VIDEO.equals(device);
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}
}
