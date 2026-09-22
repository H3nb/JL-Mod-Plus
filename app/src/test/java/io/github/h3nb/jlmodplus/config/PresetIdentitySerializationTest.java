/*
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import static io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.SharedPreferences;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.lcdui.keyboard.VirtualKeyboard;

public class PresetIdentitySerializationTest {
	@Test
	public void linkedActivationVsRenameCannotPublishStaleOldName() throws Exception {
		File root = tempDir("activation-rename-root");
		File source = preset(root, "K800i", 360, 1);
		File target = tempDir("activation-rename-target");
		writeConfig(target, 176, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.blockCommit(2); // clear barrier #1, final link #2.
		AtomicReference<LinkedPresetActivation.Result> activationResult = new AtomicReference<>();
		AtomicReference<PresetLifecycle.Result> renameResult = new AtomicReference<>();
		AtomicReference<Throwable> activationFailure = new AtomicReference<>();
		AtomicReference<Throwable> renameFailure = new AtomicReference<>();

		Thread activation = thread("activation", activationFailure,
				() -> activationResult.set(LinkedPresetActivation.activate(
						preferences, target, source, "K800i")));
		activation.start();
		preferences.awaitBlockedCommit();

		Thread rename = thread("rename", renameFailure,
				() -> renameResult.set(PresetLifecycle.rename(
						preferences, root, "K800i", "Sony K800i")));
		rename.start();
		awaitBlocked(rename);

		preferences.releaseBlockedCommit();
		join(activation, activationFailure);
		join(rename, renameFailure);

		assertEquals(LinkedPresetActivation.Result.LINKED, activationResult.get());
		assertEquals(PresetLifecycle.Result.SUCCESS, renameResult.get());
		assertLinked(preferences, target, "Sony K800i");
		assertEquals(360, readConfig(target).screenWidth);
	}

	@Test
	public void linkedActivationSafeFailureRestoresBeforeRename() throws Exception {
		File root = tempDir("activation-fail-rename-root");
		preset(root, "K800i", 176, 1);
		File broken = preset(root, "Broken", 360, VirtualKeyboard.TYPE_CUSTOM);
		File target = tempDir("activation-fail-rename-target");
		writeConfig(target, 240, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(target, "K800i", true);
		preferences.blockCommit(2); // clear #1, safe restoration #2.
		AtomicReference<LinkedPresetActivation.Result> activationResult = new AtomicReference<>();
		AtomicReference<PresetLifecycle.Result> renameResult = new AtomicReference<>();
		AtomicReference<Throwable> activationFailure = new AtomicReference<>();
		AtomicReference<Throwable> renameFailure = new AtomicReference<>();

		Thread activation = thread("failed-activation", activationFailure,
				() -> activationResult.set(LinkedPresetActivation.activate(
						preferences, target, broken, "Broken")));
		activation.start();
		preferences.awaitBlockedCommit();

		Thread rename = thread("rename-after-safe-restore", renameFailure,
				() -> renameResult.set(PresetLifecycle.rename(
						preferences, root, "K800i", "Sony K800i")));
		rename.start();
		awaitBlocked(rename);

		preferences.releaseBlockedCommit();
		join(activation, activationFailure);
		join(rename, renameFailure);

		assertEquals(LinkedPresetActivation.Result.FAILED_SAFE, activationResult.get());
		assertEquals(PresetLifecycle.Result.SUCCESS, renameResult.get());
		assertLinked(preferences, target, "Sony K800i");
	}

	@Test
	public void linkedActivationSafeFailureRestoresBeforeDelete() throws Exception {
		File root = tempDir("activation-fail-delete-root");
		preset(root, "K800i", 176, 1);
		File broken = preset(root, "Broken", 360, VirtualKeyboard.TYPE_CUSTOM);
		File target = tempDir("activation-fail-delete-target");
		writeConfig(target, 240, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(target, "K800i", true);
		preferences.blockCommit(2);
		AtomicReference<Throwable> activationFailure = new AtomicReference<>();
		AtomicReference<Throwable> deleteFailure = new AtomicReference<>();

		Thread activation = thread("failed-activation-delete", activationFailure,
				() -> assertEquals(LinkedPresetActivation.Result.FAILED_SAFE,
						LinkedPresetActivation.activate(preferences, target, broken, "Broken")));
		activation.start();
		preferences.awaitBlockedCommit();

		Thread delete = thread("delete-after-safe-restore", deleteFailure,
				() -> assertEquals(PresetLifecycle.Result.SUCCESS,
						PresetLifecycle.delete(preferences, root, "K800i")));
		delete.start();
		awaitBlocked(delete);

		preferences.releaseBlockedCommit();
		join(activation, activationFailure);
		join(delete, deleteFailure);

		assertNull(new PresetLinkage(preferences, target).getOrigin());
		assertFalse(new PresetLinkage(preferences, target).isLinked());
	}

	@Test
	public void followerBoundaryReadsRenamedOriginAfterWinningLifecycle() throws Exception {
		File root = tempDir("follower-rename-root");
		preset(root, "K800i", 480, 1);
		File target = tempDir("follower-rename-target");
		writeConfig(target, 176, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(target, "K800i", true);
		AtomicBoolean prepared = new AtomicBoolean();
		AtomicReference<Throwable> followerFailure = new AtomicReference<>();

		Thread follower;
		synchronized (ProfilesManager.presetSourceLock()) {
			follower = thread("follower-boundary", followerFailure,
					() -> prepared.set(MidletConfigLoadBoundary.prepare(
							preferences, target, root)));
			follower.start();
			awaitBlocked(follower);
			assertEquals(PresetLifecycle.Result.SUCCESS,
					PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i"));
		}
		join(follower, followerFailure);

		assertTrue(prepared.get());
		assertLinked(preferences, target, "Sony K800i");
		assertEquals(480, readConfig(target).screenWidth);
	}

	@Test
	public void followerNeverMaterializesReusedOldNameWhenOriginIsNewName() throws Exception {
		File root = tempDir("follower-reuse-root");
		preset(root, "K800i", 176, 1);
		preset(root, "Sony K800i", 640, 1);
		File target = tempDir("follower-reuse-target");
		writeConfig(target, 111, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(target, "Sony K800i", true);

		assertTrue(MidletConfigLoadBoundary.prepare(preferences, target, root));

		assertEquals(640, readConfig(target).screenWidth);
		assertLinked(preferences, target, "Sony K800i");
	}

	@Test
	public void failedConfigStyleDetachedWriteRestoresBeforeRename() throws Exception {
		assertDetachedFailureThenRename("config-write");
	}

	@Test
	public void failedRuntimeLayoutStyleDetachedWriteRestoresBeforeDelete() throws Exception {
		assertDetachedFailureThenDelete("runtime-layout");
	}

	@Test
	public void failedKeyMapperStyleDetachedWriteRestoresBeforeRename() throws Exception {
		assertDetachedFailureThenRename("keymapper-rename");
	}

	@Test
	public void failedKeyMapperStyleDetachedWriteRestoresBeforeDelete() throws Exception {
		assertDetachedFailureThenDelete("keymapper-delete");
	}

	@Test
	public void sourceReplacementSafeRestoreCompletesBeforeRename() throws Exception {
		File root = tempDir("source-replace-rename-root");
		preset(root, "K800i", 176, 1);
		File target = tempDir("source-replace-rename-target");
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(target, "K800i", true);
		CountDownLatch cleared = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		AtomicReference<Throwable> renameFailure = new AtomicReference<>();

		Thread writer = thread("source-replacement-safe-restore", writerFailure, () -> {
			synchronized (ProfilesManager.presetSourceLock()) {
				PresetSourceReplacement.Guard guard =
						PresetSourceReplacement.begin(preferences, target);
				assertTrue(guard.canWrite());
				cleared.countDown();
				await(release);
				assertTrue(guard.restoreIfUnchanged());
			}
		});
		writer.start();
		assertTrue(cleared.await(5, TimeUnit.SECONDS));

		Thread rename = thread("rename-during-source-replacement", renameFailure,
				() -> assertEquals(PresetLifecycle.Result.SUCCESS,
						PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i")));
		rename.start();
		awaitBlocked(rename);
		release.countDown();
		join(writer, writerFailure);
		join(rename, renameFailure);

		assertLinked(preferences, target, "Sony K800i");
	}

	@Test
	public void sourceReplacementSafeRestoreCompletesBeforeDelete() throws Exception {
		File root = tempDir("source-replace-delete-root");
		preset(root, "K800i", 176, 1);
		File target = tempDir("source-replace-delete-target");
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(target, "K800i", true);
		CountDownLatch cleared = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		AtomicReference<Throwable> deleteFailure = new AtomicReference<>();

		Thread writer = thread("source-replacement-delete-restore", writerFailure, () -> {
			synchronized (ProfilesManager.presetSourceLock()) {
				PresetSourceReplacement.Guard guard =
						PresetSourceReplacement.begin(preferences, target);
				assertTrue(guard.canWrite());
				cleared.countDown();
				await(release);
				assertTrue(guard.restoreIfUnchanged());
			}
		});
		writer.start();
		assertTrue(cleared.await(5, TimeUnit.SECONDS));

		Thread delete = thread("delete-during-source-replacement", deleteFailure,
				() -> assertEquals(PresetLifecycle.Result.SUCCESS,
						PresetLifecycle.delete(preferences, root, "K800i")));
		delete.start();
		awaitBlocked(delete);
		release.countDown();
		join(writer, writerFailure);
		join(delete, deleteFailure);

		assertNull(new PresetLinkage(preferences, target).getOrigin());
		assertFalse(new PresetLinkage(preferences, target).isLinked());
	}

	@Test
	public void staleUpdateAfterRenameCleanupFailureCannotOverwriteOldSource() throws Exception {
		File root = tempDir("stale-update-rename-root");
		File oldSource = preset(root, "K800i", 176, 1);
		preset(root, "Sony K800i", 360, 1);
		File current = tempDir("stale-update-rename-current");
		writeConfig(current, 640, 1);
		byte[] oldBytes = Files.readAllBytes(configFile(oldSource).toPath());
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "Sony K800i", true);

		assertEquals(PresetSourceSave.Result.FAILED,
				PresetSourceSave.updateExisting(preferences, current, root, "K800i"));

		assertArrayEquals(oldBytes, Files.readAllBytes(configFile(oldSource).toPath()));
		assertLinked(preferences, current, "Sony K800i");
	}

	@Test
	public void staleUpdateAfterOldNameReuseCannotOverwriteUnrelatedSource() throws Exception {
		File root = tempDir("stale-update-reuse-root");
		File reused = preset(root, "K800i", 999, 1);
		preset(root, "Sony K800i", 360, 1);
		File current = tempDir("stale-update-reuse-current");
		writeConfig(current, 640, 1);
		byte[] reusedBytes = Files.readAllBytes(configFile(reused).toPath());
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "Sony K800i", false);

		assertEquals(PresetSourceSave.Result.FAILED,
				PresetSourceSave.updateExisting(preferences, current, root, "K800i"));

		assertArrayEquals(reusedBytes, Files.readAllBytes(configFile(reused).toPath()));
		assertEquals("Sony K800i", new PresetLinkage(preferences, current).getOrigin());
		assertFalse(new PresetLinkage(preferences, current).isLinked());
	}

	@Test
	public void provenanceOnlyExplicitUpdateStillPromotesToLinked() throws Exception {
		File root = tempDir("provenance-update-root");
		File source = preset(root, "K800i", 176, 1);
		File current = tempDir("provenance-update-current");
		writeConfig(current, 640, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.updateExisting(preferences, current, root, "K800i"));

		assertEquals(640, readConfig(source).screenWidth);
		assertLinked(preferences, current, "K800i");
	}

	@Test
	public void renameWaitsThenRejectsCaseInsensitiveSaveAsRace() throws Exception {
		File root = tempDir("rename-case-race-root");
		File oldSource = preset(root, "K800i", 176, 1);
		File local = tempDir("rename-case-race-local");
		writeConfig(local, 640, 1);
		FakePreferences preferences = new FakePreferences();
		AtomicReference<PresetLifecycle.Result> renameResult = new AtomicReference<>();
		AtomicReference<Throwable> renameFailure = new AtomicReference<>();

		Thread rename;
		synchronized (ProfilesManager.presetSourceLock()) {
			rename = thread("rename-case-race", renameFailure,
					() -> renameResult.set(PresetLifecycle.rename(
							preferences, root, "K800i", "Sony K800i")));
			rename.start();
			awaitBlocked(rename);

			ProfilesManager.saveNewCompleteSnapshot(root, "sony k800i", local);
		}
		join(rename, renameFailure);

		assertEquals(PresetLifecycle.Result.FAILED, renameResult.get());
		assertTrue(oldSource.isDirectory());
		assertEquals(640, readConfig(new File(root, "sony k800i")).screenWidth);
		assertFalse(new File(root, "Sony K800i").exists());
	}

	@Test
	public void renameRejectsCaseInsensitiveCollisionInsideIdentityLock() throws Exception {
		File root = tempDir("rename-case-root");
		File oldSource = preset(root, "K800i", 176, 1);
		File occupied = preset(root, "sony k800i", 360, 1);
		byte[] occupiedBytes = Files.readAllBytes(configFile(occupied).toPath());

		assertEquals(PresetLifecycle.Result.FAILED,
				PresetLifecycle.rename(new FakePreferences(), root, "K800i", "Sony K800i"));

		assertTrue(oldSource.isDirectory());
		assertArrayEquals(occupiedBytes, Files.readAllBytes(configFile(occupied).toPath()));
		assertFalse(new File(root, "Sony K800i").exists());
	}

	@Test
	public void freshDefaultInitializationCompletesBeforeRenameThenRenameRewritesIdentity()
			throws Exception {
		File root = tempDir("default-rename-root");
		preset(root, "K800i", 480, 1);
		File target = tempDir("default-rename-target");
		FakePreferences preferences = new FakePreferences();
		preferences.seedString(PREF_DEFAULT_PROFILE, "K800i");
		preferences.blockCommit(2); // activation clear #1, final link #2.
		AtomicReference<LinkedPresetActivation.Result> initResult = new AtomicReference<>();
		AtomicReference<Throwable> initFailure = new AtomicReference<>();
		AtomicReference<Throwable> renameFailure = new AtomicReference<>();

		Thread initializer = thread("fresh-default-init", initFailure,
				() -> initResult.set(initializeCurrentDefault(preferences, target, root)));
		initializer.start();
		preferences.awaitBlockedCommit();

		Thread rename = thread("rename-default", renameFailure,
				() -> assertEquals(PresetLifecycle.Result.SUCCESS,
						PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i")));
		rename.start();
		awaitBlocked(rename);
		preferences.releaseBlockedCommit();
		join(initializer, initFailure);
		join(rename, renameFailure);

		assertEquals(LinkedPresetActivation.Result.LINKED, initResult.get());
		assertEquals("Sony K800i", preferences.getString(PREF_DEFAULT_PROFILE, null));
		assertLinked(preferences, target, "Sony K800i");
	}

	@Test
	public void freshDefaultInitializationSeesDeleteThatWinsIdentityLock() throws Exception {
		File root = tempDir("default-delete-root");
		preset(root, "K800i", 480, 1);
		File target = tempDir("default-delete-target");
		FakePreferences preferences = new FakePreferences();
		preferences.seedString(PREF_DEFAULT_PROFILE, "K800i");
		AtomicReference<LinkedPresetActivation.Result> initResult = new AtomicReference<>();
		AtomicReference<Throwable> initFailure = new AtomicReference<>();

		Thread initializer;
		synchronized (ProfilesManager.presetSourceLock()) {
			initializer = thread("fresh-default-after-delete", initFailure,
					() -> initResult.set(initializeCurrentDefault(preferences, target, root)));
			initializer.start();
			awaitBlocked(initializer);
			assertEquals(PresetLifecycle.Result.SUCCESS,
					PresetLifecycle.delete(preferences, root, "K800i"));
		}
		join(initializer, initFailure);

		assertNull(initResult.get());
		assertNull(preferences.getString(PREF_DEFAULT_PROFILE, null));
		assertNull(new PresetLinkage(preferences, target).getOrigin());
		assertFalse(configFile(target).exists());
	}

	@Test
	public void setDefaultCompletesBeforeRenameAndRenameRewritesPreference() throws Exception {
		File root = tempDir("set-default-rename-root");
		preset(root, "K800i", 360, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.blockCommit(1);
		AtomicBoolean setResult = new AtomicBoolean();
		AtomicReference<Throwable> setFailure = new AtomicReference<>();
		AtomicReference<Throwable> renameFailure = new AtomicReference<>();

		Thread setter = thread("set-default", setFailure,
				() -> setResult.set(ProfilesActivity.setNamedDefault(
						preferences, root, "K800i")));
		setter.start();
		preferences.awaitBlockedCommit();

		Thread rename = thread("rename-set-default", renameFailure,
				() -> assertEquals(PresetLifecycle.Result.SUCCESS,
						PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i")));
		rename.start();
		awaitBlocked(rename);
		preferences.releaseBlockedCommit();
		join(setter, setFailure);
		join(rename, renameFailure);

		assertTrue(setResult.get());
		assertEquals("Sony K800i", preferences.getString(PREF_DEFAULT_PROFILE, null));
	}

	@Test
	public void setDefaultCannotPublishDeletedIdentityWhenDeleteWinsLock() throws Exception {
		File root = tempDir("set-default-delete-root");
		preset(root, "K800i", 360, 1);
		FakePreferences preferences = new FakePreferences();
		AtomicBoolean setResult = new AtomicBoolean(true);
		AtomicReference<Throwable> setFailure = new AtomicReference<>();

		Thread setter;
		synchronized (ProfilesManager.presetSourceLock()) {
			setter = thread("set-default-after-delete", setFailure,
					() -> setResult.set(ProfilesActivity.setNamedDefault(
							preferences, root, "K800i")));
			setter.start();
			awaitBlocked(setter);
			assertEquals(PresetLifecycle.Result.SUCCESS,
					PresetLifecycle.delete(preferences, root, "K800i"));
		}
		join(setter, setFailure);

		assertFalse(setResult.get());
		assertNull(preferences.getString(PREF_DEFAULT_PROFILE, null));
	}

	@Test
	public void builtInDefaultClearUsesIdentityLockAndSynchronousCommit() throws Exception {
		FakePreferences preferences = new FakePreferences();
		preferences.seedString(PREF_DEFAULT_PROFILE, "K800i");
		AtomicBoolean result = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread clear;
		synchronized (ProfilesManager.presetSourceLock()) {
			clear = thread("clear-default", failure,
					() -> result.set(ProfilesActivity.setBuiltInDefault(preferences)));
			clear.start();
			awaitBlocked(clear);
			assertEquals("K800i", preferences.getString(PREF_DEFAULT_PROFILE, null));
		}
		join(clear, failure);

		assertTrue(result.get());
		assertNull(preferences.getString(PREF_DEFAULT_PROFILE, null));
	}

	@Test
	public void editorDraftModeClassifiesAfterInterruptedNewProfileRecovery() throws Exception {
		File root = tempDir("editor-recovery-mode-root");
		File source = new File(root, "N95");
		assertTrue(source.mkdir());
		File rollback = new File(source, ProfilesManager.PRESET_SAVE_ROLLBACK_DIR);
		assertTrue(rollback.mkdir());
		assertTrue(new File(
				rollback, ProfilesManager.PRESET_SAVE_NEW_PROFILE_MARKER).createNewFile());
		assertTrue(new File(
				rollback, ProfilesManager.PRESET_SAVE_READY_MARKER).createNewFile());
		File draft = tempDir("editor-recovery-mode-draft");

		assertEquals(ProfilesManager.ProfileEditMode.CREATE_NEW,
				ProfilesManager.preparePresetEditDraft(source, draft));

		assertFalse(source.exists());
		assertFalse(configFile(draft).exists());
	}

	@Test
	public void editorEditExistingAfterRenameDoesNotRecreateOldName() throws Exception {
		File root = tempDir("editor-edit-rename-root");
		File source = preset(root, "K800i", 176, 1);
		File draft = tempDir("editor-edit-rename-draft");
		assertEquals(ProfilesManager.ProfileEditMode.EDIT_EXISTING,
				ProfilesManager.preparePresetEditDraft(source, draft));
		writeConfig(draft, 640, 1);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.rename(new FakePreferences(), root, "K800i", "Sony K800i"));
		expectIOException(() -> ProfilesManager.saveEditedSnapshot(
				source, draft, ProfilesManager.ProfileEditMode.EDIT_EXISTING));

		assertFalse(source.exists());
		assertEquals(176, readConfig(new File(root, "Sony K800i")).screenWidth);
	}

	@Test
	public void editorEditExistingAfterDeleteDoesNotRecreateOldName() throws Exception {
		File root = tempDir("editor-edit-delete-root");
		File source = preset(root, "K800i", 176, 1);
		File draft = tempDir("editor-edit-delete-draft");
		assertEquals(ProfilesManager.ProfileEditMode.EDIT_EXISTING,
				ProfilesManager.preparePresetEditDraft(source, draft));
		writeConfig(draft, 640, 1);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.delete(new FakePreferences(), root, "K800i"));
		expectIOException(() -> ProfilesManager.saveEditedSnapshot(
				source, draft, ProfilesManager.ProfileEditMode.EDIT_EXISTING));

		assertFalse(source.exists());
	}

	@Test
	public void editorCreateNewAfterConcurrentSameNameCreationDoesNotOverwrite() throws Exception {
		File root = tempDir("editor-create-race-root");
		File target = new File(root, "N95");
		File draft = tempDir("editor-create-race-draft");
		assertEquals(ProfilesManager.ProfileEditMode.CREATE_NEW,
				ProfilesManager.preparePresetEditDraft(target, draft));
		writeConfig(draft, 640, 1);
		assertTrue(target.mkdir());
		writeConfig(target, 176, 1);
		byte[] existing = Files.readAllBytes(configFile(target).toPath());

		expectIOException(() -> ProfilesManager.saveEditedSnapshot(
				target, draft, ProfilesManager.ProfileEditMode.CREATE_NEW));

		assertArrayEquals(existing, Files.readAllBytes(configFile(target).toPath()));
	}

	@Test
	public void editorCreateNewRejectsCaseInsensitiveCollision() throws Exception {
		File root = tempDir("editor-create-case-root");
		File target = new File(root, "N95");
		File draft = tempDir("editor-create-case-draft");
		assertEquals(ProfilesManager.ProfileEditMode.CREATE_NEW,
				ProfilesManager.preparePresetEditDraft(target, draft));
		writeConfig(draft, 640, 1);
		File occupied = preset(root, "n95", 176, 1);
		byte[] existing = Files.readAllBytes(configFile(occupied).toPath());

		expectIOException(() -> ProfilesManager.saveEditedSnapshot(
				target, draft, ProfilesManager.ProfileEditMode.CREATE_NEW));

		assertArrayEquals(existing, Files.readAllBytes(configFile(occupied).toPath()));
		assertFalse(target.exists());
	}

	@Test
	public void activityRecreationModeParserPreservesCreateAndEditIntent() {
		assertEquals(ProfilesManager.ProfileEditMode.CREATE_NEW,
				ConfigActivity.parseProfileEditMode(
						ProfilesManager.ProfileEditMode.CREATE_NEW.name()));
		assertEquals(ProfilesManager.ProfileEditMode.EDIT_EXISTING,
				ConfigActivity.parseProfileEditMode(
						ProfilesManager.ProfileEditMode.EDIT_EXISTING.name()));
		assertNull(ConfigActivity.parseProfileEditMode("UNKNOWN"));
		assertNull(ConfigActivity.parseProfileEditMode(null));
	}

	private static void assertDetachedFailureThenRename(String suffix) throws Exception {
		File root = tempDir(suffix + "-root");
		preset(root, "K800i", 176, 1);
		File target = tempDir(suffix + "-target");
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(target, "K800i", true);
		CountDownLatch writeEntered = new CountDownLatch(1);
		CountDownLatch releaseWrite = new CountDownLatch(1);
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		AtomicReference<Throwable> renameFailure = new AtomicReference<>();

		Thread writer = thread(suffix + "-writer", writerFailure, () ->
				assertFalse(PresetLocalOverride.runDetachedWrite(
						preferences,
						target,
						() -> {
							writeEntered.countDown();
							await(releaseWrite);
							return false;
						})));
		writer.start();
		assertTrue(writeEntered.await(5, TimeUnit.SECONDS));

		Thread rename = thread(suffix + "-rename", renameFailure,
				() -> assertEquals(PresetLifecycle.Result.SUCCESS,
						PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i")));
		rename.start();
		awaitBlocked(rename);
		releaseWrite.countDown();
		join(writer, writerFailure);
		join(rename, renameFailure);

		assertLinked(preferences, target, "Sony K800i");
	}

	private static void assertDetachedFailureThenDelete(String suffix) throws Exception {
		File root = tempDir(suffix + "-root");
		preset(root, "K800i", 176, 1);
		File target = tempDir(suffix + "-target");
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(target, "K800i", true);
		CountDownLatch writeEntered = new CountDownLatch(1);
		CountDownLatch releaseWrite = new CountDownLatch(1);
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		AtomicReference<Throwable> deleteFailure = new AtomicReference<>();

		Thread writer = thread(suffix + "-writer", writerFailure, () ->
				assertFalse(PresetLocalOverride.runDetachedWrite(
						preferences,
						target,
						() -> {
							writeEntered.countDown();
							await(releaseWrite);
							return false;
						})));
		writer.start();
		assertTrue(writeEntered.await(5, TimeUnit.SECONDS));

		Thread delete = thread(suffix + "-delete", deleteFailure,
				() -> assertEquals(PresetLifecycle.Result.SUCCESS,
						PresetLifecycle.delete(preferences, root, "K800i")));
		delete.start();
		awaitBlocked(delete);
		releaseWrite.countDown();
		join(writer, writerFailure);
		join(delete, deleteFailure);

		assertNull(new PresetLinkage(preferences, target).getOrigin());
		assertFalse(new PresetLinkage(preferences, target).isLinked());
	}

	private static LinkedPresetActivation.Result initializeCurrentDefault(
			FakePreferences preferences, File target, File root) {
		synchronized (ProfilesManager.presetSourceLock()) {
			String name = preferences.getString(PREF_DEFAULT_PROFILE, null);
			if (name == null) return null;
			File source = new File(root, name);
			if (!source.isDirectory()) return null;
			ProfilesManager.ProfileInfo info =
					ProfilesManager.inspectProfile(new Profile(name), source);
			if (!ConfigActivity.hasApplicationSettingsArtifact(info)) return null;
			return LinkedPresetActivation.activate(preferences, target, source, name);
		}
	}

	private static File preset(File root, String name, int width, int vkType) throws Exception {
		File dir = new File(root, name);
		assertTrue(dir.mkdir());
		writeConfig(dir, width, vkType);
		return dir;
	}

	private static void writeConfig(File dir, int width, int vkType) throws Exception {
		if (!dir.isDirectory()) assertTrue(dir.mkdirs());
		ProfileModel model = new ProfileModel();
		model.version = ProfileModel.VERSION;
		model.screenWidth = width;
		model.screenHeight = 320;
		model.vkType = vkType;
		model.systemProperties = "";
		Files.write(configFile(dir).toPath(),
				new Gson().toJson(model).getBytes(StandardCharsets.UTF_8));
	}

	private static ProfileModel readConfig(File dir) throws Exception {
		return new Gson().fromJson(
				new String(Files.readAllBytes(configFile(dir).toPath()), StandardCharsets.UTF_8),
				ProfileModel.class);
	}

	private static File configFile(File dir) {
		return new File(dir, Config.MIDLET_CONFIG_FILE);
	}

	private static File tempDir(String suffix) throws Exception {
		File dir = Files.createTempDirectory("jlmod-preset-identity-" + suffix).toFile();
		dir.deleteOnExit();
		return dir;
	}

	private static void assertLinked(FakePreferences preferences, File dir, String name) {
		PresetLinkage linkage = new PresetLinkage(preferences, dir);
		assertEquals(name, linkage.getOrigin());
		assertTrue(linkage.isLinked());
	}

	private static Thread thread(
			String name, AtomicReference<Throwable> failure, ThrowingRunnable action) {
		return new Thread(() -> {
			try {
				action.run();
			} catch (Throwable throwable) {
				failure.set(throwable);
			}
		}, name);
	}

	private static void awaitBlocked(Thread thread) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
			if (!thread.isAlive()) break;
			Thread.yield();
		}
		assertEquals("Expected thread to block on preset identity monitor",
				Thread.State.BLOCKED, thread.getState());
	}

	private static void join(Thread thread, AtomicReference<Throwable> failure) throws Exception {
		thread.join(TimeUnit.SECONDS.toMillis(5));
		assertFalse("Thread did not finish", thread.isAlive());
		if (failure.get() != null) throw new AssertionError(failure.get());
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("Timed out waiting for test latch");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static void expectIOException(ThrowingRunnable action) throws Exception {
		try {
			action.run();
			fail("Expected IOException");
		} catch (IOException expected) {
			// Expected.
		}
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static final class FakePreferences implements SharedPreferences {
		private final Map<String, Object> values = new ConcurrentHashMap<>();
		private int commitCount;
		private int blockedCommitNumber = -1;
		private CountDownLatch commitEntered;
		private CountDownLatch commitRelease;

		void seedOrigin(File dir, String origin, boolean linked) {
			values.put(PresetLinkage.originPreferenceKey(dir), origin);
			if (linked) values.put(PresetLinkage.linkedPreferenceKey(dir), true);
			else values.remove(PresetLinkage.linkedPreferenceKey(dir));
		}

		void seedString(String key, String value) {
			values.put(key, value);
		}

		void blockCommit(int commitNumber) {
			blockedCommitNumber = commitNumber;
			commitEntered = new CountDownLatch(1);
			commitRelease = new CountDownLatch(1);
		}

		void awaitBlockedCommit() throws Exception {
			assertTrue("Expected preference commit to reach blocking point",
					commitEntered != null && commitEntered.await(5, TimeUnit.SECONDS));
		}

		void releaseBlockedCommit() {
			if (commitRelease != null) commitRelease.countDown();
		}

		@Override
		public Map<String, ?> getAll() {
			return Collections.unmodifiableMap(new HashMap<>(values));
		}

		@Override
		public String getString(String key, String defValue) {
			Object value = values.get(key);
			return value instanceof String ? (String) value : defValue;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Set<String> getStringSet(String key, Set<String> defValues) {
			Object value = values.get(key);
			return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
		}

		@Override
		public int getInt(String key, int defValue) {
			Object value = values.get(key);
			return value instanceof Integer ? (Integer) value : defValue;
		}

		@Override
		public long getLong(String key, long defValue) {
			Object value = values.get(key);
			return value instanceof Long ? (Long) value : defValue;
		}

		@Override
		public float getFloat(String key, float defValue) {
			Object value = values.get(key);
			return value instanceof Float ? (Float) value : defValue;
		}

		@Override
		public boolean getBoolean(String key, boolean defValue) {
			Object value = values.get(key);
			return value instanceof Boolean ? (Boolean) value : defValue;
		}

		@Override
		public boolean contains(String key) {
			return values.containsKey(key);
		}

		@Override
		public Editor edit() {
			return new FakeEditor();
		}

		@Override
		public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
		}

		@Override
		public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
		}

		private final class FakeEditor implements Editor {
			private final Map<String, Object> updates = new HashMap<>();
			private final Set<String> removals = new HashSet<>();
			private boolean clear;

			@Override
			public Editor putString(String key, String value) {
				updates.put(key, value);
				removals.remove(key);
				return this;
			}

			@Override
			public Editor putStringSet(String key, Set<String> value) {
				updates.put(key, value == null ? null : new HashSet<>(value));
				removals.remove(key);
				return this;
			}

			@Override
			public Editor putInt(String key, int value) {
				updates.put(key, value);
				removals.remove(key);
				return this;
			}

			@Override
			public Editor putLong(String key, long value) {
				updates.put(key, value);
				removals.remove(key);
				return this;
			}

			@Override
			public Editor putFloat(String key, float value) {
				updates.put(key, value);
				removals.remove(key);
				return this;
			}

			@Override
			public Editor putBoolean(String key, boolean value) {
				updates.put(key, value);
				removals.remove(key);
				return this;
			}

			@Override
			public Editor remove(String key) {
				removals.add(key);
				updates.remove(key);
				return this;
			}

			@Override
			public Editor clear() {
				clear = true;
				return this;
			}

			@Override
			public boolean commit() {
				applyChanges();
				commitCount++;
				if (commitCount == blockedCommitNumber) {
					if (commitEntered != null) commitEntered.countDown();
					await(commitRelease);
				}
				return true;
			}

			@Override
			public void apply() {
				applyChanges();
			}

			private void applyChanges() {
				if (clear) values.clear();
				for (String key : removals) values.remove(key);
				for (Map.Entry<String, Object> entry : updates.entrySet()) {
					if (entry.getValue() == null) values.remove(entry.getKey());
					else values.put(entry.getKey(), entry.getValue());
				}
			}
		}
	}
}
