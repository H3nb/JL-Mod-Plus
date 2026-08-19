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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Best-effort app-private marker proving a Room play-stat receipt has already committed. */
final class MidletSessionStatsAckStore {
	private static final String TAG = MidletSessionStatsAckStore.class.getSimpleName();
	private static final String ACK_DIR = "diagnostics/midlet-session-stats-acks";
	private static final String ACK_SUFFIX = ".ack";
	private static final String JOURNAL_SUFFIX = ".properties";

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

	/** Removes only ack markers whose corresponding AtomicFile journal has already disappeared. */
	static void pruneOrphans(Context context) {
		if (context == null) {
			return;
		}
		File directory = new File(context.getFilesDir(), ACK_DIR);
		pruneOrphanFiles(directory, MidletSessionJournal.journalFiles(context));
	}

	static void pruneOrphanFiles(File directory, List<File> journals) {
		File[] ackFiles = directory == null ? null : directory.listFiles();
		if (ackFiles == null || ackFiles.length == 0) {
			return;
		}
		Set<String> retained = new HashSet<>();
		if (journals != null) {
			for (File journal : journals) {
				if (journal == null) continue;
				String name = journal.getName();
				if (name.endsWith(JOURNAL_SUFFIX)) {
					retained.add(name.substring(0, name.length() - JOURNAL_SUFFIX.length()));
				}
			}
		}
		for (File ack : ackFiles) {
			if (ack == null || !ack.isFile() || !ack.getName().endsWith(ACK_SUFFIX)) {
				continue;
			}
			String name = ack.getName();
			String sessionId = name.substring(0, name.length() - ACK_SUFFIX.length());
			if (retained.contains(sessionId)) {
				continue;
			}
			if (!ack.delete()) {
				Log.w(TAG, "Unable to remove orphan MIDlet stats acknowledgement");
			}
		}
	}

	private static File file(Context context, String sessionId) {
		if (context == null || !MidletFailureRecovery.isSafeEventId(sessionId)) {
			return null;
		}
		return new File(new File(context.getFilesDir(), ACK_DIR), sessionId + ACK_SUFFIX);
	}
}
