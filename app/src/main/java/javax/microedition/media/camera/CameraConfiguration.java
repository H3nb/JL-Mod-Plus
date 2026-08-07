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

import javax.microedition.media.MediaException;

/** Canonical virtual-resolution catalog shared by MMAPI camera surfaces. */
public final class CameraConfiguration {
	private static final int[][] RESOLUTIONS = {
			{640, 480},
			{320, 240},
			{160, 120},
			{176, 144},
			{352, 288},
			{240, 320},
			{480, 640},
			{800, 600},
			{1024, 768},
			{960, 1280},
			{1280, 960},
			{1600, 1200},
			{1536, 2048},
			{2048, 1536}
	};

	private int videoResolutionIndex;
	private int stillResolutionIndex;

	public CameraConfiguration(CaptureRequest request) {
		int initial = request.hasExplicitDimensions()
				? findResolution(request.getWidth(), request.getHeight())
				: findResolution(CameraRuntimeConfig.defaultWidth(), CameraRuntimeConfig.defaultHeight());
		videoResolutionIndex = initial >= 0 ? initial : 0;
		stillResolutionIndex = initial >= 0 ? initial : 0;
	}

	public static int[] supportedResolutions() {
		int count = 0;
		for (int[] resolution : RESOLUTIONS) {
			if (CameraRuntimeConfig.acceptsDimensions(resolution[0], resolution[1])) {
				count++;
			}
		}
		int[] flattened = new int[count * 2];
		int output = 0;
		for (int[] resolution : RESOLUTIONS) {
			if (!CameraRuntimeConfig.acceptsDimensions(resolution[0], resolution[1])) {
				continue;
			}
			flattened[output++] = resolution[0];
			flattened[output++] = resolution[1];
		}
		return flattened;
	}

	public static String snapshotEncodings() {
		StringBuilder result = new StringBuilder();
		appendEncoding(result, CameraRuntimeConfig.defaultWidth(), CameraRuntimeConfig.defaultHeight());
		for (int[] resolution : RESOLUTIONS) {
			if (!CameraRuntimeConfig.acceptsDimensions(resolution[0], resolution[1])
					|| (resolution[0] == CameraRuntimeConfig.defaultWidth()
					&& resolution[1] == CameraRuntimeConfig.defaultHeight())) {
				continue;
			}
			appendEncoding(result, resolution[0], resolution[1]);
		}
		return result.toString();
	}

	public static boolean isWithinVirtualLimits(int width, int height) {
		return CameraRuntimeConfig.acceptsDimensions(width, height);
	}

	public synchronized int getVideoResolutionIndex() {
		return videoResolutionIndex;
	}

	public synchronized int getStillResolutionIndex() {
		return stillResolutionIndex;
	}

	public synchronized void setVideoResolutionIndex(int index) {
		checkIndex(index);
		int[] resolution = supportedResolutionAt(index);
		videoResolutionIndex = findResolution(resolution[0], resolution[1]);
	}

	public synchronized void setStillResolutionIndex(int index) {
		checkIndex(index);
		int[] resolution = supportedResolutionAt(index);
		stillResolutionIndex = findResolution(resolution[0], resolution[1]);
	}

	public synchronized int getVideoWidth() {
		return RESOLUTIONS[videoResolutionIndex][0];
	}

	public synchronized int getVideoHeight() {
		return RESOLUTIONS[videoResolutionIndex][1];
	}

	public synchronized int getStillWidth() {
		return RESOLUTIONS[stillResolutionIndex][0];
	}

	public synchronized int getStillHeight() {
		return RESOLUTIONS[stillResolutionIndex][1];
	}

	/** Applies the selected virtual still resolution only to an unspecified snapshot. */
	public synchronized SnapshotRequest resolveSnapshot(SnapshotRequest request) throws MediaException {
		if (request == null) {
			return null;
		}
		SnapshotRequest resolved = request.isDefaultResolution()
				? new SnapshotRequest(getStillWidth(), getStillHeight(), false, request.getQuality())
				: request;
		if (!CameraRuntimeConfig.acceptsDimensions(resolved.getWidth(), resolved.getHeight())) {
			throw new MediaException("Requested snapshot is outside the configured virtual limits");
		}
		return resolved;
	}

	private static void appendEncoding(StringBuilder result, int width, int height) {
		if (result.length() != 0) {
			result.append(' ');
		}
		result.append("encoding=jpeg&width=")
				.append(width)
				.append("&height=")
				.append(height);
	}

	private static int[] supportedResolutionAt(int index) {
		int[] flattened = supportedResolutions();
		int offset = index * 2;
		if (offset < 0 || offset + 1 >= flattened.length) {
			throw new IllegalArgumentException("unsupported camera resolution index: " + index);
		}
		return new int[]{flattened[offset], flattened[offset + 1]};
	}

	private static int findResolution(int width, int height) {
		for (int i = 0; i < RESOLUTIONS.length; i++) {
			if (RESOLUTIONS[i][0] == width && RESOLUTIONS[i][1] == height) {
				return i;
			}
		}
		return -1;
	}

	private static void checkIndex(int index) {
		int count = supportedResolutions().length / 2;
		if (index < 0 || index >= count) {
			throw new IllegalArgumentException("unsupported camera resolution index: " + index);
		}
	}
}
