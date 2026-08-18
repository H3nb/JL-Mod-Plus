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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Narrow main-process handoff from durable diagnostics journals to Library play-stat reconciliation. */
public final class MidletSessionStatsHandoff {
	private static final String TAG = MidletSessionStatsHandoff.class.getSimpleName();

	private MidletSessionStatsHandoff() {}

	/**
	 * Returns only terminal schema-v2 sessions that carry enough identity/stat data to reconcile.
	 * Live or ambiguous sessions remain absent and therefore retryable on a later pass.
	 */
	public static List<Record> loadTerminalRecords(Context context) {
		if (context == null) {
			return Collections.emptyList();
		}
		List<File> files = MidletSessionJournal.journalFiles(context);
		if (files.isEmpty()) {
			return Collections.emptyList();
		}
		ArrayList<Record> records = new ArrayList<>(files.size());
		for (File file : files) {
			try {
				MidletSessionJournal.Snapshot snapshot = MidletSessionJournal.read(file);
				if (snapshot.schemaVersion < 2
						|| MidletSessionStatsAckStore.isAcknowledged(context, snapshot.sessionId)
						|| !MidletSessionTerminalClassifier.isTerminal(context, snapshot)) {
					continue;
				}
				Record record = toRecord(snapshot);
				if (record != null) {
					records.add(record);
				}
			} catch (IOException | RuntimeException error) {
				Log.w(TAG, "Ignoring unreadable MIDlet stats journal", error);
			}
		}
		return records;
	}

	/** Call only after the target Room receipt transaction has committed or was already present. */
	public static boolean markReconciled(Context context, String sessionId) {
		return MidletSessionStatsAckStore.acknowledge(context, sessionId);
	}

	private static Record toRecord(MidletSessionJournal.Snapshot snapshot) {
		String workdir = normalizeWorkdir(snapshot.workdirLocator);
		String storageKey = safeStorageKey(snapshot.storageKey);
		Boolean reachedRunning = snapshot.reachedRunning;
		if (workdir == null || storageKey == null || reachedRunning == null) {
			return null;
		}
		if (reachedRunning) {
			if (snapshot.firstRunningWallTimeMillis == null
					|| snapshot.accumulatedActiveMillis == null
					|| snapshot.accumulatedActiveMillis < 0L) {
				return null;
			}
			return new Record(
					snapshot.sessionId,
					workdir,
					storageKey,
					true,
					snapshot.firstRunningWallTimeMillis,
					snapshot.accumulatedActiveMillis
			);
		}
		long activeMillis = snapshot.accumulatedActiveMillis == null
				? 0L : Math.max(0L, snapshot.accumulatedActiveMillis);
		return new Record(snapshot.sessionId, workdir, storageKey, false, null, activeMillis);
	}

	private static String normalizeWorkdir(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		File file = new File(value.trim());
		try {
			return file.getCanonicalPath();
		} catch (IOException | SecurityException ignored) {
			return file.getAbsolutePath();
		}
	}

	private static String safeStorageKey(String value) {
		if (value == null) {
			return null;
		}
		String key = value.trim();
		if (key.isEmpty() || ".".equals(key) || "..".equals(key)
				|| key.indexOf('/') >= 0 || key.indexOf('\\') >= 0) {
			return null;
		}
		return key;
	}

	public static final class Record {
		private final String sessionId;
		private final String workdirLocator;
		private final String storageKey;
		private final boolean reachedRunning;
		private final Long firstRunningWallTimeMillis;
		private final long accumulatedActiveMillis;

		Record(String sessionId, String workdirLocator, String storageKey, boolean reachedRunning,
				Long firstRunningWallTimeMillis, long accumulatedActiveMillis) {
			this.sessionId = sessionId;
			this.workdirLocator = workdirLocator;
			this.storageKey = storageKey;
			this.reachedRunning = reachedRunning;
			this.firstRunningWallTimeMillis = firstRunningWallTimeMillis;
			this.accumulatedActiveMillis = accumulatedActiveMillis;
		}

		public String getSessionId() { return sessionId; }
		public String getWorkdirLocator() { return workdirLocator; }
		public String getStorageKey() { return storageKey; }
		public boolean getReachedRunning() { return reachedRunning; }
		public Long getFirstRunningWallTimeMillis() { return firstRunningWallTimeMillis; }
		public long getAccumulatedActiveMillis() { return accumulatedActiveMillis; }
	}
}
