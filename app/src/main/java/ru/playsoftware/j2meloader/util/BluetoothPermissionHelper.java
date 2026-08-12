/*
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

// Modified for JL-Mod Plus.
package ru.playsoftware.j2meloader.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import javax.microedition.util.ContextHolder;

/**
 * Maps Android's versioned Bluetooth permissions to the operations exposed by
 * JSR-82. Permission checks intentionally happen at each operation boundary so
 * revocation after a MIDlet has started is handled as well as the initial grant.
 */
public final class BluetoothPermissionHelper {
	private static final int REQUEST_CODE = 0;

	private BluetoothPermissionHelper() {
	}

	public static boolean hasScanPermission() {
		return hasPermission(scanPermission());
	}

	public static boolean hasConnectPermission() {
		return hasPermission(connectPermission());
	}

	public static boolean hasAdvertisePermission() {
		return hasPermission(advertisePermission());
	}

	public static boolean ensureScanPermission() {
		return ensurePermission(scanPermission());
	}

	public static boolean ensureConnectPermission() {
		return ensurePermission(connectPermission());
	}

	public static boolean ensureAdvertisePermission() {
		return ensurePermission(advertisePermission());
	}

	private static String scanPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			return Manifest.permission.BLUETOOTH_SCAN;
		}
		return Manifest.permission.ACCESS_FINE_LOCATION;
	}

	private static String connectPermission() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				? Manifest.permission.BLUETOOTH_CONNECT : null;
	}

	private static String advertisePermission() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				? Manifest.permission.BLUETOOTH_ADVERTISE : null;
	}

	private static boolean hasPermission(String permission) {
		if (permission == null) {
			return true;
		}
		Context context = permissionContext();
		return context != null
				&& ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
	}

	private static boolean ensurePermission(String permission) {
		if (permission == null || hasPermission(permission)) {
			return true;
		}
		Activity activity = currentActivity();
		if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
			return false;
		}
		ActivityCompat.requestPermissions(activity, new String[]{permission}, REQUEST_CODE);
		return false;
	}

	private static Context permissionContext() {
		Activity activity = currentActivity();
		if (activity != null) {
			return activity;
		}
		try {
			return ContextHolder.getAppContext();
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static Activity currentActivity() {
		try {
			return ContextHolder.getActivity();
		} catch (RuntimeException ignored) {
			return null;
		}
	}
}
