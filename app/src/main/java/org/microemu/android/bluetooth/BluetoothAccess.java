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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import javax.bluetooth.BluetoothStateException;

/**
 * Keeps Android runtime-permission checks next to the protected platform calls.
 */
public final class BluetoothAccess {
	private BluetoothAccess() {
	}

	public static BluetoothAdapter requireAdapter() throws BluetoothStateException {
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		if (adapter == null) {
			throw new BluetoothStateException("Bluetooth is not supported on this device");
		}
		return adapter;
	}

	@SuppressLint("MissingPermission")
	public static boolean isEnabled(BluetoothAdapter adapter) throws BluetoothStateException {
		BluetoothPermissions.requireConnectPermission();
		try {
			return adapter.isEnabled();
		} catch (SecurityException e) {
			throw permissionFailure("read Bluetooth state", e);
		}
	}

	@SuppressLint("MissingPermission")
	public static boolean isEnabledOrFalse(BluetoothAdapter adapter) {
		if (adapter == null || !BluetoothPermissions.hasConnectPermission()) {
			return false;
		}
		try {
			return adapter.isEnabled();
		} catch (SecurityException e) {
			return false;
		}
	}

	@SuppressLint("MissingPermission")
	public static void cancelDiscoveryIfActive(BluetoothAdapter adapter)
			throws BluetoothStateException {
		BluetoothPermissions.requireScanPermission();
		try {
			if (adapter.isDiscovering()) {
				adapter.cancelDiscovery();
			}
		} catch (SecurityException e) {
			throw permissionFailure("cancel Bluetooth discovery", e);
		}
	}

	@SuppressLint("MissingPermission")
	public static void cancelDiscoveryIfPermitted(BluetoothAdapter adapter) {
		if (!BluetoothPermissions.hasScanPermission()) {
			return;
		}
		try {
			if (adapter.isDiscovering()) {
				adapter.cancelDiscovery();
			}
		} catch (SecurityException ignored) {
			// A permission can be revoked between the explicit check and this call.
		}
	}

	@SuppressLint("MissingPermission")
	public static boolean isDiscovering(BluetoothAdapter adapter)
			throws BluetoothStateException {
		BluetoothPermissions.requireScanPermission();
		try {
			return adapter.isDiscovering();
		} catch (SecurityException e) {
			throw permissionFailure("read Bluetooth discovery state", e);
		}
	}

	@SuppressLint("MissingPermission")
	public static boolean startDiscovery(BluetoothAdapter adapter)
			throws BluetoothStateException {
		BluetoothPermissions.requireScanPermission();
		try {
			return adapter.startDiscovery();
		} catch (SecurityException e) {
			throw permissionFailure("start Bluetooth discovery", e);
		}
	}

	@SuppressLint("MissingPermission")
	public static boolean cancelDiscovery(BluetoothAdapter adapter) {
		if (!BluetoothPermissions.hasScanPermission()) {
			return false;
		}
		try {
			return adapter.cancelDiscovery();
		} catch (SecurityException e) {
			return false;
		}
	}

	@SuppressLint("MissingPermission")
	public static Set<BluetoothDevice> getBondedDevices(BluetoothAdapter adapter) {
		if (!BluetoothPermissions.hasConnectPermission()) {
			return Collections.emptySet();
		}
		try {
			return adapter.getBondedDevices();
		} catch (SecurityException e) {
			return Collections.emptySet();
		}
	}

	@SuppressLint("MissingPermission")
	public static boolean fetchUuidsWithSdp(BluetoothDevice device)
			throws BluetoothStateException {
		BluetoothPermissions.requireConnectPermission();
		try {
			return device.fetchUuidsWithSdp();
		} catch (SecurityException e) {
			throw permissionFailure("search Bluetooth services", e);
		}
	}

	@SuppressLint("MissingPermission")
	public static BluetoothServerSocket listenUsingRfcomm(
			BluetoothAdapter adapter,
			String serviceName,
			java.util.UUID uuid,
			boolean secure
	) throws IOException {
		BluetoothPermissions.requireConnectPermission();
		try {
			return secure
					? adapter.listenUsingRfcommWithServiceRecord(serviceName, uuid)
					: adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid);
		} catch (SecurityException e) {
			throw permissionFailure("open a Bluetooth server", e);
		}
	}

	@SuppressLint("MissingPermission")
	public static BluetoothSocket createRfcommSocket(
			BluetoothDevice device,
			java.util.UUID uuid,
			boolean secure
	) throws IOException {
		BluetoothPermissions.requireConnectPermission();
		try {
			return secure
					? device.createRfcommSocketToServiceRecord(uuid)
					: device.createInsecureRfcommSocketToServiceRecord(uuid);
		} catch (SecurityException e) {
			throw permissionFailure("create a Bluetooth connection", e);
		}
	}

	@SuppressLint("MissingPermission")
	public static void connect(BluetoothSocket socket) throws IOException {
		BluetoothPermissions.requireConnectPermission();
		try {
			socket.connect();
		} catch (SecurityException e) {
			throw permissionFailure("connect to a Bluetooth device", e);
		}
	}

	@SuppressLint("MissingPermission")
	public static String getAdapterName(BluetoothAdapter adapter) {
		if (!BluetoothPermissions.hasConnectPermission()) {
			return null;
		}
		try {
			return adapter.getName();
		} catch (SecurityException e) {
			return null;
		}
	}

	@SuppressLint("MissingPermission")
	public static int getScanMode(BluetoothAdapter adapter) {
		if (!BluetoothPermissions.hasScanModePermission()) {
			return BluetoothAdapter.SCAN_MODE_NONE;
		}
		try {
			return adapter.getScanMode();
		} catch (SecurityException e) {
			return BluetoothAdapter.SCAN_MODE_NONE;
		}
	}

	@SuppressLint("MissingPermission")
	public static String getDeviceName(BluetoothDevice device) {
		if (!BluetoothPermissions.hasConnectPermission()) {
			return null;
		}
		try {
			return device.getName();
		} catch (SecurityException e) {
			return null;
		}
	}

	@SuppressLint("MissingPermission")
	public static String getDeviceAddress(BluetoothDevice device) {
		if (!BluetoothPermissions.hasConnectPermission()) {
			return null;
		}
		try {
			return device.getAddress();
		} catch (SecurityException e) {
			return null;
		}
	}

	@SuppressLint("MissingPermission")
	public static BluetoothDevice getRemoteDevice(BluetoothSocket socket) throws IOException {
		BluetoothPermissions.requireConnectPermission();
		try {
			return socket.getRemoteDevice();
		} catch (SecurityException e) {
			throw permissionFailure("read the remote Bluetooth device", e);
		}
	}

	@SuppressLint("MissingPermission")
	public static BluetoothDevice getRemoteDeviceOrNull(BluetoothSocket socket) {
		if (!BluetoothPermissions.hasConnectPermission()) {
			return null;
		}
		try {
			return socket.getRemoteDevice();
		} catch (SecurityException e) {
			return null;
		}
	}

	@SuppressLint("MissingPermission")
	public static void requestEnable(Activity activity, int requestCode)
			throws BluetoothStateException {
		BluetoothPermissions.requireConnectPermission();
		if (activity == null) {
			throw new BluetoothStateException("Bluetooth cannot be enabled without an active screen");
		}
		try {
			activity.startActivityForResult(
					new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
					requestCode
			);
		} catch (SecurityException e) {
			throw permissionFailure("request Bluetooth activation", e);
		}
	}

	@SuppressLint("MissingPermission")
	public static void requestDiscoverable(
			Activity activity,
			int requestCode,
			int durationSeconds
	)
			throws BluetoothStateException {
		BluetoothPermissions.requireAdvertisePermission();
		if (activity == null) {
			throw new BluetoothStateException("Discoverable mode requires an active screen");
		}
		try {
			Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSeconds);
			activity.startActivityForResult(
					intent,
					requestCode
			);
		} catch (SecurityException e) {
			throw permissionFailure("request discoverable mode", e);
		}
	}

	private static BluetoothStateException permissionFailure(
			String operation,
			SecurityException cause
	) {
		BluetoothStateException exception =
				new BluetoothStateException("Permission denied while attempting to " + operation);
		exception.initCause(cause);
		return exception;
	}
}
