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

/** Canonical Java ME-compatible virtual-resolution catalog shared by MMAPI camera surfaces. */
public final class CameraConfiguration {
	/*
	 * Keep this catalog intentionally bounded to feature-phone-era Java ME sizes.
	 * Both orientations are exposed for explicit MMAPI requests, while the default
	 * snapshot is stored as an orientation-neutral size class and follows the
	 * active Java viewfinder orientation.
	 */
	private static final int[][] RESOLUTIONS = {
			{640, 480}, {480, 640},
			{320, 240}, {240, 320},
			{160, 120}, {120, 160},
			{128, 128},
			{176, 144}, {144, 176},
			{100, 60}, {60, 100},
			{352, 288}, {288, 352},
			{800, 600}, {600, 800},
			{1024, 768}, {768, 1024},
			{1280, 960}, {960, 1280},
			{1600, 1200}, {1200, 1600},
			{2048, 1536}, {1536, 2048}
	};

	private int videoResolutionIndex;
	private int stillResolutionIndex;
	private boolean stillResolutionExplicit;
	private int viewfinderWidth;
	private int viewfinderHeight;

	public CameraConfiguration(CaptureRequest request) {
		int videoInitial = request.hasExplicitDimensions()
				? findResolution(request.getWidth(), request.getHeight())
				: findResolution(CaptureRequest.DEFAULT_WIDTH, CaptureRequest.DEFAULT_HEIGHT);
		int stillInitial = findResolution(
				CameraRuntimeConfig.defaultWidth(), CameraRuntimeConfig.defaultHeight());
		videoResolutionIndex = videoInitial >= 0 ? videoInitial : 0;
		stillResolutionIndex = stillInitial >= 0 ? stillInitial : 0;
		viewfinderWidth = request.getWidth();
		viewfinderHeight = request.getHeight();
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
		/*
		 * MMAPI allows an encoding descriptor without dimensions. Put the generic
		 * JPEG descriptor first so it truthfully represents getSnapshot(null): JPEG
		 * is the default format, while the implementation chooses the configured
		 * size-class orientation to match the active Java viewfinder. Exact sizes
		 * remain advertised afterwards for MIDlets that request width/height.
		 */
		StringBuilder result = new StringBuilder("encoding=jpeg");
		int defaultWidth = CameraRuntimeConfig.defaultWidth();
		int defaultHeight = CameraRuntimeConfig.defaultHeight();
		if (CameraRuntimeConfig.acceptsDimensions(defaultWidth, defaultHeight)) {
			appendEncoding(result, defaultWidth, defaultHeight);
		}
		if (defaultWidth != defaultHeight
				&& CameraRuntimeConfig.acceptsDimensions(defaultHeight, defaultWidth)) {
			appendEncoding(result, defaultHeight, defaultWidth);
		}
		for (int[] resolution : RESOLUTIONS) {
			if (!CameraRuntimeConfig.acceptsDimensions(resolution[0], resolution[1])
					|| sameSize(resolution[0], resolution[1], defaultWidth, defaultHeight)
					|| sameSize(resolution[0], resolution[1], defaultHeight, defaultWidth)) {
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
		return supportedIndexOf(RESOLUTIONS[videoResolutionIndex][0], RESOLUTIONS[videoResolutionIndex][1]);
	}

	public synchronized int getStillResolutionIndex() {
		return supportedIndexOf(RESOLUTIONS[stillResolutionIndex][0], RESOLUTIONS[stillResolutionIndex][1]);
	}

	public synchronized void setVideoResolutionIndex(int index) {
		int[] resolution = supportedResolutionAt(index);
		videoResolutionIndex = requireResolution(resolution[0], resolution[1]);
	}

	public synchronized void setStillResolutionIndex(int index) {
		int[] resolution = supportedResolutionAt(index);
		stillResolutionIndex = requireResolution(resolution[0], resolution[1]);
		stillResolutionExplicit = true;
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

	/** Records the actual Java viewfinder size used to orient an unspecified snapshot. */
	public synchronized void setViewfinderSize(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("viewfinder dimensions must be positive");
		}
		viewfinderWidth = width;
		viewfinderHeight = height;
	}

	/**
	 * Applies the selected virtual still size only to an unspecified snapshot.
	 * Explicit MMAPI width/height requests are never rotated or rewritten.
	 */
	public synchronized SnapshotRequest resolveSnapshot(SnapshotRequest request) throws MediaException {
		if (request == null) {
			return null;
		}
		SnapshotRequest resolved = request;
		if (request.isDefaultResolution()) {
			int width = getStillWidth();
			int height = getStillHeight();
			if (!stillResolutionExplicit && width != height) {
				boolean viewfinderPortrait = viewfinderHeight > viewfinderWidth;
				boolean viewfinderLandscape = viewfinderWidth > viewfinderHeight;
				if ((viewfinderPortrait && width > height)
						|| (viewfinderLandscape && height > width)) {
					int swap = width;
					width = height;
					height = swap;
				}
			}
			resolved = new SnapshotRequest(width, height, false, request.getQuality());
		}
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

	private static int supportedIndexOf(int width, int height) {
		int[] flattened = supportedResolutions();
		for (int i = 0; i < flattened.length; i += 2) {
			if (flattened[i] == width && flattened[i + 1] == height) {
				return i / 2;
			}
		}
		return 0;
	}

	private static int requireResolution(int width, int height) {
		int index = findResolution(width, height);
		if (index < 0) {
			throw new IllegalArgumentException("unsupported camera resolution: " + width + "x" + height);
		}
		return index;
	}

	private static int findResolution(int width, int height) {
		for (int i = 0; i < RESOLUTIONS.length; i++) {
			if (RESOLUTIONS[i][0] == width && RESOLUTIONS[i][1] == height) {
				return i;
			}
		}
		return -1;
	}

	private static boolean sameSize(int width, int height, int otherWidth, int otherHeight) {
		return width == otherWidth && height == otherHeight;
	}
}
