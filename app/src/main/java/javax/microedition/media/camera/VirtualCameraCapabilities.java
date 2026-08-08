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

import android.content.pm.PackageManager;

import javax.microedition.util.ContextHolder;

/** Runtime-owned multimedia capabilities exposed to converted MIDlets. */
public final class VirtualCameraCapabilities {
	/** Recording encoding; still-image formats are exposed through video.snapshot.encodings. */
	public static final String VIDEO_ENCODING =
			"encoding=" + CaptureRequest.VIDEO_RECORDING_CONTENT_TYPE;
	/** Standalone capture://audio format exposed through the MMAPI audio encoding properties. */
	public static final String AUDIO_ENCODING = "encoding=audio/amr&rate=8000&channels=1";

	private VirtualCameraCapabilities() {
	}

	public static boolean hasCameraFeature() {
		try {
			return ContextHolder.getAppContext().getPackageManager()
					.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
		} catch (RuntimeException e) {
			return false;
		}
	}

	public static boolean hasMicrophoneFeature() {
		try {
			return ContextHolder.getAppContext().getPackageManager()
					.hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** True for properties that must reflect the current emulator capability. */
	public static boolean isManagedProperty(String key) {
		if (key == null) {
			return false;
		}
		return switch (key) {
			case "supports.video.capture", "supports.audio.capture", "supports.recording",
					"audio.encoding", "audio.encodings", "video.encoding", "video.encodings",
					"video.snapshot.encoding", "video.snapshot.encodings" -> true;
			default -> false;
		};
	}

	/** Returns emulator-owned Java ME multimedia properties, or null for unrelated keys. */
	public static String systemProperty(String key) {
		if (key == null) {
			return null;
		}
		return switch (key) {
			case "supports.video.capture" -> Boolean.toString(hasCameraFeature());
			case "supports.audio.capture" -> Boolean.toString(hasMicrophoneFeature());
			case "supports.recording" ->
					Boolean.toString(hasMicrophoneFeature() || hasCameraFeature());
			case "audio.encoding", "audio.encodings" ->
					hasMicrophoneFeature() ? AUDIO_ENCODING : null;
			case "video.encoding", "video.encodings" ->
					hasCameraFeature() ? VIDEO_ENCODING : null;
			case "video.snapshot.encoding", "video.snapshot.encodings" ->
					hasCameraFeature() ? CameraConfiguration.snapshotEncodings() : null;
			default -> null;
		};
	}
}
