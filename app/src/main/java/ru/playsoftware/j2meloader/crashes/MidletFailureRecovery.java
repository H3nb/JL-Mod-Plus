/*
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

package ru.playsoftware.j2meloader.crashes;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Main-process recovery view over durable MIDlet session journals.
 *
 * Acknowledgment is deliberately stored separately from child-owned journals. Closing a notice only
 * suppresses repeated notification; it never deletes or mutates the diagnostic journal itself.
 */
public final class MidletFailureRecovery {
	private static final String TAG = MidletFailureRecovery.class.getSimpleName();
	private static final String ACK_DIR = "diagnostics/midlet-failure-acks";
	private static final String ACK_SUFFIX = ".ack";

	private MidletFailureRecovery() {}

	/** Returns the newest unacknowledged unexpected MIDlet session failure, if any. */
	public static PendingFailure findPendingFailure(Context context) {
		List<MidletSessionJournal.Snapshot> failures = readRetainedFailures(context);
		Set<String> acknowledged = readAcknowledgedEventIds(context);
		pruneOrphanAcknowledgments(context, failures);
		return selectNewestPending(failures, acknowledged);
	}

	/**
	 * Acknowledges all currently retained unexpected failures represented by the recovery notice.
	 * This prevents a backlog of old dialogs when several sessions failed before the user returned to
	 * the library. Diagnostics remain retained and can be surfaced by a later report inbox.
	 */
	public static void acknowledgePendingFailures(Context context) {
		List<MidletSessionJournal.Snapshot> failures = readRetainedFailures(context);
		if (failures.isEmpty()) {
			return;
		}
		File directory = acknowledgmentDirectory(context);
		if (!directory.isDirectory() && !directory.mkdirs()) {
			Log.w(TAG, "Unable to create MIDlet failure acknowledgment directory");
			return;
		}
		for (MidletSessionJournal.Snapshot failure : failures) {
			String eventId = failure.failureEventId;
			if (!isSafeEventId(eventId)) {
				continue;
			}
			File marker = acknowledgmentFile(directory, eventId);
			try {
				if (!marker.exists() && !marker.createNewFile()) {
					Log.w(TAG, "Unable to acknowledge MIDlet failure event: " + eventId);
				}
			} catch (IOException | SecurityException e) {
				Log.w(TAG, "Unable to acknowledge MIDlet failure event: " + eventId, e);
			}
		}
	}

	/** Removes only the main-process notice marker for a diagnostic event. */
	static void deleteAcknowledgment(Context context, String eventId) {
		if (!isSafeEventId(eventId)) {
			return;
		}
		File marker = acknowledgmentFile(acknowledgmentDirectory(context), eventId);
		if (marker.isFile() && !marker.delete()) {
			Log.w(TAG, "Unable to delete MIDlet failure acknowledgment: " + eventId);
		}
	}

	static PendingFailure selectNewestPending(List<MidletSessionJournal.Snapshot> failures,
			Set<String> acknowledgedEventIds) {
		MidletSessionJournal.Snapshot newest = null;
		for (MidletSessionJournal.Snapshot failure : failures) {
			if (!isUnexpectedFailure(failure)) {
				continue;
			}
			if (acknowledgedEventIds.contains(failure.failureEventId)) {
				continue;
			}
			if (newest == null || failure.updatedWallTimeMillis > newest.updatedWallTimeMillis
					|| (failure.updatedWallTimeMillis == newest.updatedWallTimeMillis
					&& failure.sessionId.compareTo(newest.sessionId) > 0)) {
				newest = failure;
			}
		}
		return newest == null ? null : new PendingFailure(newest);
	}

	static Set<String> collectFailureEventIds(List<MidletSessionJournal.Snapshot> failures) {
		HashSet<String> eventIds = new HashSet<>();
		for (MidletSessionJournal.Snapshot failure : failures) {
			if (isUnexpectedFailure(failure)) {
				eventIds.add(failure.failureEventId);
			}
		}
		return eventIds;
	}

	private static List<MidletSessionJournal.Snapshot> readRetainedFailures(Context context) {
		File directory = MidletSessionJournal.journalDirectory(context);
		File[] files = directory.listFiles();
		if (files == null || files.length == 0) {
			return Collections.emptyList();
		}
		ArrayList<MidletSessionJournal.Snapshot> failures = new ArrayList<>(files.length);
		for (File file : files) {
			if (file == null || !file.isFile()) {
				continue;
			}
			try {
				MidletSessionJournal.Snapshot snapshot = MidletSessionJournal.read(file);
				if (isUnexpectedFailure(snapshot)) {
					failures.add(snapshot);
				}
			} catch (IOException | RuntimeException e) {
				// Corrupt/future diagnostics must never make the main library unusable.
				Log.w(TAG, "Ignoring unreadable MIDlet session journal: " + file.getName(), e);
			}
		}
		return failures;
	}

	private static Set<String> readAcknowledgedEventIds(Context context) {
		File directory = acknowledgmentDirectory(context);
		File[] files = directory.listFiles();
		if (files == null || files.length == 0) {
			return Collections.emptySet();
		}
		HashSet<String> acknowledged = new HashSet<>(files.length);
		for (File file : files) {
			if (file == null || !file.isFile()) {
				continue;
			}
			String name = file.getName();
			if (name.endsWith(ACK_SUFFIX) && name.length() > ACK_SUFFIX.length()) {
				acknowledged.add(name.substring(0, name.length() - ACK_SUFFIX.length()));
			}
		}
		return acknowledged;
	}

	private static void pruneOrphanAcknowledgments(Context context,
			List<MidletSessionJournal.Snapshot> failures) {
		File directory = acknowledgmentDirectory(context);
		File[] files = directory.listFiles();
		if (files == null || files.length == 0) {
			return;
		}
		Set<String> retainedEventIds = collectFailureEventIds(failures);
		for (File file : files) {
			if (file == null || !file.isFile()) {
				continue;
			}
			String name = file.getName();
			if (!name.endsWith(ACK_SUFFIX)) {
				continue;
			}
			String eventId = name.substring(0, name.length() - ACK_SUFFIX.length());
			if (!retainedEventIds.contains(eventId) && !file.delete()) {
				Log.w(TAG, "Unable to remove orphan MIDlet failure acknowledgment: " + name);
			}
		}
	}

	private static boolean isUnexpectedFailure(MidletSessionJournal.Snapshot snapshot) {
		return snapshot != null
				&& snapshot.outcome == MidletSessionJournal.Outcome.UNEXPECTED_FAILURE
				&& isSafeEventId(snapshot.failureEventId);
	}

	static boolean isSafeEventId(String eventId) {
		if (eventId == null || eventId.length() < 1 || eventId.length() > 128) {
			return false;
		}
		for (int i = 0; i < eventId.length(); i++) {
			char c = eventId.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
				continue;
			}
			return false;
		}
		return true;
	}

	private static File acknowledgmentDirectory(Context context) {
		return new File(context.getFilesDir(), ACK_DIR);
	}

	private static File acknowledgmentFile(File directory, String eventId) {
		return new File(directory, eventId + ACK_SUFFIX);
	}

	/** Immutable UI-facing projection of one durable failure journal. */
	public static final class PendingFailure {
		private final String eventId;
		private final String sessionId;
		private final String midletName;
		private final MidletSessionJournal.Stage stage;
		private final MidletSessionJournal.FailureBoundary boundary;
		private final long updatedWallTimeMillis;

		private PendingFailure(MidletSessionJournal.Snapshot snapshot) {
			this.eventId = snapshot.failureEventId;
			this.sessionId = snapshot.sessionId;
			this.midletName = snapshot.midletName;
			this.stage = snapshot.stage;
			this.boundary = snapshot.failureBoundary;
			this.updatedWallTimeMillis = snapshot.updatedWallTimeMillis;
		}

		public String getEventId() {
			return eventId;
		}

		public String getSessionId() {
			return sessionId;
		}

		public String getMidletName() {
			return midletName;
		}

		public MidletSessionJournal.Stage getStage() {
			return stage;
		}

		public MidletSessionJournal.FailureBoundary getBoundary() {
			return boundary;
		}

		public long getUpdatedWallTimeMillis() {
			return updatedWallTimeMillis;
		}
	}
}
