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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.R;

/**
 * Persists audio backend failures and surfaces a short in-app warning.
 *
 * <p>Audio failures intentionally do not create Android system notifications.
 * Details remain available from global Settings > Audio diagnostics.</p>
 */
public final class AudioFailureReporter {
	private static final String TAG = AudioFailureReporter.class.getSimpleName();
	private static final long DEDUPLICATION_WINDOW_MS = 30_000L;
	private static final Map<String, Long> recentFailures = new ConcurrentHashMap<>();
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
		if (!claimFailure(failure)) {
			return;
		}
		REPORT_EXECUTOR.execute(() -> persistAndNotifyInApp(failure));
	}

	private static boolean claimFailure(AudioFailure failure) {
		long now = System.currentTimeMillis();
		String key = failure.getNotificationKey();
		Long previous = recentFailures.put(key, now);
		return previous == null || now - previous >= DEDUPLICATION_WINDOW_MS;
	}

	private static void persistAndNotifyInApp(AudioFailure failure) {
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

		try {
			AudioFailureReportStore.save(context.getFilesDir(), failure);
		} catch (IOException | RuntimeException e) {
			Log.e(TAG, "Unable to persist audio failure report", e);
		}
		showToast(context);
	}

	private static void showToast(Context context) {
		MAIN_HANDLER.post(() -> {
			MicroActivity activity = ContextHolder.getActivity();
			if (activity != null) {
				activity.toast(R.string.audio_failure_toast_settings);
			} else {
				android.widget.Toast.makeText(context, R.string.audio_failure_toast_settings,
						android.widget.Toast.LENGTH_SHORT).show();
			}
		});
	}
}
