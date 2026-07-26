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

package ru.woesss.j2me.installer;

import android.util.Log;

import com.android.dex.ClassDef;
import com.android.dex.Dex;
import com.android.dx.command.dexer.Main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.util.FileUtils;
import ru.woesss.j2me.jar.Descriptor;

/**
 * Rebuilds one installed MIDlet's transformed DEX without touching its source
 * JAR, configuration, RMS, or data directory.
 *
 * <p>The previous executable remains available until the rebuilt MIDlet class
 * has been loaded and instantiated. An interrupted or failed migration is
 * rolled back on the next launch.</p>
 */
public final class TimingDexMigrator {
	private static final String TAG = TimingDexMigrator.class.getSimpleName();
	private static final String TEMP_ARCHIVE = ".converted.timing.tmp.zip";
	private static final String TEMP_MANIFEST = ".converted.dex.tmp.conf";
	private static final String BACKUP_ARCHIVE = ".converted.timing.backup";
	private static final String BACKUP_MANIFEST = ".converted.dex.conf.backup";
	private static final String BACKUP_MANIFEST_ABSENT = ".converted.dex.conf.absent";
	private static final String LEGACY_BACKUP_VERSION = ".converted.timing.version.backup";
	private static final String LEGACY_BACKUP_VERSION_ABSENT = ".converted.timing.version.absent";
	private static final String PENDING_MIGRATION = ".converted.timing.pending";
	private static final String PENDING_MIGRATION_TEMP = ".converted.timing.pending.tmp";
	private static final Object MIGRATION_LOCK = new Object();

	private TimingDexMigrator() {
	}

	public static boolean needsMigration(File appDir) {
		File archive = findActiveArchive(appDir);
		File manifest = child(appDir, Config.MIDLET_MANIFEST_FILE);
		if (!archive.isFile() || !manifest.isFile()) {
			return true;
		}
		try {
			Descriptor descriptor = new Descriptor(manifest, false);
			String value = descriptor.getAttribute(Config.MIDLET_DEX_VERSION_ATTRIBUTE);
			return value == null || Integer.parseInt(value) < Config.MIDLET_DEX_VERSION;
		} catch (IOException | NumberFormatException e) {
			return true;
		}
	}

	public static void recoverInterruptedMigration(File appDir) {
		synchronized (MIGRATION_LOCK) {
			try {
				rollbackPendingMigration(appDir);
			} catch (IOException e) {
				Log.e(TAG, "Unable to recover interrupted timing DEX migration", e);
			}
		}
	}

	public static void migrate(File appDir) throws IOException {
		synchronized (MIGRATION_LOCK) {
			rollbackPendingMigration(appDir);
			if (!needsMigration(appDir)) {
				return;
			}

			File sourceJar = child(appDir, Config.MIDLET_RES_FILE);
			File manifest = child(appDir, Config.MIDLET_MANIFEST_FILE);
			File activeArchive = findActiveArchive(appDir);
			if (!sourceJar.isFile()) {
				throw new IOException("MIDlet source JAR is missing");
			}
			if (!manifest.isFile()) {
				throw new IOException("MIDlet manifest is missing");
			}

			File tempArchive = new File(appDir, TEMP_ARCHIVE);
			File tempManifest = new File(appDir, TEMP_MANIFEST);
			File pending = new File(appDir, PENDING_MIGRATION);
			File pendingTemp = new File(appDir, PENDING_MIGRATION_TEMP);
			deleteRequired(tempArchive);
			deleteRequired(tempManifest);
			deleteRequired(pendingTemp);

			try {
				Main.main(new String[]{
						"--no-optimize",
						"--output=" + tempArchive.getAbsolutePath(),
						sourceJar.getAbsolutePath()
				});
			} catch (Throwable e) {
				deleteQuietly(tempArchive);
				String detail = e.getMessage();
				throw new IOException(detail == null || detail.trim().isEmpty()
						? "MIDlet conversion failed"
						: "MIDlet conversion failed: " + detail, e);
			}

			try {
				Descriptor rebuiltManifest = new Descriptor(manifest, false);
				rebuiltManifest.setAttribute(Config.MIDLET_DEX_VERSION_ATTRIBUTE,
						Integer.toString(Config.MIDLET_DEX_VERSION));
				rebuiltManifest.writeTo(tempManifest);
				validateArchive(tempArchive, tempManifest);
				writeAscii(pendingTemp, activeArchive == null ? "" : activeArchive.getName());
				if (!pendingTemp.renameTo(pending)) {
					throw new IOException("Unable to prepare migration recovery metadata");
				}
			} catch (IOException e) {
				deleteQuietly(tempArchive);
				deleteQuietly(tempManifest);
				deleteQuietly(pending);
				deleteQuietly(pendingTemp);
				throw e;
			}

			try {
				activate(appDir, activeArchive, tempArchive, tempManifest);
				deleteQuietly(child(appDir, Config.MIDLET_MONITOR_FALLBACK_FILE));
			} catch (IOException e) {
				rollbackPendingMigration(appDir);
				throw e;
			}
		}
	}

	/**
	 * Keeps or rolls back the activated DEX after the main MIDlet class has
	 * either instantiated successfully or failed during launch.
	 */
	public static void completeLaunch(File appDir, boolean successful) {
		synchronized (MIGRATION_LOCK) {
			try {
				if (successful) {
					boolean migrationPending = new File(appDir, PENDING_MIGRATION).isFile();
					// Removing the pending marker is the commit point. Backups
					// must remain recoverable until that marker is gone.
					deleteRequired(new File(appDir, PENDING_MIGRATION));
					deleteRequired(new File(appDir, PENDING_MIGRATION_TEMP));
					deleteQuietly(new File(appDir, BACKUP_ARCHIVE));
					deleteQuietly(new File(appDir, BACKUP_MANIFEST));
					deleteQuietly(new File(appDir, BACKUP_MANIFEST_ABSENT));
					deleteQuietly(new File(appDir, TEMP_ARCHIVE));
					deleteQuietly(new File(appDir, TEMP_MANIFEST));
					deleteQuietly(child(appDir, Config.MIDLET_TIMING_VERSION_FILE));
					deleteQuietly(new File(appDir, LEGACY_BACKUP_VERSION));
					deleteQuietly(new File(appDir, LEGACY_BACKUP_VERSION_ABSENT));
					if (migrationPending) {
						pruneRebuiltDirectory(appDir);
					}
				} else {
					rollbackPendingMigration(appDir);
				}
			} catch (IOException e) {
				Log.e(TAG, successful
						? "Unable to finish timing DEX migration"
						: "Unable to roll back timing DEX migration", e);
			}
		}
	}

	private static void activate(File appDir, File activeArchive, File tempArchive,
			File tempManifest) throws IOException {
		File backupArchive = new File(appDir, BACKUP_ARCHIVE);
		File manifest = child(appDir, Config.MIDLET_MANIFEST_FILE);
		File backupManifest = new File(appDir, BACKUP_MANIFEST);
		File backupManifestAbsent = new File(appDir, BACKUP_MANIFEST_ABSENT);
		File newArchive = child(appDir, Config.MIDLET_DEX_ARCH);

		requireAbsent(backupArchive);
		requireAbsent(backupManifest);
		requireAbsent(backupManifestAbsent);
		if (activeArchive != null && !activeArchive.renameTo(backupArchive)) {
			throw new IOException("Unable to preserve previous MIDlet executable");
		}
		if (manifest.isFile()) {
			if (!manifest.renameTo(backupManifest)) {
				restoreArchive(activeArchive, backupArchive);
				throw new IOException("Unable to preserve previous DEX manifest");
			}
		} else {
			try {
				writeAscii(backupManifestAbsent, "");
			} catch (IOException e) {
				restoreArchive(activeArchive, backupArchive);
				throw e;
			}
		}
		if (!tempArchive.renameTo(newArchive)) {
			restoreManifest(manifest, backupManifest, backupManifestAbsent);
			restoreArchive(activeArchive, backupArchive);
			throw new IOException("Unable to activate rebuilt MIDlet executable");
		}
		if (!tempManifest.renameTo(manifest)) {
			deleteQuietly(newArchive);
			restoreManifest(manifest, backupManifest, backupManifestAbsent);
			restoreArchive(activeArchive, backupArchive);
			throw new IOException("Unable to activate rebuilt DEX manifest");
		}
	}

	private static void validateArchive(File archive, File manifestFile) throws IOException {
		if (!archive.isFile() || archive.length() == 0L) {
			throw new IOException("Converted MIDlet archive is empty");
		}
		Dex dex = new Dex(archive);
		Set<String> definedClasses = new HashSet<>();
		for (ClassDef classDef : dex.classDefs()) {
			definedClasses.add(dex.typeNames().get(classDef.getTypeIndex()));
		}
		if (definedClasses.isEmpty()) {
			throw new IOException("Converted MIDlet archive has no classes");
		}

		Descriptor descriptor = new Descriptor(manifestFile, false);
		Map<String, String> attributes = descriptor.getAttrs();
		int validatedMidlets = 0;
		for (int i = 1; ; i++) {
			String value = attributes.get(Descriptor.MIDLET_N + i);
			if (value == null) {
				break;
			}
			int separator = value.lastIndexOf(',');
			if (separator < 0 || separator == value.length() - 1) {
				throw new IOException("Invalid MIDlet entry: " + value);
			}
			String className = value.substring(separator + 1).trim();
			String classDescriptor = 'L' + className.replace('.', '/') + ';';
			if (!definedClasses.contains(classDescriptor)) {
				throw new IOException("Converted MIDlet class is missing: " + className);
			}
			validatedMidlets++;
		}
		if (validatedMidlets == 0) {
			throw new IOException("MIDlet manifest has no launchable classes");
		}
	}

	private static void rollbackPendingMigration(File appDir) throws IOException {
		File pending = new File(appDir, PENDING_MIGRATION);
		if (!pending.isFile()) {
			deleteQuietly(new File(appDir, TEMP_ARCHIVE));
			deleteQuietly(new File(appDir, TEMP_MANIFEST));
			deleteQuietly(new File(appDir, PENDING_MIGRATION_TEMP));
			return;
		}

		String originalName = FileUtils.getText(pending.getPath()).trim();
		if (originalName.contains("/")
				|| originalName.contains("\\")) {
			throw new IOException("Invalid timing migration recovery metadata");
		}

		File backupArchive = new File(appDir, BACKUP_ARCHIVE);
		File currentArchive = child(appDir, Config.MIDLET_DEX_ARCH);
		if (backupArchive.isFile()) {
			deleteRequired(currentArchive);
			if (originalName.isEmpty() || !backupArchive.renameTo(new File(appDir, originalName))) {
				throw new IOException("Unable to restore previous MIDlet executable");
			}
		} else if (originalName.isEmpty()) {
			deleteRequired(currentArchive);
		}
		File manifest = child(appDir, Config.MIDLET_MANIFEST_FILE);
		File backupManifest = new File(appDir, BACKUP_MANIFEST);
		File backupManifestAbsent = new File(appDir, BACKUP_MANIFEST_ABSENT);
		restoreManifest(manifest, backupManifest, backupManifestAbsent);
		deleteRequired(pending);
		deleteQuietly(new File(appDir, TEMP_ARCHIVE));
		deleteQuietly(new File(appDir, TEMP_MANIFEST));
		deleteQuietly(new File(appDir, PENDING_MIGRATION_TEMP));
		deleteQuietly(new File(appDir, LEGACY_BACKUP_VERSION));
		deleteQuietly(new File(appDir, LEGACY_BACKUP_VERSION_ABSENT));
	}

	private static File findActiveArchive(File appDir) {
		File archive = child(appDir, Config.MIDLET_DEX_ARCH);
		if (archive.isFile()) {
			return archive;
		}
		File dex = child(appDir, Config.MIDLET_DEX_FILE);
		return dex.isFile() ? dex : null;
	}

	private static File child(File parent, String name) {
		int start = 0;
		while (start < name.length()
				&& (name.charAt(start) == '/' || name.charAt(start) == '\\')) {
			start++;
		}
		return new File(parent, name.substring(start));
	}

	private static void restoreArchive(File original, File backup) throws IOException {
		if (backup.isFile() && !backup.renameTo(original)) {
			throw new IOException("Unable to restore previous MIDlet executable");
		}
	}

	private static void restoreManifest(File manifest, File backup, File absent)
				throws IOException {
		if (backup.isFile()) {
			deleteRequired(manifest);
			if (!backup.renameTo(manifest)) {
				throw new IOException("Unable to restore previous DEX manifest");
			}
		} else if (absent.isFile()) {
			deleteRequired(manifest);
		}
		deleteRequired(absent);
	}

	private static void writeAscii(File file, String text) throws IOException {
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(text.getBytes(StandardCharsets.US_ASCII));
			output.getFD().sync();
		}
	}

	private static void deleteRequired(File file) throws IOException {
		if (file.exists() && !file.delete()) {
			throw new IOException("Unable to remove stale migration file: " + file.getName());
		}
	}

	private static void pruneRebuiltDirectory(File appDir) throws IOException {
		File[] files = appDir.listFiles();
		if (files == null) {
			throw new IOException("Unable to inspect rebuilt MIDlet directory");
		}
		String root = appDir.getCanonicalPath() + File.separator;
		for (File file : files) {
			String name = file.getName();
			if (name.equals(child(appDir, Config.MIDLET_RES_FILE).getName())
					|| name.equals(child(appDir, Config.MIDLET_RES_JAD_FILE).getName())
					|| name.equals(child(appDir, Config.MIDLET_ICON_FILE).getName())
					|| name.equals(child(appDir, Config.MIDLET_DEX_ARCH).getName())
					|| name.equals(child(appDir, Config.MIDLET_MANIFEST_FILE).getName())) {
				continue;
			}
			deleteTreeRequired(file, root);
		}
	}

	private static void deleteTreeRequired(File file, String root) throws IOException {
		String path = file.getCanonicalPath();
		if (!path.startsWith(root)) {
			throw new IOException("Refusing to remove a path outside the MIDlet directory");
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children == null) {
				throw new IOException("Unable to inspect stale MIDlet directory");
			}
			for (File child : children) {
				deleteTreeRequired(child, root);
			}
		}
		if (file.exists() && !file.delete()) {
			throw new IOException("Unable to remove stale MIDlet file: " + file.getName());
		}
	}

	private static void requireAbsent(File file) throws IOException {
		if (file.exists()) {
			throw new IOException("Unresolved migration recovery file: " + file.getName());
		}
	}

	private static void deleteQuietly(File file) {
		if (file.exists() && !file.delete()) {
			Log.w(TAG, "Unable to remove stale migration file: " + file.getName());
		}
	}
}
