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

package org.microemu.android.bluetooth;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import javax.bluetooth.BluetoothStateException;
import javax.microedition.util.ContextHolder;

public final class BluetoothPermissions {
	private BluetoothPermissions() {
	}

	public static boolean hasConnectPermission() {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
				|| hasPermission(Manifest.permission.BLUETOOTH_CONNECT);
	}

	public static boolean hasScanPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			return hasPermission(Manifest.permission.BLUETOOTH_SCAN);
		}
		return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
	}

	public static boolean hasScanModePermission() {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
				|| hasPermission(Manifest.permission.BLUETOOTH_SCAN);
	}

	public static boolean hasAdvertisePermission() {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
				|| hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE);
	}

	public static boolean requestConnectPermission() {
		if (hasConnectPermission()) {
			return true;
		}
		return ContextHolder.requestPermission(Manifest.permission.BLUETOOTH_CONNECT);
	}

	public static void requireConnectPermission() throws BluetoothStateException {
		if (requestConnectPermission()) {
			return;
		}
		throw new BluetoothStateException("Bluetooth connect permission was not granted");
	}

	public static void requireJsr82Permissions() throws BluetoothStateException {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
			return;
		}
		if (hasConnectPermission() && hasScanPermission() && hasAdvertisePermission()) {
			return;
		}
		String[] permissions = {
				Manifest.permission.BLUETOOTH_CONNECT,
				Manifest.permission.BLUETOOTH_SCAN,
				Manifest.permission.BLUETOOTH_ADVERTISE,
		};
		if (ContextHolder.requestPermissions(permissions)) {
			return;
		}
		throw new BluetoothStateException("Nearby devices permission was not granted");
	}

	public static void requireScanPermission() throws BluetoothStateException {
		if (hasScanPermission()) {
			return;
		}
		String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				? Manifest.permission.BLUETOOTH_SCAN
				: Manifest.permission.ACCESS_FINE_LOCATION;
		if (ContextHolder.requestPermission(permission)) {
			return;
		}
		throw new BluetoothStateException("Bluetooth scan permission was not granted");
	}

	public static void requireAdvertisePermission() throws BluetoothStateException {
		if (hasAdvertisePermission()) {
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				&& ContextHolder.requestPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
			return;
		}
		throw new BluetoothStateException("Bluetooth advertise permission was not granted");
	}

	private static boolean hasPermission(String permission) {
		return ContextCompat.checkSelfPermission(ContextHolder.getAppContext(), permission)
				== PackageManager.PERMISSION_GRANTED;
	}
}
