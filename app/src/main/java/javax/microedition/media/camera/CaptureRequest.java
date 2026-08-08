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

/** Immutable normalized request for an MMAPI live-camera locator. */
public final class CaptureRequest {
	public static final String CONTENT_TYPE = "video/preview";
	public static final String DEVICE_VIDEO = "video";
	public static final String DEVICE_IMAGE = "image";
	public static final String DEVICE_REAR = "devcam0";
	public static final String DEVICE_FRONT = "devcam1";
	public static final String DEVICE_AUDIO_VIDEO = "audio_video";
	/** Legacy still-camera compatibility encoding accepted on capture://video. */
	public static final String DEFAULT_ENCODING = "jpeg";
	/** Container produced by the CameraX RecordControl backend. */
	public static final String DEFAULT_RECORDING_ENCODING = "video/mp4";

	/** Conservative virtual defaults; independent of the MIDlet display size. */
	public static final int DEFAULT_WIDTH = 640;
	public static final int DEFAULT_HEIGHT = 480;
	public static final int MAX_WIDTH = 2048;
	public static final int MAX_HEIGHT = 2048;
	public static final long MAX_PIXEL_COUNT = 2048L * 1536L;

	/** Bounded CameraX source target before exact virtual crop/resize. */
	public static final int PHYSICAL_CAPTURE_WIDTH = 2048;
	public static final int PHYSICAL_CAPTURE_HEIGHT = 1536;

	private final String locator;
	private final String device;
	private final LogicalCameraDevice logicalCameraDevice;
	private final String encoding;
	private final int width;
	private final int height;
	private final boolean explicitDimensions;

	CaptureRequest(String locator, String device, LogicalCameraDevice logicalCameraDevice,
			String encoding, int width, int height, boolean explicitDimensions) {
		this.locator = locator;
		this.device = device;
		this.logicalCameraDevice = logicalCameraDevice;
		this.encoding = encoding;
		this.width = width;
		this.height = height;
		this.explicitDimensions = explicitDimensions;
	}

	public String getLocator() {
		return locator;
	}

	public String getDevice() {
		return device;
	}

	public LogicalCameraDevice getLogicalCameraDevice() {
		return logicalCameraDevice;
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

	public boolean hasExplicitDimensions() {
		return explicitDimensions;
	}
}
