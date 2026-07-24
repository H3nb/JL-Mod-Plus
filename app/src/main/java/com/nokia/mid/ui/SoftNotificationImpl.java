/*
 * Copyright 2021 Arman Jussupgaliyev
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

package com.nokia.mid.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.Hashtable;
import java.util.Locale;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.util.PNGUtils;

public class SoftNotificationImpl extends SoftNotification {
	static final Hashtable<Integer, SoftNotificationImpl> instanceMap = new Hashtable<>();

	private SoftNotificationListener[] listeners;
	private String groupText;
	private String text;
	private boolean hasImage;
	private Notification notification;
	private String softAction1;
	private String softAction2;
	private int id;
	private static int ids = 1;
	private SoftNotificationImpl old;
	private Bitmap bitmap;

	public SoftNotificationImpl(int notificationId) {
		initialize(notificationId);
	}

	public SoftNotificationImpl() {
		initialize(-1);
	}

	protected void initialize(int notificationId) {
		id = notificationId;
		listeners = new SoftNotificationListener[1];
		if (id != -1) {
			old = instanceMap.get(id);
			if (old != null) {
				notification = old.notification;
			}
		}
	}

	void notificationCallback(int eventArg) {
		synchronized (this.listeners) {
			SoftNotificationListener listener = this.listeners[0];
			if (listener != null) {
				if (eventArg == 1) {
					listener.notificationSelected(this);
				} else if (eventArg == 2) {
					listener.notificationDismissed(this);
				}
			}
		}
	}

	public int getId() {
		if (notification == null) return -1;
		return id;
	}

	public void post() throws SoftNotificationException {
		Context context = ContextHolder.getAppContext();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				&& ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
					!= PackageManager.PERMISSION_GRANTED
				&& !requestNotificationPermission()) {
			throw new SoftNotificationException("Notification permission was not granted");
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				&& ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
					!= PackageManager.PERMISSION_GRANTED) {
			throw new SoftNotificationException("Notification permission is unavailable");
		}

		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
		if (!notificationManager.areNotificationsEnabled()) {
			throw new SoftNotificationException("Notifications are disabled");
		}

		try {
			if (id == -1) id = ids++;
			MicroActivity activity = ContextHolder.getActivity();
			String appName = activity != null
					? activity.getAppName()
					: context.getString(R.string.app_name);
			String channelId = appName.toLowerCase(Locale.ROOT);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				NotificationChannel channel = notificationManager.getNotificationChannel(channelId);
				if (channel == null) {
					int importance = NotificationManager.IMPORTANCE_DEFAULT;
					channel = new NotificationChannel(channelId, appName, importance);
					channel.setDescription("MIDlet");
					notificationManager.createNotificationChannel(channel);
				}
			}
			NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId);
			builder.setContentTitle(appName);
			if (text != null) builder.setContentText(text);
			if (groupText != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				builder.setGroup(groupText);
			}
			if (bitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				builder.setSmallIcon(IconCompat.createWithBitmap(bitmap));
			} else {
				builder.setSmallIcon(R.mipmap.ic_launcher);
			}
			builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
			builder.setAutoCancel(true);

			@SuppressLint("InlinedApi")
			int pendingIntentFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? PendingIntent.FLAG_IMMUTABLE : 0;
			if (softAction1 != null) {
				Intent selectIntent = new Intent(context, NotificationActivity.class);
				selectIntent.setAction("select");
				selectIntent.putExtra("id", id);
				selectIntent.putExtra("event", 1);

				PendingIntent selectPendingIntent = PendingIntent.getActivity(context,
						(int) System.currentTimeMillis(), selectIntent, pendingIntentFlags);
				builder.setContentIntent(selectPendingIntent);

				builder.addAction(new NotificationCompat.Action.Builder(null,
						softAction1, selectPendingIntent)
						.build());
			}

			Intent dismissIntent = new Intent(context, NotificationActivity.class);
			dismissIntent.setAction("dismiss");
			dismissIntent.putExtra("id", id);
			dismissIntent.putExtra("event", 2);

			PendingIntent dismissPendingIntent = PendingIntent.getActivity(context,
					(int) System.currentTimeMillis(), dismissIntent, pendingIntentFlags);

			NotificationCompat.Action dismissAction =
					new NotificationCompat.Action.Builder(null,
							softAction2 != null ? softAction2 : context.getString(R.string.dismiss),
							dismissPendingIntent)
							.build();
			builder.addAction(dismissAction);
			Notification builtNotification = builder.build();
			notificationManager.notify(id, builtNotification);
			notification = builtNotification;
			instanceMap.put(id, this);
		} catch (RuntimeException e) {
			throw new SoftNotificationException(e);
		}
	}

	private static boolean requestNotificationPermission() throws SoftNotificationException {
		try {
			return ContextHolder.requestPermission(Manifest.permission.POST_NOTIFICATIONS);
		} catch (RuntimeException e) {
			throw new SoftNotificationException(e);
		}
	}

	public void remove() throws SoftNotificationException {
		// Nokia UI API specifies that removing an unposted or already removed
		// notification is a no-op, so callers can safely perform cleanup.
		if (notification == null) return;
		NotificationManagerCompat.from(ContextHolder.getAppContext()).cancel(id);
	}

	public void setListener(SoftNotificationListener listener) {
		synchronized (listeners) {
			listeners[0] = listener;
		}
	}

	public void setText(String text, String groupText) throws SoftNotificationException {
		this.text = text;
		this.groupText = groupText;
	}

	public void setSoftkeyLabels(String softkey1Label, String softkey2Label) throws SoftNotificationException {
		softAction1 = softkey1Label;
		softAction2 = softkey2Label;
	}

	public void setImage(byte[] imageData) throws SoftNotificationException {
		Bitmap b = PNGUtils.getFixedBitmap(imageData, 0, imageData.length);
		if (b == null) {
			throw new SoftNotificationException("Can't decode image");
		}
		bitmap = b;
		hasImage = true;
	}
}
