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
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * Bounded, app-private journal of the last high-level state for one Android process run.
 *
 * A run-scoped file is used instead of one global "last state" file so a freshly started process
 * cannot overwrite the context of a process that just died before ApplicationExitInfo is ingested.
 * Callers must use stable internal IDs, never translated UI labels or user-entered values.
 */
final class CrashContextStore {
	static final int SCHEMA_VERSION = 1;
	static final int MAX_BREADCRUMBS = 4;
	static final int MAX_LOCATION_LENGTH = 48;
	static final int MAX_ACTION_LENGTH = 24;
	static final int MAX_PHASE_LENGTH = 12;
	static final int MAX_RUN_ID_LENGTH = 20;

	private static final int MAX_RECORD_COUNT = 64;
	private static final long MAX_RECORD_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L;
	private static final long DELETE_GRACE_MILLIS = 5L * 60L * 1000L;
	private static final int MAX_BUILD_COMMIT_LENGTH = 40;
	private static final int MAX_BUILD_VARIANT_LENGTH = 48;

	private static final String TAG = CrashContextStore.class.getSimpleName();
	private static final String RECORD_DIR = "diagnostics/process-contexts";
	private static final String RECORD_SUFFIX = ".properties";
	private static final String BACKUP_SUFFIX = ".bak";
	private static final String NEW_SUFFIX = ".new";

	private static final String KEY_SCHEMA = "schemaVersion";
	private static final String KEY_RUN_ID = "runId";
	private static final String KEY_PROCESS_ROLE = "processRole";
	private static final String KEY_BUILD_COMMIT = "buildCommit";
	private static final String KEY_BUILD_VARIANT = "buildVariant";
	private static final String KEY_LOCATION = "location";
	private static final String KEY_PREVIOUS_LOCATION = "previousLocation";
	private static final String KEY_ACTION = "action";
	private static final String KEY_PHASE = "phase";
	private static final String KEY_UPDATED_WALL_TIME = "updatedWallTimeMillis";
	private static final String KEY_BREADCRUMB_COUNT = "breadcrumbCount";

	private static final Object LOCK = new Object();
	private static Snapshot current;

	private CrashContextStore() {}

	static Snapshot initialize(Context context, String processRole, String buildCommit,
			String buildVariant) {
		synchronized (LOCK) {
			if (current != null) {
				return current;
			}
			long now = System.currentTimeMillis();
			current = new Snapshot(
					newRunId(),
					normalizeToken(processRole, 16),
					boundText(buildCommit, MAX_BUILD_COMMIT_LENGTH),
					boundText(buildVariant, MAX_BUILD_VARIANT_LENGTH),
					null,
					null,
					null,
					null,
					now,
					Collections.emptyList()
			);
			persist(context, current);
			return current;
		}
	}

	static Snapshot update(Context context, String location, String action, String phase) {
		String nextLocation = normalizeToken(location, MAX_LOCATION_LENGTH);
		String nextAction = normalizeToken(action, MAX_ACTION_LENGTH);
		String nextPhase = normalizeToken(phase, MAX_PHASE_LENGTH);
		if (nextLocation == null || nextPhase == null) {
			return currentSnapshot();
		}
		synchronized (LOCK) {
			if (current == null) {
				return null;
			}
			if (equals(current.location, nextLocation)
					&& equals(current.action, nextAction)
					&& equals(current.phase, nextPhase)) {
				return current;
			}

			long now = System.currentTimeMillis();
			ArrayList<Breadcrumb> breadcrumbs = new ArrayList<>(current.breadcrumbs);
			if (current.location != null) {
				breadcrumbs.add(new Breadcrumb(
						current.updatedWallTimeMillis,
						current.location,
						current.action,
						current.phase
				));
				while (breadcrumbs.size() > MAX_BREADCRUMBS) {
					breadcrumbs.remove(0);
				}
			}
			String previous = current.previousLocation;
			if (current.location != null && !current.location.equals(nextLocation)) {
				previous = current.location;
			}
			current = new Snapshot(
					current.runId,
					current.processRole,
					current.buildCommit,
					current.buildVariant,
					nextLocation,
					previous,
					nextAction,
					nextPhase,
					now,
					breadcrumbs
			);
			persist(context, current);
			return current;
		}
	}

	static Snapshot currentSnapshot() {
		synchronized (LOCK) {
			return current;
		}
	}

	static String currentRunId() {
		Snapshot snapshot = currentSnapshot();
		return snapshot == null ? null : snapshot.runId;
	}

	static Snapshot readForRun(Context context, String runId) {
		if (!isSafeRunId(runId)) {
			return null;
		}
		File file = recordFile(context, runId);
		if (!atomicExists(file)) {
			return null;
		}
		try (InputStream input = new AtomicFile(file).openRead()) {
			Snapshot snapshot = read(input);
			return runId.equals(snapshot.runId) ? snapshot : null;
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "Ignoring unreadable crash context: " + runId);
			return null;
		}
	}

	static void prune(Context context) {
		File[] listed = directory(context).listFiles();
		if (listed == null || listed.length == 0) {
			return;
		}
		ArrayList<File> bases = new ArrayList<>();
		ArrayList<String> seen = new ArrayList<>();
		for (File file : listed) {
			File base = canonicalRecord(file);
			if (base != null && !seen.contains(base.getAbsolutePath())) {
				seen.add(base.getAbsolutePath());
				bases.add(base);
			}
		}
		bases.sort(Comparator.comparingLong(CrashContextStore::atomicLastModified).reversed());
		long now = System.currentTimeMillis();
		String currentRun = currentRunId();
		int kept = 0;
		for (File base : bases) {
			String runId = runIdFromFile(base);
			long modified = atomicLastModified(base);
			long age = modified > 0 && now >= modified ? now - modified : 0;
			boolean currentFile = currentRun != null && currentRun.equals(runId);
			boolean inGrace = modified > 0 && now >= modified && age < DELETE_GRACE_MILLIS;
			boolean expired = modified > 0 && now >= modified && age > MAX_RECORD_AGE_MILLIS;
			boolean overCount = kept >= MAX_RECORD_COUNT;
			if (!currentFile && !inGrace && (expired || overCount)) {
				deleteAtomic(base);
			} else {
				kept++;
			}
		}
	}

	static void write(Snapshot snapshot, OutputStream output) throws IOException {
		Properties properties = new Properties();
		properties.setProperty(KEY_SCHEMA, Integer.toString(SCHEMA_VERSION));
		properties.setProperty(KEY_RUN_ID, snapshot.runId);
		put(properties, KEY_PROCESS_ROLE, snapshot.processRole);
		put(properties, KEY_BUILD_COMMIT, snapshot.buildCommit);
		put(properties, KEY_BUILD_VARIANT, snapshot.buildVariant);
		put(properties, KEY_LOCATION, snapshot.location);
		put(properties, KEY_PREVIOUS_LOCATION, snapshot.previousLocation);
		put(properties, KEY_ACTION, snapshot.action);
		put(properties, KEY_PHASE, snapshot.phase);
		properties.setProperty(KEY_UPDATED_WALL_TIME, Long.toString(snapshot.updatedWallTimeMillis));
		properties.setProperty(KEY_BREADCRUMB_COUNT, Integer.toString(snapshot.breadcrumbs.size()));
		for (int i = 0; i < snapshot.breadcrumbs.size(); i++) {
			Breadcrumb breadcrumb = snapshot.breadcrumbs.get(i);
			String prefix = "breadcrumb." + i + ".";
			properties.setProperty(prefix + "time", Long.toString(breadcrumb.wallTimeMillis));
			put(properties, prefix + "location", breadcrumb.location);
			put(properties, prefix + "action", breadcrumb.action);
			put(properties, prefix + "phase", breadcrumb.phase);
		}
		properties.store(output, null);
	}

	static Snapshot read(InputStream input) throws IOException {
		Properties properties = new Properties();
		properties.load(input);
		int schema = parseInt(properties, KEY_SCHEMA);
		if (schema != SCHEMA_VERSION) {
			throw new IOException("Unsupported crash context schema: " + schema);
		}
		String runId = require(properties, KEY_RUN_ID);
		if (!isSafeRunId(runId)) {
			throw new IOException("Unsafe crash context run ID");
		}
		int count = Math.min(MAX_BREADCRUMBS,
				Math.max(0, parseIntDefault(properties, KEY_BREADCRUMB_COUNT, 0)));
		ArrayList<Breadcrumb> breadcrumbs = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String prefix = "breadcrumb." + i + ".";
			String location = normalizeToken(properties.getProperty(prefix + "location"),
					MAX_LOCATION_LENGTH);
			String phase = normalizeToken(properties.getProperty(prefix + "phase"), MAX_PHASE_LENGTH);
			if (location != null && phase != null) {
				breadcrumbs.add(new Breadcrumb(
						parseLongDefault(properties, prefix + "time", 0),
						location,
						normalizeToken(properties.getProperty(prefix + "action"), MAX_ACTION_LENGTH),
						phase
				));
			}
		}
		return new Snapshot(
				runId,
				normalizeToken(properties.getProperty(KEY_PROCESS_ROLE), 16),
				boundText(properties.getProperty(KEY_BUILD_COMMIT), MAX_BUILD_COMMIT_LENGTH),
				boundText(properties.getProperty(KEY_BUILD_VARIANT), MAX_BUILD_VARIANT_LENGTH),
				normalizeToken(properties.getProperty(KEY_LOCATION), MAX_LOCATION_LENGTH),
				normalizeToken(properties.getProperty(KEY_PREVIOUS_LOCATION), MAX_LOCATION_LENGTH),
				normalizeToken(properties.getProperty(KEY_ACTION), MAX_ACTION_LENGTH),
				normalizeToken(properties.getProperty(KEY_PHASE), MAX_PHASE_LENGTH),
				parseLongDefault(properties, KEY_UPDATED_WALL_TIME, 0),
				breadcrumbs
		);
	}

	private static void persist(Context context, Snapshot snapshot) {
		File directory = directory(context);
		if (!directory.isDirectory() && !directory.mkdirs()) {
			Log.w(TAG, "Unable to create crash context directory");
			return;
		}
		AtomicFile atomic = new AtomicFile(recordFile(context, snapshot.runId));
		FileOutputStream output = null;
		try {
			output = atomic.startWrite();
			write(snapshot, output);
			atomic.finishWrite(output);
		} catch (IOException | RuntimeException e) {
			rollback(atomic, output);
			Log.w(TAG, "Unable to persist crash context", e);
		} catch (OutOfMemoryError e) {
			rollback(atomic, output);
			try {
				Log.w(TAG, "Unable to persist crash context under low memory");
			} catch (Throwable ignored) {}
		}
	}

	private static String newRunId() {
		String random = Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits(), 36);
		String pid = Integer.toString(Math.max(0, Process.myPid()), 36);
		String candidate = "r" + random + "-" + pid;
		return candidate.length() <= MAX_RUN_ID_LENGTH
				? candidate : candidate.substring(0, MAX_RUN_ID_LENGTH);
	}

	static boolean isSafeRunId(String runId) {
		if (runId == null || runId.isEmpty() || runId.length() > MAX_RUN_ID_LENGTH) {
			return false;
		}
		for (int i = 0; i < runId.length(); i++) {
			char c = runId.charAt(i);
			if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
				return false;
			}
		}
		return true;
	}

	static String normalizeToken(String value, int maxLength) {
		if (value == null || maxLength <= 0) {
			return null;
		}
		String trimmed = value.trim().toLowerCase(Locale.ROOT);
		if (trimmed.isEmpty()) {
			return null;
		}
		StringBuilder normalized = new StringBuilder(Math.min(trimmed.length(), maxLength));
		for (int i = 0; i < trimmed.length() && normalized.length() < maxLength; i++) {
			char c = trimmed.charAt(i);
			boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
					|| c == '.' || c == '_' || c == '-' || c == ':';
			normalized.append(allowed ? c : '_');
		}
		return normalized.length() == 0 ? null : normalized.toString();
	}

	private static String boundText(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
		if (normalized.isEmpty()) {
			return null;
		}
		return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
	}

	private static void put(Properties properties, String key, String value) {
		if (value != null && !value.isEmpty()) {
			properties.setProperty(key, value);
		}
	}

	private static String require(Properties properties, String key) throws IOException {
		String value = properties.getProperty(key);
		if (value == null || value.trim().isEmpty()) {
			throw new IOException("Missing crash context field: " + key);
		}
		return value.trim();
	}

	private static int parseInt(Properties properties, String key) throws IOException {
		try {
			return Integer.parseInt(require(properties, key));
		} catch (NumberFormatException e) {
			throw new IOException("Invalid crash context integer: " + key, e);
		}
	}

	private static int parseIntDefault(Properties properties, String key, int fallback)
			throws IOException {
		String value = properties.getProperty(key);
		if (value == null || value.trim().isEmpty()) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			throw new IOException("Invalid crash context integer: " + key, e);
		}
	}

	private static long parseLongDefault(Properties properties, String key, long fallback)
			throws IOException {
		String value = properties.getProperty(key);
		if (value == null || value.trim().isEmpty()) {
			return fallback;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			throw new IOException("Invalid crash context long: " + key, e);
		}
	}

	private static File directory(Context context) {
		return new File(context.getFilesDir(), RECORD_DIR);
	}

	private static File recordFile(Context context, String runId) {
		return new File(directory(context), runId + RECORD_SUFFIX);
	}

	private static File canonicalRecord(File file) {
		if (file == null || !file.isFile()) {
			return null;
		}
		String name = file.getName();
		if (name.endsWith(RECORD_SUFFIX)) {
			return file;
		}
		if (name.endsWith(RECORD_SUFFIX + BACKUP_SUFFIX)) {
			return new File(file.getParentFile(),
					name.substring(0, name.length() - BACKUP_SUFFIX.length()));
		}
		if (name.endsWith(RECORD_SUFFIX + NEW_SUFFIX)) {
			return new File(file.getParentFile(),
					name.substring(0, name.length() - NEW_SUFFIX.length()));
		}
		return null;
	}

	private static String runIdFromFile(File base) {
		String name = base.getName();
		return name.endsWith(RECORD_SUFFIX)
				? name.substring(0, name.length() - RECORD_SUFFIX.length()) : null;
	}

	private static boolean atomicExists(File file) {
		return file.isFile() || new File(file.getPath() + BACKUP_SUFFIX).isFile();
	}

	private static long atomicLastModified(File file) {
		return Math.max(file.lastModified(), Math.max(
				new File(file.getPath() + BACKUP_SUFFIX).lastModified(),
				new File(file.getPath() + NEW_SUFFIX).lastModified()));
	}

	private static void deleteAtomic(File file) {
		deleteIfExists(file);
		deleteIfExists(new File(file.getPath() + BACKUP_SUFFIX));
		deleteIfExists(new File(file.getPath() + NEW_SUFFIX));
	}

	private static void deleteIfExists(File file) {
		if (file.exists() && file.isFile() && !file.delete()) {
			Log.w(TAG, "Unable to delete old crash context: " + file.getName());
		}
	}

	private static void rollback(AtomicFile atomic, FileOutputStream output) {
		if (output == null) {
			return;
		}
		try {
			atomic.failWrite(output);
		} catch (Throwable ignored) {}
	}

	private static boolean equals(String left, String right) {
		return left == null ? right == null : left.equals(right);
	}

	static final class Breadcrumb {
		final long wallTimeMillis;
		final String location;
		final String action;
		final String phase;

		Breadcrumb(long wallTimeMillis, String location, String action, String phase) {
			this.wallTimeMillis = wallTimeMillis;
			this.location = location;
			this.action = action;
			this.phase = phase;
		}
	}

	static final class Snapshot {
		final String runId;
		final String processRole;
		final String buildCommit;
		final String buildVariant;
		final String location;
		final String previousLocation;
		final String action;
		final String phase;
		final long updatedWallTimeMillis;
		final List<Breadcrumb> breadcrumbs;

		Snapshot(String runId, String processRole, String buildCommit, String buildVariant,
				 String location, String previousLocation, String action, String phase,
				 long updatedWallTimeMillis, List<Breadcrumb> breadcrumbs) {
			this.runId = runId;
			this.processRole = processRole;
			this.buildCommit = buildCommit;
			this.buildVariant = buildVariant;
			this.location = location;
			this.previousLocation = previousLocation;
			this.action = action;
			this.phase = phase;
			this.updatedWallTimeMillis = updatedWallTimeMillis;
			this.breadcrumbs = Collections.unmodifiableList(new ArrayList<>(breadcrumbs));
		}
	}
}
