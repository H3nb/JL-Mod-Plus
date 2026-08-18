/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.crashes;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** Best-effort app-private marker proving a Room play-stat receipt has already committed. */
final class MidletSessionStatsAckStore {
	private static final String TAG = MidletSessionStatsAckStore.class.getSimpleName();
	private static final String ACK_DIR = "diagnostics/midlet-session-stats-acks";
	private static final String ACK_SUFFIX = ".ack";

	private MidletSessionStatsAckStore() {}

	static boolean isAcknowledged(Context context, String sessionId) {
		File file = file(context, sessionId);
		return file != null && file.isFile();
	}

	static boolean acknowledge(Context context, String sessionId) {
		File file = file(context, sessionId);
		if (file == null) {
			return false;
		}
		File directory = file.getParentFile();
		if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
			return false;
		}
		if (file.isFile()) {
			return true;
		}
		try {
			return file.createNewFile() || file.isFile();
		} catch (IOException | SecurityException error) {
			Log.w(TAG, "Unable to acknowledge reconciled MIDlet stats", error);
			return false;
		}
	}

	static void delete(Context context, String sessionId) {
		File file = file(context, sessionId);
		if (file != null && file.exists() && !file.delete()) {
			Log.w(TAG, "Unable to remove stale MIDlet stats acknowledgement");
		}
	}

	private static File file(Context context, String sessionId) {
		if (context == null || !MidletFailureRecovery.isSafeEventId(sessionId)) {
			return null;
		}
		return new File(new File(context.getFilesDir(), ACK_DIR), sessionId + ACK_SUFFIX);
	}
}
