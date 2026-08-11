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
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import ru.playsoftware.j2meloader.EmulatorApplication;

/**
 * Small, app-private durable record of one MIDlet session.
 *
 * The :midlet process is the only writer for a session file. Writes use AtomicFile so a reader in
 * the main process can observe either the previous complete snapshot or the next complete snapshot,
 * never a partially written properties file.
 */
public final class MidletSessionJournal {
	static final int SCHEMA_VERSION = 1;
	static final int MAX_JOURNAL_COUNT = 64;
	static final long MAX_JOURNAL_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L;
	static final long DELETE_GRACE_MILLIS = 5L * 60L * 1000L;

	private static final String TAG = MidletSessionJournal.class.getSimpleName();
	private static final String JOURNAL_DIR = "diagnostics/midlet-sessions";
	private static final int MAX_VALUE_LENGTH = 256;

	private static final String KEY_SCHEMA_VERSION = "schemaVersion";
	private static final String KEY_SESSION_ID = "sessionId";
	private static final String KEY_PROCESS_NAME = "processName";
	private static final String KEY_PROCESS_PID = "processPid";
	private static final String KEY_STARTED_WALL_TIME = "startedWallTimeMillis";
	private static final String KEY_STARTED_ELAPSED_TIME = "startedElapsedRealtimeMillis";
	private static final String KEY_UPDATED_WALL_TIME = "updatedWallTimeMillis";
	private static final String KEY_UPDATED_ELAPSED_TIME = "updatedElapsedRealtimeMillis";
	private static final String KEY_STAGE = "stage";
	private static final String KEY_OUTCOME = "outcome";
	private static final String KEY_MIDLET_NAME = "midletName";
	private static final String KEY_MIDLET_VENDOR = "midletVendor";
	private static final String KEY_MIDLET_VERSION = "midletVersion";
	private static final String KEY_MAIN_CLASS = "mainClass";
	private static final String KEY_JAR_SIZE = "jarSize";
	private static final String KEY_JAR_SHA256 = "jarSha256";

	public enum Stage {
		PREPARING,
		INITIALIZING,
		STARTING,
		RUNNING,
		PAUSING,
		PAUSED,
		STOPPING,
		COMPLETED
	}

	public enum Outcome {
		NONE,
		MIDLET_REQUEST,
		USER_STOP,
		LIFECYCLE_STOP,
		UNEXPECTED_FAILURE
	}

	private final AtomicFile atomicFile;
	private final String sessionId;
	private final String processName;
	private final int processPid;
	private final long startedWallTimeMillis;
	private final long startedElapsedRealtimeMillis;
	private final String midletName;
	private final String midletVendor;
	private final String midletVersion;
	private final String mainClass;
	private final String jarSize;
	private final String jarSha256;

	private Stage stage;
	private Outcome outcome;
	private long updatedWallTimeMillis;
	private long updatedElapsedRealtimeMillis;

	private MidletSessionJournal(File file, String sessionId, String processName, int processPid,
			long startedWallTimeMillis, long startedElapsedRealtimeMillis, String midletName,
			String midletVendor, String midletVersion, String mainClass, String jarSize,
			String jarSha256) {
		this.atomicFile = new AtomicFile(file);
		this.sessionId = sessionId;
		this.processName = processName;
		this.processPid = processPid;
		this.startedWallTimeMillis = startedWallTimeMillis;
		this.startedElapsedRealtimeMillis = startedElapsedRealtimeMillis;
		this.midletName = bound(midletName);
		this.midletVendor = bound(midletVendor);
		this.midletVersion = bound(midletVersion);
		this.mainClass = bound(mainClass);
		this.jarSize = bound(jarSize);
		this.jarSha256 = bound(jarSha256);
		this.stage = Stage.PREPARING;
		this.outcome = Outcome.NONE;
		this.updatedWallTimeMillis = startedWallTimeMillis;
		this.updatedElapsedRealtimeMillis = startedElapsedRealtimeMillis;
	}

	public static MidletSessionJournal create(Context context, String midletName, String midletVendor,
			String midletVersion, String mainClass, String jarSize, String jarSha256) {
		String sessionId = UUID.randomUUID().toString();
		File directory = journalDirectory(context);
		File file = new File(directory, sessionId + ".properties");
		MidletSessionJournal journal = new MidletSessionJournal(
				file,
				sessionId,
				EmulatorApplication.getProcessName(),
				Process.myPid(),
				System.currentTimeMillis(),
				SystemClock.elapsedRealtime(),
				midletName,
				midletVendor,
				midletVersion,
				mainClass,
				jarSize,
				jarSha256
		);
		if (!directory.isDirectory() && !directory.mkdirs()) {
			Log.w(TAG, "Unable to create MIDlet session journal directory");
		}
		journal.persist();
		try {
			// ACRA custom data is a process-global HashMap. Publish only the immutable session ID;
			// stage/outcome stay authoritative in the durable journal and are never mutated there.
			CrashReporter.setSessionContext(sessionId);
		} catch (RuntimeException e) {
			Log.w(TAG, "Unable to publish MIDlet session ID to crash context", e);
		}
		return journal;
	}

	static void prune(Context context) {
		File directory = journalDirectory(context);
		File[] files = directory.listFiles();
		if (files == null || files.length == 0) {
			return;
		}
		ArrayList<File> journals = new ArrayList<>(files.length);
		Collections.addAll(journals, files);
		pruneFiles(
				journals,
				System.currentTimeMillis(),
				MAX_JOURNAL_COUNT,
				MAX_JOURNAL_AGE_MILLIS,
				DELETE_GRACE_MILLIS
		);
	}

	static void pruneFiles(List<File> files, long now, int maxCount, long maxAgeMillis,
			long graceMillis) {
		ArrayList<File> candidates = new ArrayList<>(files.size());
		for (File file : files) {
			if (file != null && file.isFile()) {
				candidates.add(file);
			}
		}
		Collections.sort(candidates, (left, right) -> {
			long leftModified = left.lastModified();
			long rightModified = right.lastModified();
			if (leftModified == rightModified) {
				return 0;
			}
			return leftModified < rightModified ? 1 : -1;
		});

		int keptCount = 0;
		for (File journal : candidates) {
			long modified = journal.lastModified();
			long age = modified > 0 && now >= modified ? now - modified : 0;
			boolean inGracePeriod = modified > 0 && now >= modified && age < graceMillis;
			boolean expired = modified > 0 && now >= modified && age > maxAgeMillis;
			boolean overCount = keptCount >= maxCount;
			boolean shouldDelete = !inGracePeriod && (expired || overCount);

			if (shouldDelete && journal.delete()) {
				continue;
			}
			if (shouldDelete) {
				Log.w(TAG, "Unable to delete old MIDlet session journal: " + journal.getName());
			}
			keptCount++;
		}
	}

	static File journalDirectory(Context context) {
		return new File(context.getFilesDir(), JOURNAL_DIR);
	}

	public String getSessionId() {
		return sessionId;
	}

	public synchronized void transition(Stage nextStage) {
		if (nextStage == null || stage == Stage.COMPLETED) {
			return;
		}
		stage = nextStage;
		touch();
		persist();
	}

	public synchronized void markOutcome(Outcome nextOutcome) {
		if (nextOutcome == null || nextOutcome == Outcome.NONE || outcome != Outcome.NONE) {
			return;
		}
		outcome = nextOutcome;
		touch();
		persist();
	}

	public synchronized void complete(Outcome fallbackOutcome) {
		if (outcome == Outcome.NONE && fallbackOutcome != null && fallbackOutcome != Outcome.NONE) {
			outcome = fallbackOutcome;
		}
		// Preserve the causal lifecycle stage when an unexpected failure already won the session.
		if (outcome != Outcome.UNEXPECTED_FAILURE) {
			stage = Stage.COMPLETED;
		}
		touch();
		persist();
	}

	private void touch() {
		updatedWallTimeMillis = System.currentTimeMillis();
		updatedElapsedRealtimeMillis = SystemClock.elapsedRealtime();
	}

	private void persist() {
		Snapshot snapshot = snapshot();
		FileOutputStream output = null;
		try {
			output = atomicFile.startWrite();
			write(snapshot, output);
			atomicFile.finishWrite(output);
		} catch (IOException | RuntimeException e) {
			if (output != null) {
				atomicFile.failWrite(output);
			}
			Log.w(TAG, "Unable to persist MIDlet session journal", e);
		}
	}

	private synchronized Snapshot snapshot() {
		return new Snapshot(
				SCHEMA_VERSION,
				sessionId,
				processName,
				processPid,
				startedWallTimeMillis,
				startedElapsedRealtimeMillis,
				updatedWallTimeMillis,
				updatedElapsedRealtimeMillis,
				stage,
				outcome,
				midletName,
				midletVendor,
				midletVersion,
				mainClass,
				jarSize,
				jarSha256
		);
	}

	static void write(Snapshot snapshot, OutputStream output) throws IOException {
		Properties properties = new Properties();
		properties.setProperty(KEY_SCHEMA_VERSION, Integer.toString(snapshot.schemaVersion));
		put(properties, KEY_SESSION_ID, snapshot.sessionId);
		put(properties, KEY_PROCESS_NAME, snapshot.processName);
		properties.setProperty(KEY_PROCESS_PID, Integer.toString(snapshot.processPid));
		properties.setProperty(KEY_STARTED_WALL_TIME, Long.toString(snapshot.startedWallTimeMillis));
		properties.setProperty(KEY_STARTED_ELAPSED_TIME, Long.toString(snapshot.startedElapsedRealtimeMillis));
		properties.setProperty(KEY_UPDATED_WALL_TIME, Long.toString(snapshot.updatedWallTimeMillis));
		properties.setProperty(KEY_UPDATED_ELAPSED_TIME, Long.toString(snapshot.updatedElapsedRealtimeMillis));
		properties.setProperty(KEY_STAGE, snapshot.stage.name());
		properties.setProperty(KEY_OUTCOME, snapshot.outcome.name());
		put(properties, KEY_MIDLET_NAME, snapshot.midletName);
		put(properties, KEY_MIDLET_VENDOR, snapshot.midletVendor);
		put(properties, KEY_MIDLET_VERSION, snapshot.midletVersion);
		put(properties, KEY_MAIN_CLASS, snapshot.mainClass);
		put(properties, KEY_JAR_SIZE, snapshot.jarSize);
		put(properties, KEY_JAR_SHA256, snapshot.jarSha256);
		properties.store(output, null);
	}

	static Snapshot read(InputStream input) throws IOException {
		Properties properties = new Properties();
		properties.load(input);
		int schemaVersion = parseInt(properties, KEY_SCHEMA_VERSION);
		if (schemaVersion != SCHEMA_VERSION) {
			throw new IOException("Unsupported MIDlet session journal schema: " + schemaVersion);
		}
		String sessionId = require(properties, KEY_SESSION_ID);
		Stage stage = parseEnum(Stage.class, properties, KEY_STAGE);
		Outcome outcome = parseEnum(Outcome.class, properties, KEY_OUTCOME);
		return new Snapshot(
				schemaVersion,
				sessionId,
				properties.getProperty(KEY_PROCESS_NAME),
				parseInt(properties, KEY_PROCESS_PID),
				parseLong(properties, KEY_STARTED_WALL_TIME),
				parseLong(properties, KEY_STARTED_ELAPSED_TIME),
				parseLong(properties, KEY_UPDATED_WALL_TIME),
				parseLong(properties, KEY_UPDATED_ELAPSED_TIME),
				stage,
				outcome,
				properties.getProperty(KEY_MIDLET_NAME),
				properties.getProperty(KEY_MIDLET_VENDOR),
				properties.getProperty(KEY_MIDLET_VERSION),
				properties.getProperty(KEY_MAIN_CLASS),
				properties.getProperty(KEY_JAR_SIZE),
				properties.getProperty(KEY_JAR_SHA256)
		);
	}

	static Snapshot read(File file) throws IOException {
		try (InputStream input = new FileInputStream(file)) {
			return read(input);
		}
	}

	private static void put(Properties properties, String key, String value) {
		if (value != null) {
			properties.setProperty(key, value);
		}
	}

	private static String require(Properties properties, String key) throws IOException {
		String value = properties.getProperty(key);
		if (value == null || value.isEmpty()) {
			throw new IOException("Missing MIDlet session journal field: " + key);
		}
		return value;
	}

	private static int parseInt(Properties properties, String key) throws IOException {
		try {
			return Integer.parseInt(require(properties, key));
		} catch (NumberFormatException e) {
			throw new IOException("Invalid MIDlet session journal integer: " + key, e);
		}
	}

	private static long parseLong(Properties properties, String key) throws IOException {
		try {
			return Long.parseLong(require(properties, key));
		} catch (NumberFormatException e) {
			throw new IOException("Invalid MIDlet session journal long: " + key, e);
		}
	}

	private static <T extends Enum<T>> T parseEnum(Class<T> type, Properties properties, String key)
			throws IOException {
		try {
			return Enum.valueOf(type, require(properties, key));
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid MIDlet session journal enum: " + key, e);
		}
	}

	private static String bound(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
		if (normalized.isEmpty()) {
			return null;
		}
		return normalized.length() <= MAX_VALUE_LENGTH
				? normalized
				: normalized.substring(0, MAX_VALUE_LENGTH);
	}

	static final class Snapshot {
		final int schemaVersion;
		final String sessionId;
		final String processName;
		final int processPid;
		final long startedWallTimeMillis;
		final long startedElapsedRealtimeMillis;
		final long updatedWallTimeMillis;
		final long updatedElapsedRealtimeMillis;
		final Stage stage;
		final Outcome outcome;
		final String midletName;
		final String midletVendor;
		final String midletVersion;
		final String mainClass;
		final String jarSize;
		final String jarSha256;

		Snapshot(int schemaVersion, String sessionId, String processName, int processPid,
				 long startedWallTimeMillis, long startedElapsedRealtimeMillis,
				 long updatedWallTimeMillis, long updatedElapsedRealtimeMillis, Stage stage,
				 Outcome outcome, String midletName, String midletVendor, String midletVersion,
				 String mainClass, String jarSize, String jarSha256) {
			this.schemaVersion = schemaVersion;
			this.sessionId = sessionId;
			this.processName = processName;
			this.processPid = processPid;
			this.startedWallTimeMillis = startedWallTimeMillis;
			this.startedElapsedRealtimeMillis = startedElapsedRealtimeMillis;
			this.updatedWallTimeMillis = updatedWallTimeMillis;
			this.updatedElapsedRealtimeMillis = updatedElapsedRealtimeMillis;
			this.stage = stage;
			this.outcome = outcome;
			this.midletName = midletName;
			this.midletVendor = midletVendor;
			this.midletVersion = midletVersion;
			this.mainClass = mainClass;
			this.jarSize = jarSize;
			this.jarSha256 = jarSha256;
		}
	}
}
