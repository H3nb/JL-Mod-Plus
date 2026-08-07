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

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.microedition.shell.MicroActivity;
import javax.microedition.shell.MidletPermissionDialogState;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.Config;

/** MIDP-facing permission gate for privacy-sensitive MMAPI snapshot capture. */
public final class MidletMediaPermissionGate {
	public static final String SNAPSHOT_PERMISSION =
			"javax.microedition.media.control.VideoControl.getSnapshot";
	private static final String PREFS = "midlet_media_permissions";
	private static final String SNAPSHOT_PREFIX = "snapshot:";

	private static WeakReference<MicroActivity> sessionActivity = new WeakReference<>(null);
	private static String sessionSnapshotGrant;

	private MidletMediaPermissionGate() {
	}

	public static void requireSnapshotPermission() {
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null) {
			throw new SecurityException("MIDlet Activity is unavailable for camera permission");
		}
		String key = SNAPSHOT_PREFIX + currentMidletIdentity(activity);
		SharedPreferences preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		if (hasSessionGrant(activity, key) || preferences.getBoolean(key, false)) {
			return;
		}

		MidletPermissionDialogState.Result result = MidletPermissionDialogState.request(
				activity,
				activity.getString(R.string.camera_permission_title),
				activity.getString(R.string.camera_permission_message),
				activity.getString(R.string.camera_permission_allow_once),
				activity.getString(R.string.camera_permission_always_allow),
				activity.getString(R.string.camera_permission_deny));
		if (result == MidletPermissionDialogState.Result.ALWAYS_ALLOW) {
			preferences.edit().putBoolean(key, true).apply();
			setSessionGrant(activity, key);
			return;
		}
		if (result == MidletPermissionDialogState.Result.ALLOW_ONCE) {
			setSessionGrant(activity, key);
			return;
		}
		throw new SecurityException("MIDlet camera snapshot permission was denied");
	}

	/** MIDP checkPermission-compatible state: 1 allowed, -1 unknown/ask. */
	public static int checkSnapshotPermission() {
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null) {
			return -1;
		}
		String key = SNAPSHOT_PREFIX + currentMidletIdentity(activity);
		if (hasSessionGrant(activity, key)) {
			return 1;
		}
		return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
				.getBoolean(key, false) ? 1 : -1;
	}

	private static synchronized boolean hasSessionGrant(MicroActivity activity, String key) {
		return sessionActivity.get() == activity && key.equals(sessionSnapshotGrant);
	}

	private static synchronized void setSessionGrant(MicroActivity activity, String key) {
		sessionActivity = new WeakReference<>(activity);
		sessionSnapshotGrant = key;
	}

	private static String currentMidletIdentity(MicroActivity activity) {
		Uri data = activity.getIntent() == null ? null : activity.getIntent().getData();
		String path = data == null ? null : data.getPath();
		if (path != null && !path.isEmpty()) {
			String resourceName = Config.MIDLET_RES_FILE.startsWith("/")
					? Config.MIDLET_RES_FILE.substring(1) : Config.MIDLET_RES_FILE;
			File jar = new File(new File(path), resourceName);
			String digest = sha256(jar);
			if (digest != null) {
				return "sha256:" + digest;
			}
		}
		return "path:" + (data == null ? "unknown" : data.toString());
	}

	private static String sha256(File file) {
		if (!file.isFile()) {
			return null;
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			try (FileInputStream input = new FileInputStream(file)) {
				int count;
				while ((count = input.read(buffer)) != -1) {
					digest.update(buffer, 0, count);
				}
			}
			byte[] value = digest.digest();
			StringBuilder hex = new StringBuilder(value.length * 2);
			for (byte b : value) {
				hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
			}
			return hex.toString();
		} catch (IOException | NoSuchAlgorithmException e) {
			return null;
		}
	}
}
