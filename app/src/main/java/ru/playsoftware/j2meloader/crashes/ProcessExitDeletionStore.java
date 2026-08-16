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
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable user-deletion tombstones for process-exit evidence.
 *
 * Android 11+ may keep ApplicationExitInfo after JL-Mod Plus removes its local projection. A small
 * app-private marker therefore survives only while the original system-history key is still visible,
 * preventing a deliberately deleted report from being imported and notified again. Android 6-10
 * uses the same marker while the authoritative MIDlet journal that could recreate the legacy UNKNOWN
 * projection still exists. Markers contain only the immutable process-exit key, never trace/report
 * payloads.
 */
final class ProcessExitDeletionStore {
	private static final String TAG = ProcessExitDeletionStore.class.getSimpleName();
	private static final String DELETION_DIR = "diagnostics/process-exit-deletions";
	private static final String DELETION_SUFFIX = ".deleted";
	private static final String BACKUP_SUFFIX = ".bak";
	private static final String NEW_SUFFIX = ".new";

	private ProcessExitDeletionStore() {}

	/** Persists suppression before any dependent evidence is removed. */
	static boolean markDeleted(Context context, String key) {
		if (!isSafeKey(key)) {
			return false;
		}
		File directory = deletionDirectory(context);
		if (!directory.isDirectory() && !directory.mkdirs()) {
			Log.w(TAG, "Unable to create process-exit deletion directory");
			return false;
		}
		File marker = deletionFile(directory, key);
		if (atomicExists(marker)) {
			return true;
		}
		AtomicFile atomic = new AtomicFile(marker);
		FileOutputStream output = null;
		try {
			output = atomic.startWrite();
			output.write((key + "\n").getBytes(StandardCharsets.US_ASCII));
			atomic.finishWrite(output);
			return true;
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			if (output != null) {
				try {
					atomic.failWrite(output);
				} catch (Throwable ignored) {}
			}
			Log.w(TAG, "Unable to persist process-exit deletion marker: " + key, e);
			return false;
		}
	}

	static boolean isDeleted(Context context, String key) {
		return isSafeKey(key) && atomicExists(deletionFile(deletionDirectory(context), key));
	}

	/** API30+: marker is needed only while the exact key remains in the bounded framework history. */
	static void pruneAgainstHistoricalKeys(Context context, Set<String> historicalKeys) {
		pruneAgainstRetainedKeys(context, historicalKeys);
	}

	/** API23-29: marker is needed only while a journal remains capable of recreating the key. */
	static void pruneAgainstLegacyKeys(Context context, Set<String> retainedLegacyKeys) {
		pruneAgainstRetainedKeys(context, retainedLegacyKeys);
	}

	private static void pruneAgainstRetainedKeys(Context context, Set<String> retainedKeys) {
		Set<String> safeRetained = retainedKeys == null ? Collections.emptySet() : retainedKeys;
		for (Marker marker : markerFiles(context)) {
			if (!safeRetained.contains(marker.key) && !deleteAtomic(marker.file)) {
				Log.w(TAG, "Unable to prune process-exit deletion marker: " + marker.key);
			}
		}
	}

	private static List<Marker> markerFiles(Context context) {
		File[] files = deletionDirectory(context).listFiles();
		if (files == null || files.length == 0) {
			return Collections.emptyList();
		}
		ArrayList<Marker> result = new ArrayList<>();
		HashSet<String> seen = new HashSet<>();
		for (File file : files) {
			if (file == null || !file.isFile()) {
				continue;
			}
			String name = file.getName();
			String canonical = null;
			if (name.endsWith(DELETION_SUFFIX)) {
				canonical = name;
			} else if (name.endsWith(DELETION_SUFFIX + BACKUP_SUFFIX)) {
				canonical = name.substring(0, name.length() - BACKUP_SUFFIX.length());
			}
			if (canonical == null || canonical.length() <= DELETION_SUFFIX.length()) {
				continue;
			}
			String key = canonical.substring(0, canonical.length() - DELETION_SUFFIX.length());
			if (!isSafeKey(key)) {
				continue;
			}
			File base = new File(file.getParentFile(), canonical);
			if (seen.add(base.getAbsolutePath())) {
				result.add(new Marker(key, base));
			}
		}
		return result;
	}

	private static File deletionDirectory(Context context) {
		return new File(context.getFilesDir(), DELETION_DIR);
	}

	private static File deletionFile(File directory, String key) {
		return new File(directory, key + DELETION_SUFFIX);
	}

	private static boolean atomicExists(File file) {
		return file.isFile() || new File(file.getPath() + BACKUP_SUFFIX).isFile();
	}

	private static boolean deleteAtomic(File file) {
		return deleteIfExists(file)
				&& deleteIfExists(new File(file.getPath() + BACKUP_SUFFIX))
				&& deleteIfExists(new File(file.getPath() + NEW_SUFFIX));
	}

	private static boolean deleteIfExists(File file) {
		return !file.exists() || (file.isFile() && file.delete());
	}

	private static boolean isSafeKey(String key) {
		if (key == null || key.isEmpty() || key.length() > 96) {
			return false;
		}
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if ((c < '0' || c > '9') && c != '-') {
				return false;
			}
		}
		return true;
	}

	private static final class Marker {
		final String key;
		final File file;

		Marker(String key, File file) {
			this.key = key;
			this.file = file;
		}
	}
}
