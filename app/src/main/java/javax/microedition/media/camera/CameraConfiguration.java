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

/** Virtual resolution policy shared by JSR-135 and JSR-234 camera controls. */
public final class CameraConfiguration {
	private static final int[][] RESOLUTIONS = {
			{240, 320},
			{480, 640},
			{960, 1280},
			{1536, 2048}
	};

	private int videoResolutionIndex;
	private int stillResolutionIndex;

	public CameraConfiguration(CaptureRequest request) {
		int initial = findResolution(request.getWidth(), request.getHeight());
		videoResolutionIndex = initial;
		stillResolutionIndex = initial;
	}

	public static int[] supportedResolutions() {
		int[] flattened = new int[RESOLUTIONS.length * 2];
		for (int i = 0; i < RESOLUTIONS.length; i++) {
			flattened[i * 2] = RESOLUTIONS[i][0];
			flattened[i * 2 + 1] = RESOLUTIONS[i][1];
		}
		return flattened;
	}

	public synchronized int getVideoResolutionIndex() {
		return videoResolutionIndex;
	}

	public synchronized int getStillResolutionIndex() {
		return stillResolutionIndex;
	}

	public synchronized void setVideoResolutionIndex(int index) {
		checkIndex(index);
		videoResolutionIndex = index;
	}

	public synchronized void setStillResolutionIndex(int index) {
		checkIndex(index);
		stillResolutionIndex = index;
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

	/** Applies the selected AMMS still resolution only to an unspecified MMAPI snapshot. */
	public synchronized SnapshotRequest resolveSnapshot(SnapshotRequest request) {
		if (request == null || !request.isDefaultResolution()) {
			return request;
		}
		return new SnapshotRequest(getStillWidth(), getStillHeight());
	}

	private static int findResolution(int width, int height) {
		for (int i = 0; i < RESOLUTIONS.length; i++) {
			if (RESOLUTIONS[i][0] == width && RESOLUTIONS[i][1] == height) {
				return i;
			}
		}
		return 1;
	}

	private static void checkIndex(int index) {
		if (index < 0 || index >= RESOLUTIONS.length) {
			throw new IllegalArgumentException("unsupported camera resolution index: " + index);
		}
	}
}
