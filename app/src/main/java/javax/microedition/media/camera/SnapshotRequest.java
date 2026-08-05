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

/** Immutable JPEG snapshot request. */
public final class SnapshotRequest {
	private final int width;
	private final int height;
	private final boolean defaultResolution;
	private final int quality;

	SnapshotRequest(int width, int height) {
		this(width, height, false);
	}

	SnapshotRequest(int width, int height, boolean defaultResolution) {
		this(width, height, defaultResolution, 90);
	}

	SnapshotRequest(int width, int height, boolean defaultResolution, int quality) {
		this.width = width;
		this.height = height;
		this.defaultResolution = defaultResolution;
		this.quality = quality;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public boolean isDefaultResolution() {
		return defaultResolution;
	}

	public int getQuality() {
		return quality;
	}

	public SnapshotRequest withQuality(int newQuality) {
		if (newQuality < 1 || newQuality > 100) {
			throw new IllegalArgumentException("JPEG quality must be between 1 and 100");
		}
		return new SnapshotRequest(width, height, defaultResolution, newQuality);
	}
}
