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
package org.microemu.cldc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.security.cert.CertificateException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;
import javax.net.ssl.SSLException;

import io.github.h3nb.jlmodplus.R;
import javax.microedition.shell.MicroDialogHandle;

public final class SecureConnectionFailureNotifier {
	private static final String TAG = SecureConnectionFailureNotifier.class.getName();
	private static final long TOAST_RESET_DELAY_MS = 5000;
	private static final long USER_DECISION_TIMEOUT_SECONDS = 120;
	private static final AtomicBoolean notificationActive = new AtomicBoolean();
	private static final AtomicBoolean insecureWarningShown = new AtomicBoolean();

	private SecureConnectionFailureNotifier() {
	}

	public static synchronized boolean handleTlsFailure(String host, Throwable error) {
		if (!isTlsFailure(error)) {
			return false;
		}

		Log.w(TAG, "Android blocked a secure connection to " + host, error);
		if (SecureConnectionPolicy.getMode() == SecureConnectionPolicy.MODE_ASK) {
			return askWhetherToContinue(host);
		}
		showBlockedNotification(host);
		return false;
	}

	public static void warnInsecureMode(String host) {
		if (!insecureWarningShown.compareAndSet(false, true)) {
			return;
		}
		Context appContext = ContextHolder.getAppContext();
		String displayHost = getDisplayHost(appContext, host);
		String message = appContext.getString(R.string.secure_connection_insecure_active, displayHost);
		new Handler(Looper.getMainLooper()).post(
				() -> Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
		);
	}

	static void resetForMidlet() {
		notificationActive.set(false);
		insecureWarningShown.set(false);
	}

	private static void showBlockedNotification(String host) {
		if (!notificationActive.compareAndSet(false, true)) {
			return;
		}
		Context appContext = ContextHolder.getAppContext();
		String displayHost = getDisplayHost(appContext, host);
		String message = appContext.getString(R.string.secure_connection_blocked_message, displayHost);
		Handler mainHandler = new Handler(Looper.getMainLooper());
		mainHandler.post(() -> showNotification(appContext, mainHandler, message));
	}

	private static boolean askWhetherToContinue(String host) {
		Context appContext = ContextHolder.getAppContext();
		MicroActivity activity = ContextHolder.getActivity();
		if (activity == null || activity.isFinishing() || activity.isDestroyed()
				|| Looper.myLooper() == Looper.getMainLooper()) {
			showBlockedNotification(host);
			return false;
		}

		String displayHost = getDisplayHost(appContext, host);
		String message = appContext.getString(R.string.secure_connection_ask_message, displayHost);
		AtomicBoolean continueInsecurely = new AtomicBoolean();
		AtomicBoolean decisionRecorded = new AtomicBoolean();
		CountDownLatch decisionLatch = new CountDownLatch(1);
		activity.runOnUiThread(() -> {
			try {
				MicroDialogHandle dialog = activity.showRuntimeMessage(
						activity.getString(R.string.secure_connection_ask_title),
						message,
						activity.getString(R.string.secure_connection_continue_once),
						activity.getString(android.R.string.cancel),
						null,
						true,
						() -> {
							continueInsecurely.set(true);
							decisionRecorded.set(true);
							decisionLatch.countDown();
						},
						() -> {
							decisionRecorded.set(true);
							decisionLatch.countDown();
						},
						null,
						() -> {
							decisionRecorded.set(true);
							decisionLatch.countDown();
						}
				);
			} catch (RuntimeException ex) {
				Log.w(TAG, "Unable to show secure connection decision dialog", ex);
				if (decisionRecorded.compareAndSet(false, true)) {
					decisionLatch.countDown();
				}
			}
		});
		try {
			if (!decisionLatch.await(USER_DECISION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				Log.w(TAG, "Timed out waiting for the secure connection decision");
				return false;
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
		return continueInsecurely.get();
	}

	static boolean isTlsFailure(Throwable error) {
		Set<Throwable> visited = new HashSet<>();
		Throwable current = error;
		while (current != null && visited.add(current)) {
			if (current instanceof SSLException || current instanceof CertificateException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static String getDisplayHost(Context context, String host) {
		return host == null || host.isBlank()
				? context.getString(R.string.secure_connection_unknown_host)
				: host;
	}

	private static void showNotification(Context appContext, Handler mainHandler, String message) {
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
			try {
				activity.showRuntimeMessage(
						activity.getString(R.string.secure_connection_blocked_title),
						message,
						activity.getString(android.R.string.ok),
						null,
						null,
						true,
						() -> notificationActive.set(false),
						null,
						null,
						() -> notificationActive.set(false)
				);
				return;
			} catch (RuntimeException ex) {
				Log.w(TAG, "Unable to show secure connection dialog", ex);
			}
		}

		Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
		mainHandler.postDelayed(() -> notificationActive.set(false), TOAST_RESET_DELAY_MS);
	}
}
