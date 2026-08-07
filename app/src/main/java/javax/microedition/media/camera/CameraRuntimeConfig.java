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

import android.content.SharedPreferences;
import android.net.Uri;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.lang.ref.WeakReference;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.config.ProfilesManager;

/**
 * Camera policy snapshot for the currently running MIDlet. Global preferences
 * and the MIDlet profile are combined once per host Activity launch so a
 * running Player sees stable capabilities while a relaunch picks up edits.
 */
public final class CameraRuntimeConfig {
	public static final String PREF_DEFAULT_DEVICE = "pref_camera_default_device";
	public static final String PREF_DEFAULT_SNAPSHOT = "pref_camera_default_snapshot";
	public static final String PREF_MAX_SNAPSHOT = "pref_camera_max_snapshot";
	public static final String PREF_JPEG_QUALITY = "pref_camera_jpeg_quality";

	private static final int DEFAULT_WIDTH = 640;
	private static final int DEFAULT_HEIGHT = 480;
	private static final int MAX_WIDTH = 2048;
	private static final int MAX_HEIGHT = 1536;
	private static final int DEFAULT_QUALITY = 90;

	private static WeakReference<MicroActivity> installedActivity = new WeakReference<>(null);
	private static volatile String installedIdentity;
	private static volatile State installedState = State.defaults();

	private CameraRuntimeConfig() {
	}

	public static LogicalCameraDevice defaultDevice() {
		return state().device;
	}

	public static int defaultWidth() {
		return state().defaultWidth;
	}

	public static int defaultHeight() {
		return state().defaultHeight;
	}

	public static int maxWidth() {
		return state().maxWidth;
	}

	public static int maxHeight() {
		return state().maxHeight;
	}

	public static int jpegQuality() {
		return state().jpegQuality;
	}

	/** Max policy is orientation-neutral: both 2048x1536 and 1536x2048 fit. */
	public static boolean acceptsDimensions(int width, int height) {
		State state = state();
		int maxAxis = Math.max(state.maxWidth, state.maxHeight);
		int minAxis = Math.min(state.maxWidth, state.maxHeight);
		int requestMax = Math.max(width, height);
		int requestMin = Math.min(width, height);
		return width > 0 && height > 0
				&& requestMax <= maxAxis
				&& requestMin <= minAxis
				&& (long) width * height <= (long) state.maxWidth * state.maxHeight;
	}

	static LogicalCameraDevice resolveProfileDevice(int profileDevice,
			LogicalCameraDevice globalDevice) {
		return switch (profileDevice) {
			case ProfileModel.CAMERA_DEVICE_AUTO -> LogicalCameraDevice.DEFAULT;
			case ProfileModel.CAMERA_DEVICE_REAR -> LogicalCameraDevice.REAR;
			case ProfileModel.CAMERA_DEVICE_FRONT -> LogicalCameraDevice.FRONT;
			default -> globalDevice;
		};
	}

	private static State state() {
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null) {
			return State.defaults();
		}
		String identity = activityIdentity(activity);
		if (installedActivity.get() == activity && identity != null && identity.equals(installedIdentity)) {
			return installedState;
		}
		synchronized (CameraRuntimeConfig.class) {
			if (installedActivity.get() != activity
					|| identity == null || !identity.equals(installedIdentity)) {
				installedState = load(activity);
				installedIdentity = identity;
				installedActivity = new WeakReference<>(activity);
			}
			return installedState;
		}
	}

	private static State load(MicroActivity activity) {
		State global = loadGlobal();
		ProfileModel profile = loadProfile(activity);
		if (profile == null || !profile.cameraOverrideEnabled) {
			return global;
		}
		LogicalCameraDevice device = resolveProfileDevice(profile.cameraDefaultDevice, global.device);
		int defaultWidth = validDimension(profile.cameraDefaultSnapshotWidth)
				? profile.cameraDefaultSnapshotWidth : global.defaultWidth;
		int defaultHeight = validDimension(profile.cameraDefaultSnapshotHeight)
				? profile.cameraDefaultSnapshotHeight : global.defaultHeight;
		int maxWidth = validDimension(profile.cameraMaximumSnapshotWidth)
				? profile.cameraMaximumSnapshotWidth : global.maxWidth;
		int maxHeight = validDimension(profile.cameraMaximumSnapshotHeight)
				? profile.cameraMaximumSnapshotHeight : global.maxHeight;
		int quality = profile.cameraJpegQuality >= 1 && profile.cameraJpegQuality <= 100
				? profile.cameraJpegQuality : global.jpegQuality;
		return sanitize(new State(device, defaultWidth, defaultHeight, maxWidth, maxHeight, quality));
	}

	private static State loadGlobal() {
		try {
			SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(
					ContextHolder.getAppContext());
			LogicalCameraDevice device = switch (preferences.getString(PREF_DEFAULT_DEVICE, "auto")) {
				case "rear" -> LogicalCameraDevice.REAR;
				case "front" -> LogicalCameraDevice.FRONT;
				default -> LogicalCameraDevice.DEFAULT;
			};
			int[] defaultSize = parseSize(preferences.getString(
					PREF_DEFAULT_SNAPSHOT, DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT),
					DEFAULT_WIDTH, DEFAULT_HEIGHT);
			int[] maxSize = parseSize(preferences.getString(
					PREF_MAX_SNAPSHOT, MAX_WIDTH + "x" + MAX_HEIGHT), MAX_WIDTH, MAX_HEIGHT);
			int quality = preferences.getInt(PREF_JPEG_QUALITY, DEFAULT_QUALITY);
			State sanitized = sanitize(new State(device, defaultSize[0], defaultSize[1],
					maxSize[0], maxSize[1], quality));
			if (sanitized.defaultWidth != defaultSize[0] || sanitized.defaultHeight != defaultSize[1]) {
				preferences.edit().putString(PREF_DEFAULT_SNAPSHOT,
						sanitized.defaultWidth + "x" + sanitized.defaultHeight).apply();
			}
			if (sanitized.maxWidth != maxSize[0] || sanitized.maxHeight != maxSize[1]) {
				preferences.edit().putString(PREF_MAX_SNAPSHOT,
						sanitized.maxWidth + "x" + sanitized.maxHeight).apply();
			}
			if (sanitized.jpegQuality != quality) {
				preferences.edit().putInt(PREF_JPEG_QUALITY, sanitized.jpegQuality).apply();
			}
			return sanitized;
		} catch (RuntimeException e) {
			return State.defaults();
		}
	}

	private static ProfileModel loadProfile(MicroActivity activity) {
		if (activity.getIntent() == null) {
			return null;
		}
		Uri data = activity.getIntent().getData();
		String path = data == null ? null : data.getPath();
		if (path == null || path.isEmpty()) {
			return null;
		}
		File app = new File(path);
		return ProfilesManager.loadConfig(new File(Config.getConfigsDir(), app.getName()));
	}

	private static String activityIdentity(MicroActivity activity) {
		if (activity.getIntent() == null) {
			return null;
		}
		Uri data = activity.getIntent().getData();
		return data == null ? null : data.toString();
	}

	private static State sanitize(State value) {
		int quality = Math.max(1, Math.min(100, value.jpegQuality));
		int maxWidth = validDimension(value.maxWidth) ? value.maxWidth : MAX_WIDTH;
		int maxHeight = validDimension(value.maxHeight) ? value.maxHeight : MAX_HEIGHT;
		int defaultWidth = validDimension(value.defaultWidth) ? value.defaultWidth : DEFAULT_WIDTH;
		int defaultHeight = validDimension(value.defaultHeight) ? value.defaultHeight : DEFAULT_HEIGHT;
		State sanitized = new State(value.device, defaultWidth, defaultHeight,
				maxWidth, maxHeight, quality);
		return accepts(sanitized, defaultWidth, defaultHeight) ? sanitized
				: new State(value.device, DEFAULT_WIDTH, DEFAULT_HEIGHT, maxWidth, maxHeight, quality);
	}

	private static boolean accepts(State state, int width, int height) {
		int maxAxis = Math.max(state.maxWidth, state.maxHeight);
		int minAxis = Math.min(state.maxWidth, state.maxHeight);
		return Math.max(width, height) <= maxAxis
				&& Math.min(width, height) <= minAxis
				&& (long) width * height <= (long) state.maxWidth * state.maxHeight;
	}

	private static int[] parseSize(String text, int fallbackWidth, int fallbackHeight) {
		if (text != null) {
			String normalized = text.toLowerCase(java.util.Locale.ROOT).replace('×', 'x');
			int separator = normalized.indexOf('x');
			if (separator > 0 && separator < normalized.length() - 1) {
				try {
					int width = Integer.parseInt(normalized.substring(0, separator).trim());
					int height = Integer.parseInt(normalized.substring(separator + 1).trim());
					if (validDimension(width) && validDimension(height)) {
						return new int[]{width, height};
					}
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return new int[]{fallbackWidth, fallbackHeight};
	}

	private static boolean validDimension(int value) {
		return value > 0 && value <= CaptureRequest.MAX_WIDTH;
	}

	private record State(LogicalCameraDevice device, int defaultWidth, int defaultHeight,
			int maxWidth, int maxHeight, int jpegQuality) {
		static State defaults() {
			return new State(LogicalCameraDevice.DEFAULT, DEFAULT_WIDTH, DEFAULT_HEIGHT,
					MAX_WIDTH, MAX_HEIGHT, DEFAULT_QUALITY);
		}
	}
}
