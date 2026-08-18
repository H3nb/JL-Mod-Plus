/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.crashes;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Conservative terminal classifier used before a session may become a durable play-stat receipt. */
final class MidletSessionTerminalClassifier {
	private static final long PROCESS_GONE_GRACE_MILLIS = 1_500L;

	private MidletSessionTerminalClassifier() {}

	static boolean isTerminal(Context context, MidletSessionJournal.Snapshot snapshot) {
		if (snapshot == null) {
			return false;
		}
		if (snapshot.stage == MidletSessionJournal.Stage.COMPLETED
				|| snapshot.outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE) {
			return true;
		}
		return isTerminal(snapshot.stage, snapshot.outcome,
				isRecordedMidletProcessGone(context, snapshot));
	}

	/** Pure decision boundary kept host-testable independently from Android /proc probing. */
	static boolean isTerminal(MidletSessionJournal.Stage stage,
			MidletSessionJournal.Outcome outcome, boolean exactProcessGone) {
		return stage == MidletSessionJournal.Stage.COMPLETED
				|| outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE
				|| exactProcessGone;
	}

	private static boolean isRecordedMidletProcessGone(Context context,
			MidletSessionJournal.Snapshot snapshot) {
		if (context == null
				|| snapshot.processPid <= 0
				|| !MidletFailureRecovery.isSafeEventId(snapshot.sessionId)
				|| !pastGrace(snapshot)) {
			return false;
		}
		String expectedProcess = context.getPackageName() + ":midlet";
		if (!expectedProcess.equals(snapshot.processName)) {
			return false;
		}
		return exactProcessIsGone(snapshot.processPid, expectedProcess);
	}

	private static boolean pastGrace(MidletSessionJournal.Snapshot snapshot) {
		long nowElapsed = SystemClock.elapsedRealtime();
		if (snapshot.updatedElapsedRealtimeMillis >= 0
				&& nowElapsed >= snapshot.updatedElapsedRealtimeMillis) {
			return nowElapsed - snapshot.updatedElapsedRealtimeMillis >= PROCESS_GONE_GRACE_MILLIS;
		}
		long nowWall = System.currentTimeMillis();
		return snapshot.updatedWallTimeMillis > 0
				&& nowWall >= snapshot.updatedWallTimeMillis
				&& nowWall - snapshot.updatedWallTimeMillis >= PROCESS_GONE_GRACE_MILLIS;
	}

	private static boolean exactProcessIsGone(int pid, String expectedProcessName) {
		File cmdline = new File("/proc/" + pid + "/cmdline");
		if (!cmdline.exists()) {
			return true;
		}
		byte[] buffer = new byte[256];
		try (InputStream input = new FileInputStream(cmdline)) {
			int count = input.read(buffer);
			if (count <= 0) {
				return false;
			}
			int end = 0;
			while (end < count && buffer[end] != 0) {
				end++;
			}
			String actual = new String(buffer, 0, end, StandardCharsets.UTF_8).trim();
			// A different cmdline proves PID reuse, which also proves the recorded process is gone.
			return !expectedProcessName.equals(actual);
		} catch (IOException | RuntimeException error) {
			return false;
		}
	}
}
