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

package ru.woesss.j2me.rms;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Bounded, recoverable RMS snapshots. Snapshots are separate from the live RMS
 * directory and contain hashes, so a partial archive is never presented as a
 * valid restore point.
 */
public final class RmsSnapshotManager {
	public static final int MAX_SNAPSHOTS = 2;
	public static final long MAX_SNAPSHOT_BYTES = 64L * 1024L * 1024L;
	private static final long MAX_EXPANDED_BYTES = 64L * 1024L * 1024L;
	private static final int MAX_FILES = 4096;
	private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
	private static final String MANIFEST = "manifest.properties";
	private static final ReentrantLock LOCK = new ReentrantLock();

	private RmsSnapshotManager() {
	}

	public static final class Snapshot {
		public final File file;
		public final String label;
		public final long createdAt;
		public final long bytes;

		private Snapshot(File file, String label, long createdAt, long bytes) {
			this.file = file;
			this.label = label;
			this.createdAt = createdAt;
			this.bytes = bytes;
		}
	}

	public static List<Snapshot> list(File root) throws IOException {
		LOCK.lock();
		try {
			List<Snapshot> snapshots = new ArrayList<>();
			if (root == null || !root.isDirectory()) return snapshots;
			File[] files = root.listFiles((dir, name) -> name.endsWith(".zip"));
			if (files == null) return snapshots;
			for (File file : files) {
				try {
					snapshots.add(readSnapshot(file));
				} catch (IOException ignored) {
					// A corrupt archive is not a restore point. The next create
					// operation removes it as part of bounded cleanup.
				}
			}
			Collections.sort(snapshots, new Comparator<Snapshot>() {
				@Override
				public int compare(Snapshot first, Snapshot second) {
					return Long.compare(second.createdAt, first.createdAt);
				}
			});
			return snapshots;
		} finally {
			LOCK.unlock();
		}
	}

	public static Snapshot create(File rmsDir, File root, String label) throws IOException {
		return create(rmsDir, root, label, null);
	}

	private static Snapshot create(File rmsDir, File root, String label,
			File additionallyProtected) throws IOException {
		LOCK.lock();
		try {
			if (rmsDir == null || !rmsDir.isDirectory()) {
				throw new IOException("RMS directory is missing");
			}
			if (root == null || (root.exists() && !root.isDirectory())) {
				throw new IOException("RMS snapshot directory is invalid");
			}
			if (!root.exists() && !root.mkdirs()) {
				throw new IOException("Unable to create RMS snapshot directory");
			}
			List<Entry> entries = collect(rmsDir);
			long totalBytes = 0;
			for (Entry entry : entries) {
				totalBytes += entry.size;
				if (totalBytes > MAX_EXPANDED_BYTES) {
					throw new IOException("RMS data is too large to snapshot safely");
				}
			}
			long createdAt = System.currentTimeMillis();
			String safeLabel = label == null ? "" : label.trim();
			File temporary = new File(root, ".snapshot-" + UUID.randomUUID() + ".tmp");
			File destination = new File(root, "snapshot-" + createdAt + "-"
					+ UUID.randomUUID() + ".zip");
			try {
				writeArchive(temporary, entries, safeLabel, createdAt, rmsDir);
				if (temporary.length() > MAX_SNAPSHOT_BYTES) {
					throw new IOException("RMS snapshot exceeds the storage limit");
				}
				move(temporary, destination);
				prune(root, destination, additionallyProtected);
				return readSnapshot(destination);
			} finally {
				if (temporary.exists() && !temporary.delete()) {
					// It is safe to leave only a uniquely named temporary file.
				}
			}
		} finally {
			LOCK.unlock();
		}
	}

	/**
	 * Creates a bounded safety snapshot of the current save before restoring
	 * the selected snapshot. Both snapshots are protected from pruning.
	 */
	public static Snapshot restoreWithBackup(Snapshot snapshot, File rmsDir,
			File root, String backupLabel) throws IOException {
		LOCK.lock();
		try {
			if (snapshot == null) {
				throw new IOException("Snapshot is required");
			}
			Snapshot safety = create(rmsDir, root, backupLabel, snapshot.file);
			restore(snapshot, rmsDir);
			return safety;
		} finally {
			LOCK.unlock();
		}
	}

	public static void restore(Snapshot snapshot, File rmsDir) throws IOException {
		if (snapshot == null || rmsDir == null) throw new IOException("Snapshot and RMS directory are required");
		if (rmsDir.getParentFile() == null) {
			throw new IOException("RMS directory has no recoverable parent");
		}
		LOCK.lock();
		File extracted = new File(rmsDir.getParentFile(), ".rms-restore-" + UUID.randomUUID());
		File previous = new File(rmsDir.getParentFile(), ".rms-before-restore-" + UUID.randomUUID());
		boolean activated = false;
		try {
			if (!extracted.mkdirs()) throw new IOException("Unable to create RMS restore staging directory");
			extractAndVerify(snapshot.file, extracted);
			if (rmsDir.exists() && !move(rmsDir, previous)) {
				throw new IOException("Unable to preserve current RMS data");
			}
			try {
				if (!move(extracted, rmsDir)) throw new IOException("Unable to activate restored RMS data");
				activated = true;
			} catch (IOException e) {
				try {
					if (rmsDir.exists()) deleteTree(rmsDir);
					if (previous.exists()) move(previous, rmsDir);
				} catch (IOException rollbackFailure) {
					e.addSuppressed(rollbackFailure);
				}
				throw e;
			}
			// Cleanup is intentionally best-effort after activation. A cleanup
			// failure must never destroy the restored RMS or its rollback copy.
			deleteTreeQuietly(previous);
		} finally {
			deleteTreeQuietly(extracted);
			if (activated) {
				deleteTreeQuietly(previous);
			}
			LOCK.unlock();
		}
	}

	public static void delete(Snapshot snapshot) throws IOException {
		LOCK.lock();
		try {
			if (snapshot != null && snapshot.file.exists() && !snapshot.file.delete()) {
				throw new IOException("Unable to delete RMS snapshot");
			}
		} finally {
			LOCK.unlock();
		}
	}

	private static List<Entry> collect(File rmsDir) throws IOException {
		List<Entry> entries = new ArrayList<>();
		collect(rmsDir, rmsDir, entries);
		return entries;
	}

	private static void collect(File root, File directory, List<Entry> entries) throws IOException {
		File[] files = directory.listFiles();
		if (files == null) throw new IOException("Unable to read RMS directory");
		java.util.Arrays.sort(files, new Comparator<File>() {
			@Override
			public int compare(File first, File second) {
				return first.getName().compareTo(second.getName());
			}
		});
		String rootPath = root.getAbsolutePath();
		for (File file : files) {
			if (file.isDirectory()) {
				collect(root, file, entries);
			} else if (file.isFile()) {
				if (entries.size() >= MAX_FILES) {
					throw new IOException("RMS contains too many files to snapshot safely");
				}
				String name = file.getAbsolutePath().substring(rootPath.length() + 1)
						.replace(File.separatorChar, '/');
				entries.add(new Entry(name, file.length(), digest(file)));
			}
		}
	}

	private static void writeArchive(File file, List<Entry> entries, String label, long createdAt,
			File rmsDir) throws IOException {
		Properties properties = new Properties();
		properties.setProperty("createdAt", Long.toString(createdAt));
		properties.setProperty("label", label);
		properties.setProperty("count", Integer.toString(entries.size()));
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			properties.setProperty("file." + i + ".path", entry.path);
			properties.setProperty("file." + i + ".size", Long.toString(entry.size));
			properties.setProperty("file." + i + ".sha256", entry.sha256);
		}
		try (FileOutputStream fos = new FileOutputStream(file);
				ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fos))) {
			zip.putNextEntry(new ZipEntry(MANIFEST));
			properties.store(zip, "JL-Mod Plus RMS snapshot");
			zip.closeEntry();
			byte[] buffer = new byte[8192];
			for (Entry entry : entries) {
				zip.putNextEntry(new ZipEntry(entry.path));
				try (InputStream input = new BufferedInputStream(new FileInputStream(new File(rmsDir, entry.path)))) {
					int read;
					while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
				}
				zip.closeEntry();
			}
			zip.finish();
			fos.getFD().sync();
		}
	}

	private static Properties extractAndVerify(File archive, File destination) throws IOException {
		if (!archive.isFile() || archive.length() > MAX_SNAPSHOT_BYTES) {
			throw new IOException("RMS snapshot is missing or exceeds the storage limit");
		}
		Properties manifest = new Properties();
		List<Entry> entries = new ArrayList<>();
		Set<String> entryNames = new HashSet<>();
		boolean manifestSeen = false;
		long expandedBytes = 0;
		try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
			ZipEntry entry;
			byte[] buffer = new byte[8192];
			while ((entry = zip.getNextEntry()) != null) {
				if (MANIFEST.equals(entry.getName())) {
					if (manifestSeen) throw new IOException("Duplicate RMS snapshot manifest");
					manifestSeen = true;
					manifest.load(new ByteArrayInputStream(readBounded(
							zip, MAX_MANIFEST_BYTES, "RMS snapshot manifest is too large")));
					continue;
				}
				if (entry.isDirectory()) {
					throw new IOException("Unsafe RMS snapshot path");
				}
				if (!entryNames.add(entry.getName()) || entries.size() >= MAX_FILES) {
					throw new IOException("Duplicate or excessive RMS snapshot entries");
				}
				File output = safeChild(destination, entry.getName());
				File parent = output.getParentFile();
				if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Unable to stage RMS file");
				try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
					int read;
					while ((read = zip.read(buffer)) != -1) {
						expandedBytes += read;
						if (expandedBytes > MAX_EXPANDED_BYTES) {
							throw new IOException("RMS snapshot expands beyond the safety limit");
						}
						out.write(buffer, 0, read);
					}
				}
				entries.add(new Entry(entry.getName(), output.length(), digest(output)));
			}
		}
		if (!manifestSeen || manifest.isEmpty()) {
			throw new IOException("Missing RMS snapshot manifest");
		}
		int count = parseManifestInt(manifest, "count");
		if (count != entries.size()) throw new IOException("RMS snapshot file count mismatch");
		Set<String> manifestPaths = new HashSet<>();
		for (int i = 0; i < count; i++) {
			String path = manifest.getProperty("file." + i + ".path");
			if (path == null || !manifestPaths.add(path)) {
				throw new IOException("Invalid RMS snapshot manifest path");
			}
			Entry actual = null;
			for (Entry candidate : entries) {
				if (candidate.path.equals(path)) {
					actual = candidate;
					break;
				}
			}
			if (actual == null || actual.size != parseManifestLong(
					manifest, "file." + i + ".size")
					|| !actual.sha256.equals(manifest.getProperty("file." + i + ".sha256"))) {
				throw new IOException("RMS snapshot hash verification failed");
			}
		}
		return manifest;
	}

	private static File safeChild(File destination, String entryName) throws IOException {
		if (entryName == null || entryName.isEmpty() || entryName.startsWith("/")
				|| entryName.indexOf(':') >= 0) {
			throw new IOException("Unsafe RMS snapshot path");
		}
		String[] parts = entryName.split("/");
		for (String part : parts) {
			if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
				throw new IOException("Unsafe RMS snapshot path");
			}
		}
		File output = new File(destination, entryName.replace('/', File.separatorChar));
		String root = destination.getCanonicalPath() + File.separator;
		if (!output.getCanonicalPath().startsWith(root)) throw new IOException("Unsafe RMS snapshot path");
		return output;
	}

	private static Snapshot readSnapshot(File file) throws IOException {
		if (!file.isFile() || file.length() > MAX_SNAPSHOT_BYTES) {
			throw new IOException("RMS snapshot exceeds the storage limit");
		}
		try (InputStream input = new FileInputStream(file);
				ZipInputStream zip = new ZipInputStream(input)) {
			ZipEntry entry = zip.getNextEntry();
			if (entry == null || !MANIFEST.equals(entry.getName())) throw new IOException("Missing RMS snapshot manifest");
			Properties properties = new Properties();
			properties.load(new ByteArrayInputStream(readBounded(
					zip, MAX_MANIFEST_BYTES, "RMS snapshot manifest is too large")));
			long createdAt = parseManifestLong(properties, "createdAt");
			return new Snapshot(file, properties.getProperty("label", ""), createdAt, file.length());
		}
	}

	private static byte[] readBounded(InputStream input, int limit, String error)
			throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int read;
		int total = 0;
		while ((read = input.read(buffer)) != -1) {
			total += read;
			if (total > limit) throw new IOException(error);
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private static int parseManifestInt(Properties properties, String key)
			throws IOException {
		long value = parseManifestLong(properties, key);
		if (value < 0 || value > Integer.MAX_VALUE) {
			throw new IOException("Invalid RMS snapshot manifest value: " + key);
		}
		return (int) value;
	}

	private static long parseManifestLong(Properties properties, String key)
			throws IOException {
		try {
			return Long.parseLong(properties.getProperty(key, ""));
		} catch (NumberFormatException e) {
			throw new IOException("Invalid RMS snapshot manifest value: " + key, e);
		}
	}

	private static void prune(File root, File protectedSnapshot,
			File additionallyProtected) throws IOException {
		File[] files = root.listFiles((dir, name) -> name.endsWith(".zip"));
		if (files == null) return;
		List<Snapshot> valid = new ArrayList<>();
		for (File file : files) {
			try {
				valid.add(readSnapshot(file));
			} catch (IOException ignored) {
				if (!file.delete()) {
					throw new IOException("Unable to remove corrupt RMS snapshot");
				}
			}
		}
		Collections.sort(valid, new Comparator<Snapshot>() {
			@Override
			public int compare(Snapshot first, Snapshot second) {
				if (first.file.equals(second.file)) return 0;
				int firstRank = snapshotRank(first.file, protectedSnapshot,
						additionallyProtected);
				int secondRank = snapshotRank(second.file, protectedSnapshot,
						additionallyProtected);
				if (firstRank != secondRank) {
					return Integer.compare(firstRank, secondRank);
				}
				int created = Long.compare(second.createdAt, first.createdAt);
				return created != 0 ? created
						: second.file.getName().compareTo(first.file.getName());
			}
		});
		for (int i = MAX_SNAPSHOTS; i < valid.size(); i++) {
			if (!valid.get(i).file.delete()) {
				throw new IOException("Unable to prune old RMS snapshot");
			}
		}
	}

	private static int snapshotRank(File file, File protectedSnapshot,
			File additionallyProtected) {
		if (file.equals(protectedSnapshot)) return 0;
		if (file.equals(additionallyProtected)) return 1;
		return 2;
	}

	private static String digest(File file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
			}
			StringBuilder result = new StringBuilder(64);
			for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
			return result.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	private static boolean move(File source, File destination) throws IOException {
		if (!source.exists()) return false;
		if (!source.renameTo(destination)) throw new IOException("Unable to move RMS data");
		return true;
	}

	private static void deleteTree(File directory) throws IOException {
		if (!directory.exists()) return;
		File[] files = directory.listFiles();
		if (files == null) throw new IOException("Unable to read RMS cleanup directory");
		for (File file : files) {
			if (file.isDirectory()) deleteTree(file);
			else if (!file.delete()) throw new IOException("Unable to delete RMS file");
		}
		if (!directory.delete()) throw new IOException("Unable to delete RMS directory");
	}

	private static void deleteTreeQuietly(File directory) {
		try {
			deleteTree(directory);
		} catch (IOException ignored) {
			// A recovery directory is safer left behind than deleted partially.
		}
	}

	private static final class Entry {
		private final String path;
		private final long size;
		private final String sha256;
		private Entry(String path, long size, String sha256) {
			this.path = path; this.size = size; this.sha256 = sha256;
		}
	}
}
