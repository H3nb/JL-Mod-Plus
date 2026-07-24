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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.os.Parcelable;

import androidx.core.content.ContextCompat;

import org.microemu.android.bluetooth.BluetoothAccess;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Set;

import javax.microedition.util.ContextHolder;

public class DiscoveryAgent {
	public static final int NOT_DISCOVERABLE = 0;
	public static final int GIAC = 0x9E8B33;
	public static final int LIAC = 0x9E8B00;
	public static final int CACHED = 0x00;
	public static final int PREKNOWN = 0x01;

	private static int maxID = 1;

	static BluetoothAdapter adapter;

	private class Transaction extends BroadcastReceiver {
		public final int transID;
		public final int[] attrs;
		public final UUID[] uuids;
		public final RemoteDevice dev;
		public final DiscoveryListener listener;
		public volatile boolean stop = false;
		public volatile boolean discovering = false;
		public volatile boolean completed = false;
		public Context receiverContext;

		private String serviceName = null;
		private boolean btl2cap = false;
		private int id;

		public Transaction(int transID, int[] attrs, UUID[] uuids, RemoteDevice dev, DiscoveryListener listener) {
			this.transID = transID;
			this.attrs = attrs;
			this.uuids = uuids;
			this.dev = dev;
			this.listener = listener;
		}

		public boolean equals(Object obj) {
			if (obj == null || !(obj instanceof Transaction))
				return false;
			return (((Transaction) obj).transID == transID);
		}

		// Android 6.0.1 bug: UUID is reversed
		// see https://issuetracker.google.com/issues/37075233
		private java.util.UUID byteSwappedUuid(java.util.UUID toSwap) {
			ByteBuffer buffer = ByteBuffer.allocate(16);
			buffer.putLong(toSwap.getLeastSignificantBits()).putLong(toSwap.getMostSignificantBits());
			buffer.rewind();
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			return new java.util.UUID(buffer.getLong(), buffer.getLong());
		}

		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (BluetoothDevice.ACTION_UUID.equals(action)) {
				BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				if (d != null && d.equals(dev.dev)) {
					synchronized (transList) {
						if (completed) {
							return;
						}
						completed = true;
						transList.remove(this);
					}
					try {
					LinkedList<J2MEServiceRecord> records = new LinkedList<J2MEServiceRecord>();
					UUID[] uuidExtra = null;
					UUID SppUuid = new UUID(0x1101);
					UUID NameUuid = new UUID(0x1102);
					// SE phones publish a SPP service UUID instead of requested one
					boolean supportsSPP = false;
					{
						Parcelable[] uuidParcel = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
						if (uuidParcel != null) {
							uuidExtra = new UUID[uuidParcel.length];
							for (int i = 0; i < uuidExtra.length; i++)
								uuidExtra[i] = new UUID(((ParcelUuid) uuidParcel[i]).getUuid());
						}
					}

					for (int i = 0; !stop && (uuidExtra != null) && (i < uuidExtra.length); i++) {
						if (uuidExtra[i].equals(SppUuid))
							supportsSPP = true;
						if (uuidExtra[i].equals(NameUuid)) {
							// Workaround to get service name
							if (!btl2cap && serviceName == null) {
								try (BluetoothSocket bluetoothSocket =
											 BluetoothAccess.createRfcommSocket(dev.dev, NameUuid.uuid, false)) {
									if (!bluetoothSocket.isConnected()) {
										BluetoothAccess.connect(bluetoothSocket);
									}
									DataInputStream is =
											new DataInputStream(bluetoothSocket.getInputStream());
									byte[] resByte = new byte[256];
									btl2cap = is.read() == 1;
									is.readFully(resByte);
									if (attrs != null && attrs.length > 0) {
										serviceName =
												new String(resByte, StandardCharsets.UTF_8).trim();
									}
								} catch (IOException e) {
									e.printStackTrace();
								}
							}
						}
						for (int j = 0; !stop && j < uuids.length; j++) {
							if (uuidExtra[i].uuid.equals(uuids[j].uuid) || uuidExtra[i].uuid.equals(byteSwappedUuid(uuids[j].uuid))) {
								J2MEServiceRecord record = new J2MEServiceRecord(dev, uuids[j], false, btl2cap);
								records.add(record);
							}
						}
					}
					if (serviceName != null) {
						for (J2MEServiceRecord record : records) {
							record.setServiceName(serviceName);
						}
					}

					if (!stop && records.isEmpty()) {
						if (supportsSPP) {
							listener.servicesDiscovered(transID, new J2MEServiceRecord[]
									{new J2MEServiceRecord(dev, new UUID(0x1101), true, false)});
						}
					} else if (!stop) {
						J2MEServiceRecord[] casted = records.toArray(new J2MEServiceRecord[0]);
						listener.servicesDiscovered(transID, casted);
					}
					listener.serviceSearchCompleted(
							transID,
							stop
									? DiscoveryListener.SERVICE_SEARCH_TERMINATED
									: (records.isEmpty() && !supportsSPP)
											? DiscoveryListener.SERVICE_SEARCH_NO_RECORDS
											: DiscoveryListener.SERVICE_SEARCH_COMPLETED
					);
					} finally {
					unregister();
					}
				}
			}
		}

		private void unregister() {
			if (receiverContext == null) {
				return;
			}
			try {
				receiverContext.unregisterReceiver(this);
			} catch (IllegalArgumentException ignored) {
			}
		}

	}

	private LinkedList<Transaction> transList = new LinkedList<>();
	private HashSet<BluetoothDevice> discoveredList = new HashSet<>();
	private final Object inquiryLock = new Object();
	private BroadcastReceiver inquiryReceiver;
	private DiscoveryListener inquiryListener;
	private Context inquiryContext;

	DiscoveryAgent() throws BluetoothStateException {
		adapter = BluetoothAccess.requireAdapter();
	}

	public RemoteDevice[] retrieveDevices(int option) {
		Set<BluetoothDevice> set;
		if (option == CACHED) {
			synchronized (discoveredList) {
				set = new HashSet<>(discoveredList);
			}
		} else if (option == PREKNOWN) {
			set = BluetoothAccess.getBondedDevices(adapter);
		} else {
			throw new IllegalArgumentException();
		}
		if (set.isEmpty()) {
			return null;
		}
		RemoteDevice[] devices = new RemoteDevice[set.size()];
		int i = 0;
		for (BluetoothDevice device : set) devices[i++] = new RemoteDevice(device);
		return devices;
	}

	public boolean startInquiry(int accessCode, final DiscoveryListener listener) throws BluetoothStateException {
		if (listener == null) {
			throw new NullPointerException("DiscoveryListener is null");
		}
		if ((accessCode != LIAC) && (accessCode != GIAC) && ((accessCode < 0x9E8B00) || (accessCode > 0x9E8B3F))) {
			throw new IllegalArgumentException("Invalid accessCode " + accessCode);
		}

		if (BluetoothAccess.isDiscovering(adapter))
			return false;

		synchronized (transList) {
			if (!transList.isEmpty())
				return false;
		}

		IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
		filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

		Context activity = ContextHolder.getActivity();
		if (activity == null) {
			throw new BluetoothStateException("Bluetooth inquiry requires an active screen");
		}
		BroadcastReceiver receiver = new BroadcastReceiver() {
			public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();
				if (BluetoothDevice.ACTION_FOUND.equals(action)) {
					BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
					if (device == null) {
						return;
					}
					boolean added;
					synchronized (discoveredList) {
						added = discoveredList.add(device);
					}
					if (added) {
						RemoteDevice dev = new RemoteDevice(device);
						DeviceClass cod = new DeviceClass();
						listener.deviceDiscovered(dev, cod);
					}
				} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
					synchronized (transList) {
						if (!transList.isEmpty()) {
							for (Transaction t : transList) {
								if (!t.discovering) {
									try {
										t.discovering = BluetoothAccess.fetchUuidsWithSdp(t.dev.dev);
									} catch (BluetoothStateException e) {
										t.stop = true;
									}
								}
							}
						}
					}
					finishInquiry(DiscoveryListener.INQUIRY_COMPLETED);
				}
			}
		};
		synchronized (inquiryLock) {
			if (inquiryReceiver != null) {
				return false;
			}
			inquiryReceiver = receiver;
			inquiryListener = listener;
			inquiryContext = activity;
		}
		try {
			ContextCompat.registerReceiver(
					activity,
					receiver,
					filter,
					ContextCompat.RECEIVER_EXPORTED
			);
		} catch (RuntimeException e) {
			clearInquiry();
			throw new BluetoothStateException("Unable to listen for Bluetooth discovery results");
		}

		synchronized (discoveredList) {
			discoveredList.clear();
		}
		boolean started;
		try {
			started = BluetoothAccess.startDiscovery(adapter);
		} catch (BluetoothStateException e) {
			clearInquiry();
			throw e;
		}
		if (!started) {
			clearInquiry();
		} else {
			// Some MTK stacks do not send ACTION_DISCOVERY_FINISHED.
			new Thread(() -> {
				try {
					Thread.sleep(15000);
					synchronized (inquiryLock) {
						if (inquiryReceiver != receiver) {
							return;
						}
					}
					if (BluetoothAccess.isDiscovering(adapter)) {
						BluetoothAccess.cancelDiscovery(adapter);
					}
					finishInquiry(DiscoveryListener.INQUIRY_COMPLETED);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (BluetoothStateException e) {
					finishInquiry(DiscoveryListener.INQUIRY_ERROR);
				}
			}, "Jsr82InquiryTimeout").start();
		}
		return started;
	}

	public boolean cancelInquiry(DiscoveryListener listener) {
		if (listener == null) {
			throw new NullPointerException("DiscoveryListener is null");
		}
		synchronized (inquiryLock) {
			if (inquiryReceiver == null || inquiryListener != listener) {
				return false;
			}
		}
		BluetoothAccess.cancelDiscovery(adapter);
		finishInquiry(DiscoveryListener.INQUIRY_TERMINATED);
		return true;
	}

	public int searchServices(int[] attrSet, UUID[] uuidSet, RemoteDevice btDev, DiscoveryListener listener)
			throws BluetoothStateException {
		if (uuidSet == null) {
			throw new NullPointerException("uuidSet is null");
		}
		if (uuidSet.length == 0) {
			// The same as on Motorola, Nokia and SE Phones
			throw new IllegalArgumentException("uuidSet is empty");
		}
		for (int u1 = 0; u1 < uuidSet.length; u1++) {
			for (int u2 = u1 + 1; u2 < uuidSet.length; u2++) {
				if (uuidSet[u1].equals(uuidSet[u2])) {
					throw new IllegalArgumentException("uuidSet has duplicate values " + uuidSet[u1].toString());
				}
			}
		}
		if (btDev == null) {
			throw new NullPointerException("RemoteDevice is null");
		}
		if (listener == null) {
			throw new NullPointerException("DiscoveryListener is null");
		}
		for (int i = 0; attrSet != null && i < attrSet.length; i++) {
			if (attrSet[i] < 0x0000 || attrSet[i] > 0xffff) {
				throw new IllegalArgumentException("attrSet[" + i + "] not in range");
			}
		}

		final Transaction curTrans;
		synchronized (transList) {
			curTrans = new Transaction(maxID++, attrSet, uuidSet, btDev, listener);
			transList.add(curTrans);
		}
		Context activity = ContextHolder.getActivity();
		if (activity == null) {
			synchronized (transList) {
				transList.remove(curTrans);
			}
			throw new BluetoothStateException("Bluetooth service search requires an active screen");
		}
		curTrans.receiverContext = activity;
		try {
			ContextCompat.registerReceiver(
					activity,
					curTrans,
					new IntentFilter(BluetoothDevice.ACTION_UUID),
					ContextCompat.RECEIVER_EXPORTED
			);

			if (!BluetoothAccess.isDiscovering(adapter)) {
				synchronized (transList) {
					for (Transaction t : transList) {
						if (!t.discovering) {
							t.discovering = BluetoothAccess.fetchUuidsWithSdp(t.dev.dev);
						}
					}
				}
			}
		} catch (RuntimeException | BluetoothStateException e) {
			synchronized (transList) {
				transList.remove(curTrans);
			}
			try {
				activity.unregisterReceiver(curTrans);
			} catch (IllegalArgumentException ignored) {
			}
			if (e instanceof BluetoothStateException) {
				throw (BluetoothStateException) e;
			}
			BluetoothStateException failure =
					new BluetoothStateException("Unable to start Bluetooth service search");
			failure.initCause(e);
			throw failure;
		}
		new Thread(() -> {
			try {
				Thread.sleep(30000);
				finishServiceSearch(
						curTrans,
						DiscoveryListener.SERVICE_SEARCH_DEVICE_NOT_REACHABLE
				);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "Jsr82ServiceSearchTimeout").start();
		return curTrans.transID;
	}

	public boolean cancelServiceSearch(int transID) {
		Transaction transaction = null;
		synchronized (transList) {
			ListIterator<Transaction> iter = transList.listIterator();
			while (iter.hasNext()) {
				Transaction trans = iter.next();
				if (trans.transID == transID) {
					transaction = trans;
					break;
				}
			}
		}
		return transaction != null && finishServiceSearch(
				transaction,
				DiscoveryListener.SERVICE_SEARCH_TERMINATED
		);
	}

	private boolean finishServiceSearch(Transaction transaction, int completionCode) {
		synchronized (transList) {
			if (transaction.completed || !transList.remove(transaction)) {
				return false;
			}
			transaction.stop = true;
			transaction.completed = true;
		}
		transaction.unregister();
		transaction.listener.serviceSearchCompleted(transaction.transID, completionCode);
		return true;
	}

	private void finishInquiry(int completionCode) {
		DiscoveryListener listener;
		synchronized (inquiryLock) {
			if (inquiryReceiver == null) {
				return;
			}
			listener = inquiryListener;
		}
		clearInquiry();
		listener.inquiryCompleted(completionCode);
	}

	private void clearInquiry() {
		BroadcastReceiver receiver;
		Context context;
		synchronized (inquiryLock) {
			receiver = inquiryReceiver;
			context = inquiryContext;
			inquiryReceiver = null;
			inquiryListener = null;
			inquiryContext = null;
		}
		if (receiver != null && context != null) {
			try {
				context.unregisterReceiver(receiver);
			} catch (IllegalArgumentException ignored) {
				// The receiver may already be removed as the Activity is destroyed.
			}
		}
	}

	// TODO
	public String selectService(UUID uuid, int security, boolean master) throws BluetoothStateException {
		return null;
	}

}
