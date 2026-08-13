/*
 * Copyright 2018 cerg2010cerg2010
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

package javax.bluetooth;

import android.bluetooth.BluetoothDevice;

import java.io.IOException;

import javax.microedition.io.Connection;

import ru.playsoftware.j2meloader.util.BluetoothPermissionHelper;

public class RemoteDevice {
	BluetoothDevice dev;
	private final String address;

	RemoteDevice(BluetoothDevice dev) {
		this.dev = dev;
		this.address = addressOf(dev);
	}

	static String javaToAndroidAddress(String addr) {
		StringBuilder sb = new StringBuilder(addr);
		for (int i = 2; i < sb.length(); i += 3)
			sb.insert(i, ':');
		return sb.toString();
	}

	protected RemoteDevice(String address) {
		if (address == null) {
			throw new NullPointerException("address is null");
		}

		this.address = normalizeAddress(address);
		if (this.address.length() != 12) {
			throw new IllegalArgumentException("Invalid Bluetooth address");
		}
		dev = DiscoveryAgent.adapter.getRemoteDevice(javaToAndroidAddress(this.address));
	}

	public String getFriendlyName(boolean alwaysAsk) throws IOException {
		if (dev == null || !BluetoothPermissionHelper.ensureConnectPermission()) {
			throw new BluetoothStateException("Bluetooth connect permission is not granted");
		}
		String name;
		try {
			name = dev.getName();
		} catch (SecurityException e) {
			throw new BluetoothStateException("Bluetooth connect permission was revoked");
		}
		if (name == null) {
			name =  "";
		}
		return name;
	}

	public final String getBluetoothAddress() {
		return address;
	}

	private static String addressOf(BluetoothDevice device) {
		if (device == null) {
			return "";
		}
		try {
			return normalizeAddress(device.getAddress());
		} catch (SecurityException e) {
			// BluetoothDevice.toString() is its cached address on Android and remains
			// available when a permission is revoked between discovery and wrapping.
			return normalizeAddress(device.toString());
		}
	}

	private static String normalizeAddress(String value) {
		if (value == null) {
			return "";
		}
		String normalized = value.replace(":", "").toUpperCase(java.util.Locale.ROOT);
		return normalized.matches("[0-9A-F]{12}") ? normalized : "";
	}

	public boolean equals(Object obj) {
		if (obj == null || !(obj instanceof RemoteDevice))
			return false;
		return address.equals(((RemoteDevice) obj).address);
	}

	public int hashCode() {
		return address.hashCode();
	}

	public static RemoteDevice getRemoteDevice(Connection conn) throws IOException {
		if (conn == null)
			throw new NullPointerException("conn is null");
		if (!(conn instanceof org.microemu.cldc.btspp.SPPConnectionImpl
				|| conn instanceof org.microemu.cldc.btl2cap.L2CAPConnectionImpl))
			throw new java.lang.IllegalArgumentException("not a RFCOMM connection");
		if (!BluetoothPermissionHelper.ensureConnectPermission()) {
			throw new BluetoothStateException("Bluetooth connect permission is not granted");
		}

		if (conn instanceof org.microemu.cldc.btspp.SPPConnectionImpl) {
			org.microemu.cldc.btspp.SPPConnectionImpl connection =
					(org.microemu.cldc.btspp.SPPConnectionImpl) conn;
			if (connection.socket == null)
				throw new IOException("socket is null");
			try {
				return new RemoteDevice(connection.socket.getRemoteDevice());
			} catch (SecurityException e) {
				throw new BluetoothStateException("Bluetooth connect permission was revoked");
			}
		} else {
			org.microemu.cldc.btl2cap.L2CAPConnectionImpl connection =
					(org.microemu.cldc.btl2cap.L2CAPConnectionImpl) conn;
			if (connection.socket == null)
				throw new IOException("socket is null");
			try {
				return new RemoteDevice(connection.socket.getRemoteDevice());
			} catch (SecurityException e) {
				throw new BluetoothStateException("Bluetooth connect permission was revoked");
			}
		}
	}

	public boolean authenticate() throws IOException {
		return false;
	}

	public boolean authorize(javax.microedition.io.Connection conn) throws IOException {
		return false;
	}

	public boolean encrypt(javax.microedition.io.Connection conn, boolean on) throws IOException {
		return false;
	}

	public boolean isAuthenticated() {
		return false;
	}

	public boolean isAuthorized(javax.microedition.io.Connection conn) throws IOException {
		return false;
	}

	public boolean isEncrypted() {
		return false;
	}

	public boolean isTrustedDevice() {
		return false;
	}
}
