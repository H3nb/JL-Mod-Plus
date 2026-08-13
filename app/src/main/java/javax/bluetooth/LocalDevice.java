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

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;

import java.util.Hashtable;

import javax.microedition.io.Connection;
import javax.microedition.util.ActivityResultListener;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.util.BluetoothPermissionHelper;

// Modified for JL-Mod Plus.
public class LocalDevice implements ActivityResultListener {
	private static final String PRIVATE_BLUETOOTH_ADDRESS = "020000000000";
	private static LocalDevice dev;
	private DiscoveryAgent agent;
	private static Hashtable<String, String> properties;
	private volatile boolean lock = false;
	private boolean cancelled = false;
	private Object monitor = new Object();

	static {
		properties = new Hashtable<>();
		properties.put("bluetooth.api.version", "1.1");
		properties.put("bluetooth.master.switch", "true");
		properties.put("bluetooth.sd.attr.retrievable.max", "256");
		properties.put("bluetooth.connected.devices.max", "7");
		properties.put("bluetooth.l2cap.receiveMTU.max", "672");
		properties.put("bluetooth.sd.trans.max", "1");
		properties.put("bluetooth.connected.inquiry.scan", "true");
		properties.put("bluetooth.connected.page.scan", "true");
		properties.put("bluetooth.connected.inquiry", "true");
		properties.put("bluetooth.connected.page", "true");
	}

	private LocalDevice() throws BluetoothStateException {
		agent = new DiscoveryAgent();
		boolean adapterEnabled;
		try {
			adapterEnabled = DiscoveryAgent.adapter.isEnabled();
		} catch (SecurityException e) {
			// Android 12 gates this query behind CONNECT. Defer the capability
			// decision to the operation that actually needs it.
			adapterEnabled = true;
		}
		if (!adapterEnabled) {
			ContextHolder.addActivityResultListener(this);
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			Activity activity;
			try {
				activity = ContextHolder.getActivity();
			} catch (RuntimeException e) {
				ContextHolder.removeActivityResultListener(this);
				throw new BluetoothStateException("Bluetooth activity is unavailable");
			}
			if (activity == null) {
				ContextHolder.removeActivityResultListener(this);
				throw new BluetoothStateException("Bluetooth activity is unavailable");
			}
			try {
				activity.startActivityForResult(enableBtIntent, 2);
			} catch (SecurityException e) {
				ContextHolder.removeActivityResultListener(this);
				throw new BluetoothStateException("Bluetooth could not be enabled");
			}
			synchronized (monitor) {
				try {
					monitor.wait();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					ContextHolder.removeActivityResultListener(this);
					throw new BluetoothStateException("Bluetooth enable request was interrupted");
				}
			}
			if (cancelled) {
				ContextHolder.removeActivityResultListener(this);
				throw new BluetoothStateException();
			}
			cancelled = false;
		}
	}

	public static synchronized LocalDevice getLocalDevice() throws BluetoothStateException {
		if (dev == null)
			dev = new LocalDevice();
		return dev;
	}

	/** Requires the Android capability used by JSR-82 inquiry operations. */
	public static void requireScanPermission() throws BluetoothStateException {
		if (!BluetoothPermissionHelper.ensureScanPermission()) {
			throw new BluetoothStateException("Bluetooth scan permission is not granted");
		}
	}

	/** Requires the Android capability used by JSR-82 connections and SDP. */
	public static void requireConnectPermission() throws BluetoothStateException {
		if (!BluetoothPermissionHelper.ensureConnectPermission()) {
			throw new BluetoothStateException("Bluetooth connect permission is not granted");
		}
	}

	/** Requires the Android capability used by JSR-82 local service publication. */
	public static void requireAdvertisePermission() throws BluetoothStateException {
		if (!BluetoothPermissionHelper.ensureAdvertisePermission()) {
			throw new BluetoothStateException("Bluetooth advertise permission is not granted");
		}
	}

	public static boolean hasScanPermission() {
		return BluetoothPermissionHelper.hasScanPermission();
	}

	public DiscoveryAgent getDiscoveryAgent() {
		return agent;
	}

	public String getFriendlyName() {
		if (!BluetoothPermissionHelper.ensureConnectPermission()) {
			return null;
		}
		try {
			return DiscoveryAgent.adapter.getName();
		} catch (SecurityException e) {
			return null;
		}
	}

	public DeviceClass getDeviceClass() {
		return new DeviceClass();
	}

	public boolean setDiscoverable(int mode) throws BluetoothStateException {
		if ((mode != DiscoveryAgent.GIAC) && (mode != DiscoveryAgent.LIAC) && (mode != DiscoveryAgent.NOT_DISCOVERABLE)
				&& (mode < 0x9E8B00 || mode > 0x9E8B3F)) {
			throw new IllegalArgumentException("Invalid discoverable mode");
		}

		if (lock || mode == DiscoveryAgent.NOT_DISCOVERABLE)
			return true;
		requireAdvertisePermission();

		Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
		ContextHolder.addActivityResultListener(this);
		lock = true;
		try {
			ContextHolder.getActivity().startActivityForResult(discoverableIntent, 1);
		} catch (SecurityException | NullPointerException e) {
			lock = false;
			ContextHolder.removeActivityResultListener(this);
			throw new BluetoothStateException("Bluetooth discoverability request failed");
		}
		return true;
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == 1) {
			synchronized (monitor) {
				lock = false;

				monitor.notifyAll();
			}
		} else if (requestCode == 2) {
			synchronized (monitor) {
				if (resultCode != Activity.RESULT_OK)
					cancelled = true;
				monitor.notifyAll();
			}
		}
	}

	public static boolean isPowerOn() {
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		try {
			return adapter != null && adapter.isEnabled();
		} catch (SecurityException e) {
			return false;
		}
	}

	public int getDiscoverable() {
		if (!BluetoothPermissionHelper.hasConnectPermission()) {
			return DiscoveryAgent.NOT_DISCOVERABLE;
		}
		int scanMode;
		try {
			scanMode = DiscoveryAgent.adapter.getScanMode();
		} catch (SecurityException e) {
			return DiscoveryAgent.NOT_DISCOVERABLE;
		}
		switch (scanMode) {
			case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
				return DiscoveryAgent.LIAC;
			case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
				return DiscoveryAgent.GIAC;
			case BluetoothAdapter.SCAN_MODE_NONE:
			default:
				return DiscoveryAgent.NOT_DISCOVERABLE;
		}
	}

	public static String getProperty(String property) {
		return properties.get(property);
	}

	public String getBluetoothAddress() {
		// Android does not expose the local hardware address to ordinary apps.
		// Its documented privacy address preserves JSR-82's non-null 12-hex contract.
		return PRIVATE_BLUETOOTH_ADDRESS;
	}

	public ServiceRecord getRecord(Connection notifier) {
		if (notifier == null) {
			throw new NullPointerException("notifier is null");
		}
		if (!(notifier instanceof org.microemu.cldc.btspp.Connection || notifier instanceof org.microemu.cldc.btl2cap.Connection))
			throw new java.lang.IllegalArgumentException("not a RFCOMM connection");

		if (notifier instanceof org.microemu.cldc.btspp.Connection) {
			org.microemu.cldc.btspp.Connection conn = (org.microemu.cldc.btspp.Connection) notifier;
			if (conn.socket == null)
				// probably calling this for local device, so socket isn't opened
				return new J2MEServiceRecord(null, conn.connUuid, false, false);
			else {
				try {
					return new J2MEServiceRecord(new RemoteDevice(conn.socket.getRemoteDevice()), conn.connUuid,
							false, false);
				} catch (SecurityException e) {
					return new J2MEServiceRecord(null, conn.connUuid, false, false);
				}
			}
		} else {
			org.microemu.cldc.btl2cap.Connection conn = (org.microemu.cldc.btl2cap.Connection) notifier;
			if (conn.socket == null)
				// probably calling this for local device, so socket isn't opened
				return new J2MEServiceRecord(null, conn.connUuid, false, true);
			else {
				try {
					return new J2MEServiceRecord(new RemoteDevice(conn.socket.getRemoteDevice()), conn.connUuid,
							false, true);
				} catch (SecurityException e) {
					return new J2MEServiceRecord(null, conn.connUuid, false, true);
				}
			}
		}
	}

	// Not supported on Android due to API limitations
	public void updateRecord(ServiceRecord srvRecord) throws ServiceRegistrationException {
		if (srvRecord == null) {
			throw new NullPointerException("Service Record is null");
		}
	}
}
