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

/** Virtual camera capabilities exposed to the converted MIDlet. */
public final class VirtualCameraCapabilities {
	public static final String VIDEO_ENCODING = "encoding=jpeg";
	public static final String AUDIO_ENCODING = "encoding=amr-wb";
	public static final String SNAPSHOT_ENCODINGS =
					"encoding=jpeg&width=480&height=640 "
					+ "encoding=jpeg&width=240&height=320 "
					+ "encoding=jpeg&width=960&height=1280 "
					+ "encoding=jpeg&width=1536&height=2048";

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

	/**
	 * Returns the camera-related Java ME system properties handled by the
	 * emulator, or {@code null} for unrelated keys.
	 */
	public static String systemProperty(String key) {
		if (key == null) {
			return null;
		}
		return switch (key) {
			case "supports.video.capture" -> Boolean.toString(hasCameraFeature());
			case "supports.audio.capture" -> Boolean.toString(hasMicrophoneFeature());
			case "supports.recording" -> Boolean.toString(hasCameraFeature() || hasMicrophoneFeature());
			case "audio.encoding", "audio.encodings" ->
					hasMicrophoneFeature() ? AUDIO_ENCODING : null;
			case "video.encoding", "video.encodings" ->
					hasCameraFeature() ? VIDEO_ENCODING : null;
			case "video.snapshot.encoding", "video.snapshot.encodings" ->
					hasCameraFeature() ? SNAPSHOT_ENCODINGS : null;
			default -> null;
		};
	}
}
