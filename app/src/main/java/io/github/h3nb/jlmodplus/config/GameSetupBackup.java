/*
 * Modified for JL-Mod Plus.
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

package io.github.h3nb.jlmodplus.config;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/** One recoverable, per-game snapshot used before replacing a materialized setup. */
final class GameSetupBackup {
	private static final int SCHEMA_VERSION = 1;
	private static final String BACKUP_DIR = ".previous_setup";
	private static final String TEMP_DIR = ".previous_setup.tmp";
	private static final String OLD_DIR = ".previous_setup.old";
	private static final String CONFIG_FILE = "config.json";
	private static final String KEY_LAYOUT_FILE = "VirtualKeyboardLayout";
	private static final String METADATA_FILE = "metadata.json";
	private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

	private GameSetupBackup() {
	}

	static boolean hasBackup(@NonNull File configDir) {
		File backupDir = backupDir(configDir);
		return backupDir.isDirectory()
				&& new File(backupDir, CONFIG_FILE).isFile()
				&& new File(backupDir, METADATA_FILE).isFile();
	}

	static void capture(@NonNull File configDir, @Nullable String profileOrigin,
			boolean builtInThemeLinked) throws IOException {
		File currentConfig = new File(configDir, CONFIG_FILE);
		if (!currentConfig.isFile()) {
			throw new IOException("Current game configuration is not materialized");
		}
		if (!configDir.isDirectory() && !configDir.mkdirs()) {
			throw new IOException("Unable to create game configuration directory");
		}

		File temporary = new File(configDir, TEMP_DIR);
		File previous = backupDir(configDir);
		File old = new File(configDir, OLD_DIR);
		deleteRecursively(temporary);
		deleteRecursively(old);
		boolean movedPrevious = false;
		try {
			if (!temporary.mkdirs()) {
				throw new IOException("Unable to create temporary previous setup directory");
			}
			copyFile(currentConfig, new File(temporary, CONFIG_FILE));
			File currentKeyboard = new File(configDir, KEY_LAYOUT_FILE);
			boolean hadKeyboardLayout = currentKeyboard.isFile();
			if (hadKeyboardLayout) {
				copyFile(currentKeyboard, new File(temporary, KEY_LAYOUT_FILE));
			}
			Metadata metadata = new Metadata();
			metadata.schemaVersion = SCHEMA_VERSION;
			metadata.hadKeyboardLayout = hadKeyboardLayout;
			metadata.profileOrigin = profileOrigin;
			metadata.builtInThemeLinked = builtInThemeLinked;
			try (FileWriter writer = new FileWriter(new File(temporary, METADATA_FILE))) {
				GSON.toJson(metadata, writer);
			}

			if (previous.exists()) {
				if (!previous.renameTo(old)) {
					throw new IOException("Unable to rotate previous setup backup");
				}
				movedPrevious = true;
			}
			if (!temporary.renameTo(previous)) {
				throw new IOException("Unable to install previous setup backup");
			}
			if (movedPrevious) {
				deleteRecursively(old);
			}
		} catch (IOException | RuntimeException failure) {
			// Keep the last complete backup if replacing the new temporary copy failed.
			deleteRecursively(previous);
			if (movedPrevious && old.exists() && !old.renameTo(previous)) {
				failure.addSuppressed(new IOException("Unable to restore previous setup backup"));
			}
			if (failure instanceof IOException) {
				throw (IOException) failure;
			}
			throw failure;
		} finally {
			deleteRecursively(temporary);
			deleteRecursively(old);
		}
	}

	@NonNull
	static RestoredMetadata restore(@NonNull File configDir) throws IOException {
		File backup = backupDir(configDir);
		if (!hasBackup(configDir)) {
			throw new IOException("No previous setup backup exists");
		}
		Metadata metadata = readMetadata(new File(backup, METADATA_FILE));
		if (metadata.schemaVersion != SCHEMA_VERSION) {
			throw new IOException("Unsupported previous setup backup schema");
		}
		File backupKeyboard = new File(backup, KEY_LAYOUT_FILE);
		if (metadata.hadKeyboardLayout && !backupKeyboard.isFile()) {
			throw new IOException("Previous setup keyboard layout is missing");
		}
		copyFile(new File(backup, CONFIG_FILE), new File(configDir, CONFIG_FILE));
		File currentKeyboard = new File(configDir, KEY_LAYOUT_FILE);
		if (metadata.hadKeyboardLayout) {
			copyFile(backupKeyboard, currentKeyboard);
		} else if (currentKeyboard.exists() && !currentKeyboard.delete()) {
			throw new IOException("Unable to remove current keyboard layout");
		}
		RestoredMetadata restored = new RestoredMetadata(
				metadata.profileOrigin, metadata.builtInThemeLinked);
		clear(configDir);
		return restored;
	}

	static void clear(@NonNull File configDir) {
		deleteRecursively(backupDir(configDir));
		deleteRecursively(new File(configDir, TEMP_DIR));
		deleteRecursively(new File(configDir, OLD_DIR));
	}

	@NonNull
	private static File backupDir(File configDir) {
		return new File(configDir, BACKUP_DIR);
	}

	@NonNull
	private static Metadata readMetadata(File file) throws IOException {
		try (FileReader reader = new FileReader(file)) {
			Metadata metadata = GSON.fromJson(reader, Metadata.class);
			if (metadata == null) {
				throw new IOException("Previous setup metadata is empty");
			}
			return metadata;
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Unable to read previous setup metadata", e);
		}
	}

	private static void copyFile(File source, File destination) throws IOException {
		try (FileInputStream input = new FileInputStream(source);
			 FileOutputStream output = new FileOutputStream(destination)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				output.write(buffer, 0, count);
			}
		}
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		//noinspection ResultOfMethodCallIgnored
		file.delete();
	}

	static final class RestoredMetadata {
		@Nullable final String profileOrigin;
		final boolean builtInThemeLinked;

		RestoredMetadata(@Nullable String profileOrigin, boolean builtInThemeLinked) {
			this.profileOrigin = profileOrigin;
			this.builtInThemeLinked = builtInThemeLinked;
		}
	}

	private static final class Metadata {
		int schemaVersion;
		boolean hadKeyboardLayout;
		@Nullable String profileOrigin;
		boolean builtInThemeLinked;
	}
}
