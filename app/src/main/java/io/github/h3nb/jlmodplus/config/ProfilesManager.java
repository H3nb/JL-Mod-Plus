/*
 * Copyright 2018 Nikita Shakarun
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

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import javax.microedition.shell.timing.TimingMode;
import io.github.h3nb.jlmodplus.util.FileUtils;
import io.github.h3nb.jlmodplus.util.XmlUtils;

public class ProfilesManager {

	private static final String TAG = ProfilesManager.class.getName();
	private static final String ATOMIC_NEW_SUFFIX = ".new";
	private static final String ATOMIC_BACKUP_SUFFIX = ".bak";
	private static final String PRESET_SYNC_STAGING_DIR = ".preset-sync.tmp";
	private static final String PRESET_SYNC_ROLLBACK_DIR = ".preset-sync.rollback";
	private static final String PRESET_SYNC_READY_MARKER = ".ready";
	static final String PRESET_SAVE_ROLLBACK_DIR = ".preset-save.rollback";
	static final String PRESET_SAVE_READY_MARKER = ".ready";
	static final String PRESET_SAVE_NEW_PROFILE_MARKER = ".new-profile";
	private static final Object PRESET_SOURCE_LOCK = new Object();
	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	/** Shared process-local monitor for all named-preset source reads, writes, and lifecycle changes. */
	@NonNull
	static Object presetSourceLock() {
		return PRESET_SOURCE_LOCK;
	}

	/** Identifies whether legacy linkage metadata is meaningful for a config load. */
	public enum BackgroundMigrationContext {
		NAMED_PROFILE,
		MIDLET_CONFIG
	}

	static ArrayList<Profile> getProfiles() {
		File root = new File(Config.getProfilesDir());
		return getList(root);
	}

	/** A disk-only inspection result prepared before Compose state is updated. */
	enum CapabilityStatus {
		ABSENT,
		READY,
		UNAVAILABLE
	}

	static final class Capability {
		@NonNull final CapabilityStatus status;
		@Nullable final String reason;

		Capability(@NonNull CapabilityStatus status, @Nullable String reason) {
			this.status = status;
			this.reason = reason;
		}

		boolean isReady() {
			return status == CapabilityStatus.READY;
		}
	}

	static final class ProfileInfo {
		@NonNull final Profile profile;
		@Nullable final ProfileModel config;
		@NonNull final Capability settings;
		@NonNull final Capability keyboardLayout;

		ProfileInfo(@NonNull Profile profile, @Nullable ProfileModel config,
				@NonNull Capability settings, @NonNull Capability keyboardLayout) {
			this.profile = profile;
			this.config = config;
			this.settings = settings;
			this.keyboardLayout = keyboardLayout;
		}
	}

	/** Performs profile parsing and artifact checks in the caller's worker thread. */
	@NonNull
	static ArrayList<ProfileInfo> inspectProfiles(@Nullable List<Profile> profiles) {
		ArrayList<ProfileInfo> result = new ArrayList<>();
		if (profiles == null) return result;
		for (Profile profile : profiles) {
			result.add(inspectProfile(profile));
		}
		return result;
	}

	/** Computes both independent capabilities once so picker and operation code share the same view. */
	@NonNull
	static ProfileInfo inspectProfile(@NonNull Profile profile) {
		return inspectProfile(profile, profile.getDir());
	}

	/** File-level entry point kept package-private for deterministic source-recovery tests. */
	@NonNull
	static ProfileInfo inspectProfile(@NonNull Profile profile, @NonNull File profileDir) {
		synchronized (PRESET_SOURCE_LOCK) {
		try {
			recoverInterruptedPresetSave(profileDir);
		} catch (IOException | RuntimeException recoveryFailure) {
			Capability unavailable = new Capability(
					CapabilityStatus.UNAVAILABLE, "preset source recovery failed");
			return new ProfileInfo(profile, null, unavailable, unavailable);
		}

		File configFile = new File(profileDir, Config.MIDLET_CONFIG_FILE);
		File legacyConfig = new File(profileDir, "config.xml");
		boolean hasConfigArtifact = configFile.exists() || legacyConfig.exists();
		ProfileModel config = hasConfigArtifact
				? loadConfig(profileDir, false, BackgroundMigrationContext.NAMED_PROFILE, false)
				: null;
		Capability settings = hasConfigArtifact
				? new Capability(config == null ? CapabilityStatus.UNAVAILABLE : CapabilityStatus.READY,
						config == null ? "configuration cannot be parsed" : null)
				: new Capability(CapabilityStatus.ABSENT, null);
		File keyLayout = new File(profileDir, Config.MIDLET_KEY_LAYOUT_FILE);
		boolean hasLayoutArtifact = keyLayout.exists();
		String layoutReason = hasLayoutArtifact
				? KeyboardLayoutValidator.validate(keyLayout) : null;
		Capability layout = !hasLayoutArtifact
				? new Capability(CapabilityStatus.ABSENT, null)
				: new Capability(layoutReason == null ? CapabilityStatus.READY : CapabilityStatus.UNAVAILABLE,
						layoutReason);
		return new ProfileInfo(profile, config, settings, layout);
		}
	}

	/** Returns true when a directory or a saved layout already occupies this collection name. */
	static boolean profileNameExists(@Nullable String rawName) {
		if (!Profile.isValidName(rawName)) return false;
		String name = rawName.trim();
		for (Profile profile : getProfiles()) {
			if (profile.getName().equalsIgnoreCase(name)) return true;
		}
		File root = new File(Config.getProfilesDir());
		return new File(root, name).exists()
				|| new File(root, name + Config.MIDLET_CONFIG_FILE).exists()
				|| new File(root, name + Config.MIDLET_KEY_LAYOUT_FILE).exists();
	}

	@NonNull
	static ArrayList<Profile> getList(File root) {
		synchronized (PRESET_SOURCE_LOCK) {
		File[] dirs = root.listFiles();
		if (dirs == null) {
			return new ArrayList<>();
		}
		int size = dirs.length;
		ArrayList<Profile> result = new ArrayList<>(size);
		for (File dir : dirs) {
			if (!dir.isDirectory() || PresetLifecycle.isInternalRenameStagingName(dir.getName())) {
				continue;
			}
			try {
				recoverInterruptedPresetSave(dir);
			} catch (IOException | RuntimeException recoveryFailure) {
				if (isInvisibleInterruptedNewProfile(dir)) {
					continue;
				}
			}
			if (dir.isDirectory()) {
				result.add(new Profile(dir.getName()));
			}
		}
		return result;
		}
	}

	static void load(Profile from, String toPath, boolean config, boolean keyboard)
			throws IOException {
		synchronized (PRESET_SOURCE_LOCK) {
		if (!config && !keyboard) {
			return;
		}
		ProfileInfo inspected = inspectProfile(from);
		if (config && !inspected.settings.isReady()) {
			throw new IOException("Profile configuration is not loadable");
		}
		if (keyboard && !inspected.keyboardLayout.isReady()) {
			throw new IOException("Profile keyboard layout is not loadable");
		}
		applySnapshotArtifacts(from.getDir(), new File(toPath), inspected.config, config, keyboard);
		}
	}

	/**
	 * Creates a byte-preserving editor working copy from one coherent committed preset source.
	 */
	static void copyPresetSourceForEdit(@NonNull Profile profile, @NonNull File draftDir)
			throws IOException {
		copyPresetSourceForEdit(profile.getDir(), draftDir);
	}

	/** File-level entry point kept package-private for deterministic source-concurrency tests. */
	static void copyPresetSourceForEdit(@NonNull File sourceDir, @NonNull File draftDir)
			throws IOException {
		synchronized (PRESET_SOURCE_LOCK) {
		recoverInterruptedPresetSave(sourceDir);
		copyPresetArtifactIfFile(new File(sourceDir, Config.MIDLET_CONFIG_FILE),
				new File(draftDir, Config.MIDLET_CONFIG_FILE));
		copyPresetArtifactIfFile(new File(sourceDir, "config.xml"),
				new File(draftDir, "config.xml"));
		copyPresetArtifactIfFile(new File(sourceDir, Config.MIDLET_KEY_LAYOUT_FILE),
				new File(draftDir, Config.MIDLET_KEY_LAYOUT_FILE));
		}
	}

	private static void copyPresetArtifactIfFile(@NonNull File source, @NonNull File destination)
			throws IOException {
		if (source.isFile()) FileUtils.copyFileUsingChannel(source, destination);
	}

	/**
	 * Mirrors one complete named preset into a MIDlet-local materialized snapshot.
	 *
	 * <p>Exact sync owns config.json and the complete VirtualKeyboardLayout atomic family. Its
	 * rollback state is intentionally separate from partial preset application.</p>
	 */
	static void syncSnapshot(@NonNull Profile from, @NonNull String toPath) throws IOException {
		syncSnapshot(from.getDir(), new File(toPath));
	}

	/** File-level entry point kept package-private so crash-recovery state can be covered by JVM tests. */
	static void syncSnapshot(@NonNull File sourceDir, @NonNull File targetDir) throws IOException {
		synchronized (PRESET_SOURCE_LOCK) {
		if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
			throw new IOException("Unable to create configuration directory");
		}

		File staging = new File(targetDir, PRESET_SYNC_STAGING_DIR);
		File rollback = new File(targetDir, PRESET_SYNC_ROLLBACK_DIR);
		File dstConfig = new File(targetDir, Config.MIDLET_CONFIG_FILE);
		File dstKeyLayout = new File(targetDir, Config.MIDLET_KEY_LAYOUT_FILE);

		// Recover destination state before looking at the current source. A preset may have become
		// unavailable since the interrupted operation, but the previous local snapshot is still owned.
		recoverInterruptedSnapshotSync(targetDir);
		normalizeVirtualKeyboardLayout(dstKeyLayout);
		recoverInterruptedPresetSave(sourceDir);

		CompleteSnapshot snapshot = inspectCompleteSnapshot(sourceDir);
		deleteRecursively(staging);
		deleteRecursively(rollback);
		if (staging.exists() || rollback.exists()) {
			throw new IOException("Unable to clear stale preset sync state");
		}

		boolean rollbackSucceeded = true;
		File ready = new File(rollback, PRESET_SYNC_READY_MARKER);
		try {
			if (!staging.mkdirs() || !rollback.mkdirs()) {
				throw new IOException("Unable to create preset sync staging");
			}

			File stagedConfig = new File(staging, Config.MIDLET_CONFIG_FILE);
			File stagedLayout = new File(staging, Config.MIDLET_KEY_LAYOUT_FILE);
			File sourceConfig = new File(sourceDir, Config.MIDLET_CONFIG_FILE);
			if (sourceConfig.isFile()) {
				FileUtils.copyFileUsingChannel(sourceConfig, stagedConfig);
			} else {
				File originalDir = snapshot.config.dir;
				try {
					snapshot.config.dir = staging;
					if (!saveConfig(snapshot.config)) {
						throw new IOException("Unable to materialize profile configuration");
					}
				} finally {
					snapshot.config.dir = originalDir;
				}
			}
			if (!isValidConfigFile(stagedConfig)) {
				throw new IOException("Profile configuration changed while applying");
			}

			if (snapshot.hasKeyboardLayout) {
				File sourceLayout = new File(sourceDir, Config.MIDLET_KEY_LAYOUT_FILE);
				FileUtils.copyFileUsingChannel(sourceLayout, stagedLayout);
				String layoutError = KeyboardLayoutValidator.validate(stagedLayout);
				if (layoutError != null) {
					throw new IOException("Profile keyboard layout changed while applying: " + layoutError);
				}
			}

			CompleteSnapshot current = inspectCompleteSnapshot(sourceDir);
			if (current.hasKeyboardLayout != snapshot.hasKeyboardLayout) {
				throw new IOException("Profile keyboard layout changed while applying");
			}

			// Destination publication may not begin until the complete previous snapshot is recoverable.
			backupExisting(dstConfig, rollback, "config.json");
			backupExisting(dstKeyLayout, rollback, "VirtualKeyboardLayout");
			if (!ready.createNewFile()) {
				throw new IOException("Unable to mark preset sync ready for publication");
			}

			FileUtils.copyFileUsingChannel(stagedConfig, dstConfig);
			if (snapshot.hasKeyboardLayout) {
				FileUtils.copyFileUsingChannel(stagedLayout, dstKeyLayout);
			} else if (dstKeyLayout.exists() && !dstKeyLayout.delete()) {
				throw new IOException("Unable to remove stale key layout");
			}
			removeVirtualKeyboardLayoutSidecars(dstKeyLayout);

			// Disarm recovery before directory cleanup. Failure to remove the marker is a failed
			// transaction cleanup while the complete rollback snapshot is still available.
			if (!ready.delete()) {
				throw new IOException("Unable to clear preset sync publication marker");
			}
			deleteRecursively(rollback);
		} catch (IOException | RuntimeException failure) {
			if (ready.isFile()) {
				rollbackSucceeded &= tryRestore(failure, dstConfig, rollback, "config.json");
				rollbackSucceeded &= tryRestore(failure, dstKeyLayout, rollback,
						"VirtualKeyboardLayout");
				try {
					removeVirtualKeyboardLayoutSidecars(dstKeyLayout);
				} catch (IOException cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
					rollbackSucceeded = false;
				}
			}
			if (rollbackSucceeded) deleteRecursively(rollback);
			if (failure instanceof IOException) throw (IOException) failure;
			throw failure;
		} finally {
			deleteRecursively(staging);
		}
		}
	}

	private static final class CompleteSnapshot {
		@NonNull final ProfileModel config;
		final boolean hasKeyboardLayout;

		CompleteSnapshot(@NonNull ProfileModel config, boolean hasKeyboardLayout) {
			this.config = config;
			this.hasKeyboardLayout = hasKeyboardLayout;
		}
	}

	@NonNull
	private static CompleteSnapshot inspectCompleteSnapshot(@NonNull File sourceDir)
			throws IOException {
		File sourceConfig = new File(sourceDir, Config.MIDLET_CONFIG_FILE);
		File legacyConfig = new File(sourceDir, "config.xml");
		// A preset save can be interrupted between the same atomic config renames used elsewhere.
		// Recover that last-known-good file before deciding whether the source snapshot is readable.
		recoverAtomicConfig(sourceConfig);
		if (sourceConfig.exists() && !isValidConfigFile(sourceConfig)) {
			throw new IOException("Profile configuration is not loadable");
		}
		if (!sourceConfig.exists() && !legacyConfig.isFile()) {
			throw new IOException("Profile configuration is missing");
		}
		ProfileModel config = loadConfig(
				sourceDir, false, BackgroundMigrationContext.NAMED_PROFILE, false);
		if (config == null) {
			throw new IOException("Profile configuration is not loadable");
		}

		File sourceLayout = new File(sourceDir, Config.MIDLET_KEY_LAYOUT_FILE);
		boolean hasKeyboardLayout = sourceLayout.exists();
		if (hasKeyboardLayout) {
			String layoutError = KeyboardLayoutValidator.validate(sourceLayout);
			if (layoutError != null) {
				throw new IOException("Profile keyboard layout is not loadable: " + layoutError);
			}
		}
		if (config.vkType == VirtualKeyboard.TYPE_CUSTOM && !hasKeyboardLayout) {
			throw new IOException("Custom profile requires a keyboard layout");
		}
		return new CompleteSnapshot(config, hasKeyboardLayout);
	}

	static void recoverInterruptedSnapshotSync(@NonNull File targetDir) throws IOException {
		File rollback = new File(targetDir, PRESET_SYNC_ROLLBACK_DIR);
		File staging = new File(targetDir, PRESET_SYNC_STAGING_DIR);
		if (rollback.exists()) {
			File ready = new File(rollback, PRESET_SYNC_READY_MARKER);
			if (ready.isFile()) {
				File dstConfig = new File(targetDir, Config.MIDLET_CONFIG_FILE);
				File dstKeyLayout = new File(targetDir, Config.MIDLET_KEY_LAYOUT_FILE);
				restoreExisting(dstConfig, rollback, "config.json");
				restoreExisting(dstKeyLayout, rollback, "VirtualKeyboardLayout");
				removeVirtualKeyboardLayoutSidecars(dstKeyLayout);
			}
			deleteRecursively(rollback);
			if (rollback.exists()) {
				throw new IOException("Unable to clear interrupted preset sync rollback state");
			}
		}
		deleteRecursively(staging);
		if (staging.exists()) {
			throw new IOException("Unable to clear interrupted preset sync staging state");
		}
	}

	static boolean hasRecoverableLocalKeyboardLayout(@NonNull File configDir) {
		File layout = new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE);
		return layout.exists() || atomicSibling(layout, ATOMIC_BACKUP_SUFFIX).exists();
	}

	/**
	 * Removes one active MIDlet's persisted keyboard-layout family for an explicit local reset.
	 *
	 * <p>Sidecars are removed before the main layout so a sidecar-cleanup failure cannot delete the
	 * currently effective main layout.</p>
	 */
	static boolean removeLocalKeyboardLayout(@NonNull File configDir) {
		File layout = new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE);
		File temporary = atomicSibling(layout, ATOMIC_NEW_SUFFIX);
		File backup = atomicSibling(layout, ATOMIC_BACKUP_SUFFIX);
		if (temporary.exists() && !temporary.delete()) {
			return false;
		}
		if (backup.exists() && !backup.delete()) {
			return false;
		}
		return !layout.exists() || layout.delete();
	}

	/**
	 * Applies the same effective recovery order as VirtualKeyboard.recoverLayoutFile(), but fails
	 * closed so exact sync never captures an ambiguous layout family as its rollback baseline.
	 */
	private static void normalizeVirtualKeyboardLayout(@NonNull File layout) throws IOException {
		File temporary = atomicSibling(layout, ATOMIC_NEW_SUFFIX);
		File backup = atomicSibling(layout, ATOMIC_BACKUP_SUFFIX);
		if (backup.exists()) {
			if (layout.exists()) {
				if (!backup.delete()) {
					throw new IOException("Unable to remove stale virtual keyboard layout backup");
				}
			} else if (!backup.renameTo(layout)) {
				throw new IOException("Unable to restore virtual keyboard layout backup");
			}
		}
		if (temporary.exists() && !temporary.delete()) {
			throw new IOException("Unable to remove stale virtual keyboard layout write");
		}
	}

	private static void removeVirtualKeyboardLayoutSidecars(@NonNull File layout) throws IOException {
		File temporary = atomicSibling(layout, ATOMIC_NEW_SUFFIX);
		File backup = atomicSibling(layout, ATOMIC_BACKUP_SUFFIX);
		if (temporary.exists() && !temporary.delete()) {
			throw new IOException("Unable to remove virtual keyboard layout write sidecar");
		}
		if (backup.exists() && !backup.delete()) {
			throw new IOException("Unable to remove virtual keyboard layout backup sidecar");
		}
	}

	private static void applySnapshotArtifacts(@NonNull File sourceDir, @NonNull File targetDir,
			@Nullable ProfileModel sourceConfig, boolean config, boolean keyboard) throws IOException {
		if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
			throw new IOException("Unable to create configuration directory");
		}
		File staging = new File(targetDir, ".preset-apply.tmp");
		File rollback = new File(targetDir, ".preset-apply.rollback");
		deleteRecursively(staging);
		deleteRecursively(rollback);
		boolean commitStarted = false;
		boolean rollbackSucceeded = true;
		File dstConfig = new File(targetDir, Config.MIDLET_CONFIG_FILE);
		File dstKeyLayout = new File(targetDir, Config.MIDLET_KEY_LAYOUT_FILE);
		try {
			if (!staging.mkdirs() || !rollback.mkdirs()) {
				throw new IOException("Unable to create preset staging directory");
			}
			File stagedConfig = new File(staging, Config.MIDLET_CONFIG_FILE);
			File stagedLayout = new File(staging, Config.MIDLET_KEY_LAYOUT_FILE);
			if (config) {
				File source = new File(sourceDir, Config.MIDLET_CONFIG_FILE);
				if (source.isFile()) {
					FileUtils.copyFileUsingChannel(source, stagedConfig);
				} else {
					if (sourceConfig == null) {
						throw new IOException("Profile configuration is not loadable");
					}
					File originalDir = sourceConfig.dir;
					try {
						sourceConfig.dir = staging;
						if (!saveConfig(sourceConfig)) {
							throw new IOException("Unable to materialize profile configuration");
						}
					} finally {
						sourceConfig.dir = originalDir;
					}
				}
				if (!isValidConfigFile(stagedConfig)) {
					throw new IOException("Profile configuration changed while applying");
				}
			}
			if (keyboard) {
				File sourceLayout = new File(sourceDir, Config.MIDLET_KEY_LAYOUT_FILE);
				FileUtils.copyFileUsingChannel(sourceLayout, stagedLayout);
				String layoutError = KeyboardLayoutValidator.validate(stagedLayout);
				if (layoutError != null) {
					throw new IOException("Profile keyboard layout changed while applying: " + layoutError);
				}
			}

			if (config) backupExisting(dstConfig, rollback, "config.json");
			if (keyboard) backupExisting(dstKeyLayout, rollback, "VirtualKeyboardLayout");
			commitStarted = true;

			if (config) {
				FileUtils.copyFileUsingChannel(stagedConfig, dstConfig);
			}
			if (keyboard) {
				FileUtils.copyFileUsingChannel(stagedLayout, dstKeyLayout);
			}
		} catch (IOException | RuntimeException failure) {
			if (commitStarted) {
				if (config) {
					rollbackSucceeded &= tryRestore(failure, dstConfig, rollback, "config.json");
				}
				if (keyboard) {
					rollbackSucceeded &= tryRestore(failure, dstKeyLayout, rollback,
							"VirtualKeyboardLayout");
				}
			}
			if (failure instanceof IOException) throw (IOException) failure;
			throw failure;
		} finally {
			deleteRecursively(staging);
			if (rollbackSucceeded) deleteRecursively(rollback);
		}
	}

	private static boolean backupExisting(File destination, File rollback, String name)
			throws IOException {
		File marker = new File(rollback, name + ".present");
		if (destination.exists()) {
			if (!destination.isFile()) {
				throw new IOException("Profile artifact is not a file: " + destination.getName());
			}
			FileUtils.copyFileUsingChannel(destination, new File(rollback, name));
			if (!marker.createNewFile()) throw new IOException("Unable to mark existing file");
			return true;
		}
		return false;
	}

	private static void restoreExisting(File destination, File rollback, String name)
			throws IOException {
		File saved = new File(rollback, name);
		if (new File(rollback, name + ".present").isFile()) {
			FileUtils.copyFileUsingChannel(saved, destination);
		} else if (destination.exists() && !destination.delete()) {
			throw new IOException("Unable to remove partially applied file");
		}
	}

	private static boolean tryRestore(Throwable failure, File destination, File rollback, String name) {
		try {
			restoreExisting(destination, rollback, name);
			return true;
		} catch (IOException rollbackFailure) {
			failure.addSuppressed(rollbackFailure);
			return false;
		}
	}

	static void recoverInterruptedPresetSave(@NonNull File profileDir) throws IOException {
		synchronized (PRESET_SOURCE_LOCK) {
		File rollback = new File(profileDir, PRESET_SAVE_ROLLBACK_DIR);
		if (!rollback.exists()) return;
		if (!rollback.isDirectory()) {
			throw new IOException("Preset save rollback state is not a directory");
		}

		File ready = new File(rollback, PRESET_SAVE_READY_MARKER);
		File newProfile = new File(rollback, PRESET_SAVE_NEW_PROFILE_MARKER);
		if (ready.exists() && !ready.isFile()) {
			throw new IOException("Preset save ready marker is invalid");
		}
		if (newProfile.exists() && !newProfile.isFile()) {
			throw new IOException("Preset save new-profile marker is invalid");
		}
		boolean transactionCreatedProfile = newProfile.isFile();

		if (ready.isFile()) {
			restoreExisting(new File(profileDir, Config.MIDLET_CONFIG_FILE),
					rollback, "config.json");
			restoreExisting(new File(profileDir, "config.xml"),
					rollback, "config.xml");
			restoreExisting(new File(profileDir, Config.MIDLET_KEY_LAYOUT_FILE),
					rollback, "VirtualKeyboardLayout");
		}

		boolean removeTransactionCreatedProfile =
				transactionCreatedProfile && containsOnlyTransactionState(profileDir, rollback);
		deleteRecursively(rollback);
		if (rollback.exists()) {
			throw new IOException("Unable to clear interrupted preset save rollback state");
		}

		if (removeTransactionCreatedProfile && profileDir.isDirectory() && !profileDir.delete()) {
			// Preserve retry evidence when the final empty-directory cleanup itself fails.
			File retryRollback = new File(profileDir, PRESET_SAVE_ROLLBACK_DIR);
			if (retryRollback.mkdir()) {
				try {
					new File(retryRollback, PRESET_SAVE_NEW_PROFILE_MARKER).createNewFile();
				} catch (IOException ignored) {
					// Best effort only; the original cleanup failure remains authoritative.
				}
			}
			throw new IOException("Unable to remove interrupted new preset directory");
		}
		}
	}

	private static boolean containsOnlyTransactionState(
			@NonNull File profileDir, @NonNull File rollback) {
		File[] entries = profileDir.listFiles();
		if (entries == null) return false;
		for (File entry : entries) {
			if (!entry.equals(rollback)) return false;
		}
		return true;
	}

	private static boolean isInvisibleInterruptedNewProfile(@NonNull File profileDir) {
		File rollback = new File(profileDir, PRESET_SAVE_ROLLBACK_DIR);
		if (!new File(rollback, PRESET_SAVE_NEW_PROFILE_MARKER).isFile()) return false;
		if (new File(rollback, PRESET_SAVE_READY_MARKER).isFile()) return true;
		return containsOnlyTransactionState(profileDir, rollback);
	}

	private static PresetSaveTransaction beginPresetSave(
			@NonNull File profileDir, boolean transactionCreatedProfile) throws IOException {
		if (!profileDir.isDirectory() && !profileDir.mkdirs()) {
			throw new IOException("Unable to create preset directory");
		}
		File rollback = new File(profileDir, PRESET_SAVE_ROLLBACK_DIR);
		if (rollback.exists() || !rollback.mkdir()) {
			if (transactionCreatedProfile) {
				File[] entries = profileDir.listFiles();
				if (entries != null && entries.length == 0) {
					// Best effort: no preset artifact has been published yet.
					profileDir.delete();
				}
			}
			throw new IOException("Unable to create preset save rollback directory");
		}
		try {
			if (transactionCreatedProfile
					&& !new File(rollback, PRESET_SAVE_NEW_PROFILE_MARKER).createNewFile()) {
				throw new IOException("Unable to mark new preset transaction");
			}
			backupExisting(new File(profileDir, Config.MIDLET_CONFIG_FILE),
					rollback, "config.json");
			backupExisting(new File(profileDir, "config.xml"),
					rollback, "config.xml");
			backupExisting(new File(profileDir, Config.MIDLET_KEY_LAYOUT_FILE),
					rollback, "VirtualKeyboardLayout");
			File ready = new File(rollback, PRESET_SAVE_READY_MARKER);
			if (!ready.createNewFile()) {
				throw new IOException("Unable to mark preset save ready for publication");
			}
			return new PresetSaveTransaction(profileDir, rollback, ready);
		} catch (IOException | RuntimeException failure) {
			try {
				recoverInterruptedPresetSave(profileDir);
			} catch (IOException | RuntimeException recoveryFailure) {
				failure.addSuppressed(recoveryFailure);
			}
			if (failure instanceof IOException) throw (IOException) failure;
			throw failure;
		}
	}

	private static final class PresetSaveTransaction {
		@NonNull final File profileDir;
		@NonNull final File rollback;
		@NonNull final File ready;

		PresetSaveTransaction(
				@NonNull File profileDir, @NonNull File rollback, @NonNull File ready) {
			this.profileDir = profileDir;
			this.rollback = rollback;
			this.ready = ready;
		}

		void commit() throws IOException {
			// Marker removal is the commit point. Cleanup after this point is best effort:
			// a stale rollback without .ready is discarded by the next recovery boundary.
			if (!ready.delete()) {
				throw new IOException("Unable to commit preset source publication");
			}
			deleteRecursively(rollback);
		}

		void rollback(@NonNull Throwable failure) {
			try {
				recoverInterruptedPresetSave(profileDir);
			} catch (IOException | RuntimeException recoveryFailure) {
				failure.addSuppressed(recoveryFailure);
			}
		}
	}

	private static boolean isValidConfigFile(File file) {
		if (file == null || !file.isFile() || file.length() <= 0L) return false;
		try (FileReader reader = new FileReader(file)) {
			return gson.fromJson(reader, ProfileModel.class) != null;
		} catch (Exception e) {
			return false;
		}
	}

	private static void deleteRecursively(File file) {
		if (file == null || !file.exists()) return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) deleteRecursively(child);
			}
		}
		if (!file.delete() && file.exists()) {
			Log.w(TAG, "Unable to remove temporary profile operation file " + file);
		}
	}

	/**
	 * Saves a reusable application preset. Application settings are always copied; the separate
	 * keyboard layout is copied only when explicitly requested, and stale destination layouts are removed.
	 */
	static void saveSnapshot(Profile profile, String fromPath, boolean includeKeyboard) throws IOException {
		saveSnapshot(profile.getDir(), new File(fromPath), includeKeyboard);
	}

	/** File-level entry point kept package-private for deterministic preset-save recovery tests. */
	static void saveSnapshot(
			@NonNull File profileDir, @NonNull File sourceDir, boolean includeKeyboard)
			throws IOException {
		synchronized (PRESET_SOURCE_LOCK) {
		recoverInterruptedPresetSave(profileDir);

		File srcConfig = new File(sourceDir, Config.MIDLET_CONFIG_FILE);
		if (!isValidConfigFile(srcConfig)) {
			throw new IOException("Current application configuration is not loadable");
		}
		File srcKeyLayout = new File(sourceDir, Config.MIDLET_KEY_LAYOUT_FILE);
		if (includeKeyboard) {
			String layoutError = KeyboardLayoutValidator.validate(srcKeyLayout);
			if (layoutError != null) {
				throw new IOException("Current keyboard layout is not loadable: " + layoutError);
			}
		}

		boolean transactionCreatedProfile = !profileDir.exists();
		if (profileDir.exists() && !profileDir.isDirectory()) {
			throw new IOException("Preset path is not a directory");
		}
		PresetSaveTransaction transaction =
				beginPresetSave(profileDir, transactionCreatedProfile);
		File config = new File(profileDir, Config.MIDLET_CONFIG_FILE);
		File legacyConfig = new File(profileDir, "config.xml");
		File keyLayout = new File(profileDir, Config.MIDLET_KEY_LAYOUT_FILE);
		try {
			FileUtils.copyFileUsingChannel(srcConfig, config);
			if (legacyConfig.exists() && !legacyConfig.delete()) {
				throw new IOException("Unable to remove stale legacy profile configuration");
			}
			if (includeKeyboard) {
				FileUtils.copyFileUsingChannel(srcKeyLayout, keyLayout);
			} else if (keyLayout.exists() && !keyLayout.delete()) {
				throw new IOException("Unable to remove stale key layout");
			}
			transaction.commit();
		} catch (IOException | RuntimeException failure) {
			transaction.rollback(failure);
			if (failure instanceof IOException) throw (IOException) failure;
			throw failure;
		}
		}
	}

	/**
	 * Commits a preset editor draft. An unreadable layout in an existing draft is left untouched on
	 * the real profile so editing settings cannot accidentally destroy an artifact the editor could
	 * not understand.
	 */
	static void saveEditedSnapshot(Profile profile, String fromPath) throws IOException {
		saveEditedSnapshot(profile.getDir(), new File(fromPath));
	}

	/** File-level entry point kept package-private for deterministic preset-save recovery tests. */
	static void saveEditedSnapshot(@NonNull File profileDir, @NonNull File sourceDir)
			throws IOException {
		synchronized (PRESET_SOURCE_LOCK) {
		recoverInterruptedPresetSave(profileDir);

		File srcConfig = new File(sourceDir, Config.MIDLET_CONFIG_FILE);
		if (!isValidConfigFile(srcConfig)) {
			throw new IOException("Preset draft configuration is not loadable");
		}
		File srcKeyLayout = new File(sourceDir, Config.MIDLET_KEY_LAYOUT_FILE);

		boolean transactionCreatedProfile = !profileDir.exists();
		if (profileDir.exists() && !profileDir.isDirectory()) {
			throw new IOException("Preset path is not a directory");
		}
		PresetSaveTransaction transaction =
				beginPresetSave(profileDir, transactionCreatedProfile);
		File config = new File(profileDir, Config.MIDLET_CONFIG_FILE);
		File legacyConfig = new File(profileDir, "config.xml");
		File keyLayout = new File(profileDir, Config.MIDLET_KEY_LAYOUT_FILE);
		try {
			FileUtils.copyFileUsingChannel(srcConfig, config);
			if (legacyConfig.exists() && !legacyConfig.delete()) {
				throw new IOException("Unable to remove stale legacy profile configuration");
			}
			if (KeyboardLayoutValidator.validate(srcKeyLayout) == null) {
				FileUtils.copyFileUsingChannel(srcKeyLayout, keyLayout);
			} else if (!srcKeyLayout.exists() && keyLayout.exists() && !keyLayout.delete()) {
				throw new IOException("Unable to remove deleted profile keyboard layout");
			}
			transaction.commit();
		} catch (IOException | RuntimeException failure) {
			transaction.rollback(failure);
			if (failure instanceof IOException) throw (IOException) failure;
			throw failure;
		}
		}
	}

	/** Saves only the separate layout artifact, converting an explicitly overwritten entry to layout-only. */
	static void saveLayoutSnapshot(Profile profile, String fromPath) throws IOException {
		saveLayoutSnapshot(profile.getDir(), new File(fromPath));
	}

	/** File-level entry point kept package-private for deterministic preset-save recovery tests. */
	static void saveLayoutSnapshot(@NonNull File profileDir, @NonNull File sourceDir)
			throws IOException {
		synchronized (PRESET_SOURCE_LOCK) {
		recoverInterruptedPresetSave(profileDir);

		File source = new File(sourceDir, Config.MIDLET_KEY_LAYOUT_FILE);
		if (KeyboardLayoutValidator.validate(source) != null) {
			throw new IOException("Current keyboard layout is not loadable");
		}

		boolean transactionCreatedProfile = !profileDir.exists();
		if (profileDir.exists() && !profileDir.isDirectory()) {
			throw new IOException("Preset path is not a directory");
		}
		PresetSaveTransaction transaction =
				beginPresetSave(profileDir, transactionCreatedProfile);
		File config = new File(profileDir, Config.MIDLET_CONFIG_FILE);
		File legacyConfig = new File(profileDir, "config.xml");
		File keyLayout = new File(profileDir, Config.MIDLET_KEY_LAYOUT_FILE);
		try {
			FileUtils.copyFileUsingChannel(source, keyLayout);
			if (config.exists() && !config.delete()) {
				throw new IOException("Unable to remove stale profile configuration");
			}
			if (legacyConfig.exists() && !legacyConfig.delete()) {
				throw new IOException("Unable to remove stale legacy profile configuration");
			}
			transaction.commit();
		} catch (IOException | RuntimeException failure) {
			transaction.rollback(failure);
			if (failure instanceof IOException) throw (IOException) failure;
			throw failure;
		}
		}
	}

	/** Resolves a saved collection entry without requiring it to contain application settings. */
	@Nullable
	static Profile findProfile(@Nullable String name) {
		if (name == null) return null;
		for (Profile profile : getProfiles()) {
			if (name.equals(profile.getName())) return profile;
		}
		return null;
	}

	@Nullable
	public static ProfileModel loadConfig(File dir) {
		return loadConfig(dir, true, BackgroundMigrationContext.MIDLET_CONFIG, false);
	}

	/** Loads a profile and optionally persists legacy-format migrations. */
	@Nullable
	static ProfileModel loadConfig(File dir, boolean persistMigrations) {
		return loadConfig(dir, persistMigrations, BackgroundMigrationContext.MIDLET_CONFIG, false);
	}

	/**
	 * Loads a config with explicit legacy Background Mode context.
	 *
	 * <p>The caller supplies linkage evidence because a named preset must never read the active
	 * MIDlet's preference key. Historical migration is normalized before any JSON/XML write.</p>
	 */
	@Nullable
	public static ProfileModel loadConfig(File dir, boolean persistMigrations,
			@NonNull BackgroundMigrationContext context, boolean legacyThemeLinked) {
		File file = new File(dir, Config.MIDLET_CONFIG_FILE);
		recoverAtomicConfig(file);
		ProfileModel params = null;
		File oldFile = new File(dir, "config.xml");
		boolean loadedLegacyFile = false;
		if (file.exists()) {
			try (FileReader reader = new FileReader(file)) {
				params = gson.fromJson(reader, ProfileModel.class);
				if (params != null) params.dir = dir;
			} catch (Exception e) {
				Log.e(TAG, "loadConfig: ", e);
			}
		}
		if (params == null) {
			if (oldFile.exists()) {
				try (FileInputStream in = new FileInputStream(oldFile)) {
					HashMap<String, Object> map = XmlUtils.readMapXml(in);
					JsonElement json = gson.toJsonTree(map);
					params = gson.fromJson(json, ProfileModel.class);
					if (params != null) params.dir = dir;
					loadedLegacyFile = params != null;
				} catch (Exception e) {
					Log.e(TAG, "loadConfig: ", e);
				}
			}
		}
		if (params == null) {
			return null;
		}
		int originalVersion = params.version;
		switch (params.version) {
			case 0:
				if (params.hwAcceleration) {
					params.graphicsMode = 3;
				}
				updateSystemProperties(params);
			case 1:
				int w = params.screenWidth;
				int h = params.screenHeight;
				if (w > 0) {
					if (h > 0) {
						params.fontAA = Math.min(w, h) >= 240;
					} else {
						params.fontAA = w >= 240;
					}
				} else {
					params.fontAA = (h <= 0) || (h >= 240);
				}
			case 2:
				if (params.screenScaleToFit) {
					if (params.screenKeepAspectRatio) {
						params.screenScaleType = 1;
					} else {
						params.screenScaleType = 2;
					}
				} else {
					params.screenScaleType = 0;
				}
				params.screenGravity = 1;
				break;
		}
		boolean backgroundModeNeedsMigration = originalVersion < ProfileModel.VERSION;
		if (backgroundModeNeedsMigration) {
			params.screenBackgroundMode = context == BackgroundMigrationContext.MIDLET_CONFIG
					&& legacyThemeLinked ? BackgroundMode.THEME : BackgroundMode.CUSTOM;
		} else {
			params.screenBackgroundMode = BackgroundMode.sanitize(params.screenBackgroundMode);
		}
		boolean versionNeedsMigration = originalVersion < ProfileModel.VERSION;
		int normalizedTimingMode = TimingMode.sanitize(params.timingMode);
		boolean timingModeNeedsMigration = params.timingMode != normalizedTimingMode;
		params.timingMode = normalizedTimingMode;
		if (versionNeedsMigration) {
			params.version = ProfileModel.VERSION;
		}
		if (persistMigrations && (versionNeedsMigration || timingModeNeedsMigration
				|| backgroundModeNeedsMigration)) {
			if (saveConfig(params) && loadedLegacyFile && oldFile.delete()) {
				Log.d(TAG, "loadConfig: old config file deleted");
			}
		}
		return params;
	}

	public static boolean saveConfig(ProfileModel p) {
		if (p == null || p.dir == null) {
			return false;
		}
		File file = new File(p.dir, Config.MIDLET_CONFIG_FILE);
		File parent = file.getParentFile();
		File temporary = atomicSibling(file, ATOMIC_NEW_SUFFIX);
		File backup = atomicSibling(file, ATOMIC_BACKUP_SUFFIX);
		try {
			recoverAtomicConfig(file);
			if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
				throw new IOException("Unable to create profile directory: " + parent);
			}
			if (temporary.exists() && !temporary.delete()) {
				throw new IOException("Unable to remove stale profile write: " + temporary);
			}
			if (backup.exists() && !backup.delete()) {
				throw new IOException("Unable to remove stale profile backup: " + backup);
			}
			if (file.exists() && !file.renameTo(backup)) {
				throw new IOException("Unable to stage existing profile configuration");
			}
			try (FileOutputStream output = new FileOutputStream(temporary);
				 Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
				gson.toJson(p, writer);
				writer.flush();
				output.getFD().sync();
			}
			if (!temporary.renameTo(file)) {
				throw new IOException("Unable to publish profile configuration");
			}
			if (backup.exists() && !backup.delete()) {
				// The new config is already committed.  Keep the recoverable backup for the next load
				// rather than reporting a failed save after publishing valid data.
				Log.w(TAG, "Unable to remove profile configuration backup " + backup);
			}
			return true;
		} catch (Exception e) {
			if (temporary.exists() && !temporary.delete()) {
				Log.w(TAG, "saveConfig: unable to remove temporary file " + temporary);
			}
			if (!file.exists() && backup.exists() && !backup.renameTo(file)) {
				Log.e(TAG, "saveConfig: unable to restore previous configuration " + file);
			}
			Log.e(TAG, "saveConfig: ", e);
		}
		return false;
	}

	private static File atomicSibling(@NonNull File file, @NonNull String suffix) {
		return new File(file.getPath() + suffix);
	}

	/** Restores the last committed config after an interrupted temp/backup rename sequence. */
	private static void recoverAtomicConfig(@NonNull File file) {
		File temporary = atomicSibling(file, ATOMIC_NEW_SUFFIX);
		File backup = atomicSibling(file, ATOMIC_BACKUP_SUFFIX);
		if (backup.exists()) {
			if (file.exists()) {
				if (!backup.delete()) Log.w(TAG, "Unable to remove stale profile backup " + backup);
			} else if (!backup.renameTo(file)) {
				Log.w(TAG, "Unable to restore profile backup " + backup);
			}
		}
		if (temporary.exists() && file.exists() && !temporary.delete()) {
			Log.w(TAG, "Unable to remove stale profile write " + temporary);
		}
	}

	public static void updateSystemProperties(ProfileModel params) {
		String defaultProperties = ContextHolder.getAssetAsString("defaults/system.props");
		String properties = params.systemProperties;
		StringBuilder sb = new StringBuilder();
		if (properties == null) {
			params.systemProperties = defaultProperties;
			return;
		}
		sb.append(properties);
		String[] defaults = defaultProperties.split("[\\n\\r]+");
		for (String line : defaults) {
			if (properties.contains(line.substring(0, line.indexOf(':')))) continue;
			sb.append(line).append('\n');
		}
		params.systemProperties = sb.toString();
	}
}
