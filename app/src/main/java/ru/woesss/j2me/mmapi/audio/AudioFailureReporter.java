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

package ru.woesss.j2me.mmapi.audio;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.crashes.dialog.AudioFailureReportActivity;

/**
 * Converts an audio backend failure into a persistent report and a user-visible
 * notification without doing file I/O on an audio callback thread.
 */
public final class AudioFailureReporter {
	private static final String TAG = AudioFailureReporter.class.getSimpleName();
	private static final String CHANNEL_ID = "audio-failures";
	private static final long DEDUPLICATION_WINDOW_MS = 30_000L;
	private static final Map<String, Long> recentFailures = new ConcurrentHashMap<>();
	private static final AtomicInteger nextNotificationId = new AtomicInteger(0x4A000000);
	private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
	private static final ExecutorService REPORT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "AudioFailureReporter");
		thread.setDaemon(true);
		return thread;
	});

	private AudioFailureReporter() {
	}

	public static void report(String locator, String contentType, String backend,
			AudioFailure.Phase phase, String code, Throwable error) {
		report(AudioFailure.create(locator, contentType, backend, phase, code, error));
	}

	public static void report(AudioFailure failure) {
		if (failure == null) {
			return;
		}
		Log.e(TAG, failure.toReportText());
		if (!claimNotification(failure)) {
			return;
		}
		REPORT_EXECUTOR.execute(() -> persistAndNotify(failure));
	}

	private static boolean claimNotification(AudioFailure failure) {
		long now = System.currentTimeMillis();
		String key = failure.getNotificationKey();
		Long previous = recentFailures.get(key);
		if (previous != null && now - previous < DEDUPLICATION_WINDOW_MS) {
			return false;
		}
		recentFailures.put(key, now);
		return true;
	}

	private static void persistAndNotify(AudioFailure failure) {
		Context context;
		try {
			context = ContextHolder.getAppContext();
		} catch (RuntimeException e) {
			Log.e(TAG, "Application context unavailable for audio report", e);
			return;
		}
		if (context == null) {
			Log.e(TAG, "Application context unavailable for audio report");
			return;
		}

		String reportId;
		try {
			reportId = AudioFailureReportStore.save(context.getFilesDir(), failure);
		} catch (IOException | RuntimeException e) {
			Log.e(TAG, "Unable to persist audio failure report", e);
			showToast(context);
			return;
		}

		final String id = reportId;
		MAIN_HANDLER.post(() -> {
			if (!postNotification(context, id)) {
				showToast(context);
			}
		});
	}

	private static boolean postNotification(Context context, String reportId) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				&& ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
				!= PackageManager.PERMISSION_GRANTED) {
			return false;
		}
		NotificationManagerCompat manager = NotificationManagerCompat.from(context);
		if (!manager.areNotificationsEnabled()) {
			return false;
		}
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
						context.getString(R.string.audio_failure_notification_channel),
						NotificationManager.IMPORTANCE_DEFAULT);
				channel.setDescription(context.getString(R.string.audio_failure_notification_channel_description));
				NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
				if (notificationManager == null) {
					return false;
				}
				notificationManager.createNotificationChannel(channel);
			}

			Intent intent = new Intent(context, AudioFailureReportActivity.class)
				.putExtra(AudioFailureReportActivity.EXTRA_REPORT_ID, reportId)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
			int pendingIntentFlags = PendingIntentFlags.value();
			android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
				context, reportId.hashCode(), intent, pendingIntentFlags);
			NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
				.setSmallIcon(R.mipmap.ic_launcher)
				.setContentTitle(context.getString(R.string.audio_failure_notification_title))
				.setContentText(context.getString(R.string.audio_failure_notification_text))
				.setContentIntent(pendingIntent)
				.setAutoCancel(true)
				.setPriority(NotificationCompat.PRIORITY_DEFAULT);
			manager.notify(nextNotificationId.getAndIncrement(), builder.build());
			return true;
		} catch (RuntimeException e) {
			Log.e(TAG, "Unable to post audio failure notification", e);
			return false;
		}
	}

	private static void showToast(Context context) {
		MAIN_HANDLER.post(() -> {
			MicroActivity activity = ContextHolder.getActivity();
			if (activity != null) {
				activity.toast(R.string.audio_failure_toast);
			} else {
				android.widget.Toast.makeText(context, R.string.audio_failure_toast,
						android.widget.Toast.LENGTH_LONG).show();
			}
		});
	}

	private static final class PendingIntentFlags {
		private PendingIntentFlags() {
		}

		static int value() {
			return android.app.PendingIntent.FLAG_UPDATE_CURRENT
					| android.app.PendingIntent.FLAG_IMMUTABLE;
		}
	}
}
