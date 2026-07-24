/*
 * Copyright 2018 cerg2010cerg2010
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

package javax.bluetooth;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;

import org.microemu.android.bluetooth.BluetoothAccess;
import org.microemu.android.bluetooth.BluetoothPermissions;

import java.util.Hashtable;

import javax.microedition.io.Connection;
import javax.microedition.util.ActivityResultListener;
import javax.microedition.util.ContextHolder;

public class LocalDevice implements ActivityResultListener {
	private static final int REQUEST_DISCOVERABLE = 0xB100;
	private static final int REQUEST_ENABLE = 0xB101;
	private static final int DISCOVERABLE_DURATION_SECONDS = 120;
	private static final long DISCOVERABLE_REQUEST_TIMEOUT_MS = 5 * 60_000L;
	private static LocalDevice dev;
	private DiscoveryAgent agent;
	private static Hashtable<String, String> properties;
	private boolean cancelled = false;
	private boolean enableRequestPending = false;
	private boolean discoverableRequestPending = false;
	private int discoverableResultCode = Activity.RESULT_CANCELED;
	private int acceptedDiscoverableMode = DiscoveryAgent.NOT_DISCOVERABLE;
	private long acceptedDiscoverableUntil = 0;
	private final Object monitor = new Object();

	static {
		properties = new Hashtable<>();
		properties.put("bluetooth.api.version", "1.1.1");
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
		BluetoothPermissions.requireJsr82Permissions();
		ContextHolder.addActivityResultListener(this);
		if (!BluetoothAccess.isEnabled(DiscoveryAgent.adapter)) {
			synchronized (monitor) {
				enableRequestPending = true;
			}
			try {
				BluetoothAccess.requestEnable(ContextHolder.getActivity(), REQUEST_ENABLE);
			} catch (BluetoothStateException e) {
				synchronized (monitor) {
					enableRequestPending = false;
				}
				ContextHolder.removeActivityResultListener(this);
				throw e;
			}
			synchronized (monitor) {
				try {
					while (enableRequestPending) {
						monitor.wait();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					ContextHolder.removeActivityResultListener(this);
					throw new BluetoothStateException("Interrupted while enabling Bluetooth");
				}
			}
			if (cancelled || !BluetoothAccess.isEnabled(DiscoveryAgent.adapter)) {
				ContextHolder.removeActivityResultListener(this);
				throw new BluetoothStateException("Bluetooth was not enabled");
			}
			cancelled = false;
		}
	}

	public static synchronized LocalDevice getLocalDevice() throws BluetoothStateException {
		if (dev == null)
			dev = new LocalDevice();
		return dev;
	}

	public DiscoveryAgent getDiscoveryAgent() {
		return agent;
	}

	public String getFriendlyName() {
		return BluetoothAccess.getAdapterName(DiscoveryAgent.adapter);
	}

	public DeviceClass getDeviceClass() {
		return new DeviceClass();
	}

	public boolean setDiscoverable(int mode) throws BluetoothStateException {
		if ((mode != DiscoveryAgent.GIAC) && (mode != DiscoveryAgent.LIAC) && (mode != DiscoveryAgent.NOT_DISCOVERABLE)
				&& (mode < 0x9E8B00 || mode > 0x9E8B3F)) {
			throw new IllegalArgumentException("Invalid discoverable mode");
		}

		if (mode == DiscoveryAgent.NOT_DISCOVERABLE) {
			synchronized (monitor) {
				clearAcceptedDiscoverableMode();
			}
			return getDiscoverable() == DiscoveryAgent.NOT_DISCOVERABLE;
		}

		/*
		 * Android's public UI only supports general discoverability. It cannot
		 * represent JSR-82 LIAC or future custom inquiry access codes.
		 */
		if (mode != DiscoveryAgent.GIAC) {
			return false;
		}

		BluetoothPermissions.requireJsr82Permissions();
		if (getDiscoverable() == mode) {
			return true;
		}
		if (Looper.myLooper() == Looper.getMainLooper()) {
			throw new BluetoothStateException(
					"Discoverable confirmation cannot block the Android UI thread"
			);
		}

		synchronized (monitor) {
			waitForExistingDiscoverableRequest();
			if (getDiscoverable() == mode) {
				return true;
			}
			discoverableRequestPending = true;
			discoverableResultCode = Activity.RESULT_CANCELED;
		}
		try {
			BluetoothAccess.requestDiscoverable(
					ContextHolder.getActivity(),
					REQUEST_DISCOVERABLE,
					DISCOVERABLE_DURATION_SECONDS
			);
		} catch (BluetoothStateException e) {
			synchronized (monitor) {
				discoverableRequestPending = false;
				monitor.notifyAll();
			}
			throw e;
		}

		synchronized (monitor) {
			long deadline =
					SystemClock.uptimeMillis() + DISCOVERABLE_REQUEST_TIMEOUT_MS;
			while (discoverableRequestPending) {
				long remaining = deadline - SystemClock.uptimeMillis();
				if (remaining <= 0) {
					discoverableRequestPending = false;
					return false;
				}
				try {
					monitor.wait(remaining);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					discoverableRequestPending = false;
					throw new BluetoothStateException(
							"Interrupted while requesting discoverable mode"
					);
				}
			}
			return isDiscoverableResultAccepted(discoverableResultCode);
		}
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == REQUEST_DISCOVERABLE) {
			synchronized (monitor) {
				discoverableResultCode = resultCode;
				if (isDiscoverableResultAccepted(resultCode)) {
					acceptedDiscoverableMode = DiscoveryAgent.GIAC;
					acceptedDiscoverableUntil =
							SystemClock.elapsedRealtime() + resultCode * 1000L;
				} else {
					clearAcceptedDiscoverableMode();
				}
				discoverableRequestPending = false;
				monitor.notifyAll();
			}
		} else if (requestCode == REQUEST_ENABLE) {
			synchronized (monitor) {
				if (resultCode != Activity.RESULT_OK)
					cancelled = true;
				enableRequestPending = false;
				monitor.notifyAll();
			}
		}
	}

	public static boolean isPowerOn() {
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		if (adapter == null || !BluetoothPermissions.requestConnectPermission()) {
			return false;
		}
		return BluetoothAccess.isEnabledOrFalse(adapter);
	}

	public int getDiscoverable() {
		if (!BluetoothAccess.isEnabledOrFalse(DiscoveryAgent.adapter)) {
			synchronized (monitor) {
				clearAcceptedDiscoverableMode();
			}
			return DiscoveryAgent.NOT_DISCOVERABLE;
		}
		int scanMode = BluetoothAccess.getScanMode(DiscoveryAgent.adapter);
		int mappedMode = mapAndroidScanMode(scanMode);
		if (mappedMode == DiscoveryAgent.GIAC) {
			return mappedMode;
		}
		synchronized (monitor) {
			if (acceptedDiscoverableMode != DiscoveryAgent.NOT_DISCOVERABLE
					&& SystemClock.elapsedRealtime() < acceptedDiscoverableUntil) {
				return acceptedDiscoverableMode;
			}
			clearAcceptedDiscoverableMode();
		}
		return mappedMode;
	}

	static int mapAndroidScanMode(int scanMode) {
		if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
			return DiscoveryAgent.GIAC;
		}
		if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE) {
			/*
			 * Android has no public LIAC scan mode. Treat its powered-on,
			 * connectable state as LIAC so legacy MIDlets can distinguish it
			 * from a disabled adapter before they request GIAC discoverability.
			 */
			return DiscoveryAgent.LIAC;
		}
		return DiscoveryAgent.NOT_DISCOVERABLE;
	}

	static boolean isDiscoverableResultAccepted(int resultCode) {
		return resultCode > 0;
	}

	private void waitForExistingDiscoverableRequest() throws BluetoothStateException {
		while (discoverableRequestPending) {
			try {
				monitor.wait();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new BluetoothStateException(
						"Interrupted while waiting for discoverable mode"
				);
			}
		}
	}

	private void clearAcceptedDiscoverableMode() {
		acceptedDiscoverableMode = DiscoveryAgent.NOT_DISCOVERABLE;
		acceptedDiscoverableUntil = 0;
	}

	public static String getProperty(String property) {
		return properties.get(property);
	}

	public String getBluetoothAddress() {
		/*
		 * Normal Android apps cannot read the real local controller MAC address.
		 * Persisting a private, locally administered value preserves the JSR-82
		 * contract (12 uppercase hex characters) without exposing device identity.
		 */
		return LocalBluetoothAddress.get(ContextHolder.getAppContext());
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
			else
				return new J2MEServiceRecord(
						toRemoteDevice(conn.socket),
						conn.connUuid,
						false,
						false
				);
		} else {
			org.microemu.cldc.btl2cap.Connection conn = (org.microemu.cldc.btl2cap.Connection) notifier;
			if (conn.socket == null)
				// probably calling this for local device, so socket isn't opened
				return new J2MEServiceRecord(null, conn.connUuid, false, true);
			else
				return new J2MEServiceRecord(
						toRemoteDevice(conn.socket),
						conn.connUuid,
						false,
						true
				);
		}
	}

	private static RemoteDevice toRemoteDevice(android.bluetooth.BluetoothSocket socket) {
		android.bluetooth.BluetoothDevice remote = BluetoothAccess.getRemoteDeviceOrNull(socket);
		return remote == null ? null : new RemoteDevice(remote);
	}

	// Not supported on Android due to API limitations
	public void updateRecord(ServiceRecord srvRecord) throws ServiceRegistrationException {
		if (srvRecord == null) {
			throw new NullPointerException("Service Record is null");
		}
	}
}
