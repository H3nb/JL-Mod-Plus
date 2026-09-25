/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import com.google.gson.Gson;

import org.junit.Test;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.microedition.lcdui.keyboard.VirtualKeyboard;

public class PresetSourceSaveTest {
	private static final int LAYOUT_SIGNATURE = 0x564B4C00;
	private static final int LAYOUT_TYPE = 3;
	private static final int LAYOUT_EOF = -1;

	@Test
	public void saveAsCombinedCreatesExactSourceAndLinksCurrentMidlet() throws Exception {
		File root = tempDir("save-combined-root");
		File current = tempDir("save-combined-current");
		writeConfig(current, 360, 1);
		writeLayout(current, 3);
		FakePreferences preferences = new FakePreferences();

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.saveAsNew(preferences, current, root, "K800i"));

		File source = new File(root, "K800i");
		assertEquals(360, readConfig(source).screenWidth);
		assertArrayEquals(readLayout(current), readLayout(source));
		assertLinked(preferences, current, "K800i");
	}

	@Test
	public void saveAsAndUpdateStayInsidePassedWorkdirCollection() throws Exception {
		File rootA = tempDir("bound-source-a");
		File rootB = tempDir("bound-source-b");
		File foreign = preset(rootB, "K800i", 640, 1, null);
		File current = tempDir("bound-source-current");
		writeConfig(current, 360, 1);
		FakePreferences preferences = new FakePreferences();

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.saveAsNew(preferences, current, rootA, "K800i"));
		assertEquals(360, readConfig(new File(rootA, "K800i")).screenWidth);
		assertEquals(640, readConfig(foreign).screenWidth);
		writeConfig(current, 480, 1);
		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.updateExisting(preferences, current, rootA, "K800i"));
		assertEquals(480, readConfig(new File(rootA, "K800i")).screenWidth);
		assertEquals(640, readConfig(foreign).screenWidth);
	}

	@Test
	public void saveAsConfigOnlyCreatesNoLayoutAndLinksCurrentMidlet() throws Exception {
		File root = tempDir("save-config-root");
		File current = tempDir("save-config-current");
		writeConfig(current, 240, 1);
		FakePreferences preferences = new FakePreferences();

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.saveAsNew(preferences, current, root, "N95"));

		File source = new File(root, "N95");
		assertEquals(240, readConfig(source).screenWidth);
		assertFalse(layoutFile(source).exists());
		assertLinked(preferences, current, "N95");
	}

	@Test
	public void incompleteCustomSaveFailsWithoutChangingOwnership() throws Exception {
		File root = tempDir("invalid-root");
		File current = tempDir("invalid-current");
		writeConfig(current, 360, VirtualKeyboard.TYPE_CUSTOM);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);

		assertEquals(PresetSourceSave.Result.FAILED,
				PresetSourceSave.saveAsNew(preferences, current, root, "Broken"));

		assertFalse(new File(root, "Broken").exists());
		assertEquals("K800i", new PresetLinkage(preferences, current).getOrigin());
		assertFalse(new PresetLinkage(preferences, current).isLinked());
	}

	@Test
	public void updateCombinedExactlyReplacesSourceAndRelinksCurrentMidlet() throws Exception {
		File root = tempDir("update-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("update-current");
		writeConfig(current, 640, 1);
		writeLayout(current, 5);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.updateExisting(preferences, current, root, "K800i"));

		assertEquals(640, readConfig(source).screenWidth);
		assertArrayEquals(readLayout(current), readLayout(source));
		assertLinked(preferences, current, "K800i");
	}

	@Test
	public void updateConfigOnlyRemovesStaleSourceLayout() throws Exception {
		File root = tempDir("update-no-layout-root");
		File source = preset(root, "K800i", 176, 1, 2);
		File current = tempDir("update-no-layout-current");
		writeConfig(current, 480, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.updateExisting(preferences, current, root, "K800i"));

		assertEquals(480, readConfig(source).screenWidth);
		assertFalse(layoutFile(source).exists());
		assertLinked(preferences, current, "K800i");
	}

	@Test
	public void interruptedSourceRecoveryThenInvalidLocalFailsWithoutFalseLink() throws Exception {
		File root = tempDir("recovery-failure-root");
		File source = preset(root, "K800i", 176, 1, 1);
		byte[] oldConfig = readConfigBytes(source);
		byte[] oldLayout = readLayout(source);
		createLiveRollback(source, oldConfig, oldLayout);
		writeConfig(source, 999, 1);
		writeLayout(source, 6);
		File current = tempDir("recovery-failure-current");
		writeConfig(current, 360, VirtualKeyboard.TYPE_CUSTOM);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);

		assertEquals(PresetSourceSave.Result.FAILED,
				PresetSourceSave.updateExisting(preferences, current, root, "K800i"));

		assertArrayEquals(oldConfig, readConfigBytes(source));
		assertArrayEquals(oldLayout, readLayout(source));
		assertFalse(new File(source, ProfilesManager.PRESET_SAVE_ROLLBACK_DIR).exists());
		assertEquals("K800i", new PresetLinkage(preferences, current).getOrigin());
		assertFalse(new PresetLinkage(preferences, current).isLinked());
	}

	@Test
	public void saveAsCommittedSourceSurvivesOwnershipClearFailure() throws Exception {
		File root = tempDir("clear-fail-root");
		File current = tempDir("clear-fail-current");
		writeConfig(current, 360, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);
		preferences.failCommit(1);

		assertEquals(PresetSourceSave.Result.SAVED_UNLINKED,
				PresetSourceSave.saveAsNew(preferences, current, root, "N95"));

		assertEquals(360, readConfig(new File(root, "N95")).screenWidth);
		PresetLinkage linkage = new PresetLinkage(preferences, current);
		assertFalse(linkage.isLinked());
		assertFalse("N95".equals(linkage.getOrigin()) && linkage.isLinked());
	}

	@Test
	public void updateCommittedSourceSurvivesLinkFailureAndLeavesCurrentCustom() throws Exception {
		File root = tempDir("link-fail-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("link-fail-current");
		writeConfig(current, 640, 1);
		writeLayout(current, 5);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);
		preferences.failCommit(2); // ownership clear #1 succeeds, linkTo #2 fails.

		assertEquals(PresetSourceSave.Result.SAVED_UNLINKED,
				PresetSourceSave.updateExisting(preferences, current, root, "K800i"));

		assertEquals(640, readConfig(source).screenWidth);
		assertArrayEquals(readLayout(current), readLayout(source));
		PresetLinkage linkage = new PresetLinkage(preferences, current);
		assertEquals("K800i", linkage.getOrigin());
		assertFalse(linkage.isLinked());
	}

	@Test
	public void successfulSaveAsClearsBuiltInOwnershipAndCreatesNamedLink() throws Exception {
		File root = tempDir("builtin-root");
		File current = tempDir("builtin-current");
		writeConfig(current, 360, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedBoolean(ProfileModel.builtInThemePreferenceKey(current), true);

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.saveAsNew(preferences, current, root, "K800i"));

		assertFalse(preferences.getBoolean(ProfileModel.builtInThemePreferenceKey(current), false));
		assertLinked(preferences, current, "K800i");
	}

	@Test
	public void committedSourceBeforeMetadataSwitchLeavesPreviousOwnershipSafe() throws Exception {
		File root = tempDir("pre-metadata-root");
		File current = tempDir("pre-metadata-current");
		writeConfig(current, 360, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);

		ProfilesManager.saveNewCompleteSnapshot(root, "N95", current);

		assertEquals(360, readConfig(new File(root, "N95")).screenWidth);
		assertLinked(preferences, current, "K800i");
	}

	@Test
	public void ownershipClearBeforeLinkLeavesCommittedSourceAndCurrentCustom() throws Exception {
		File root = tempDir("cleared-root");
		File current = tempDir("cleared-current");
		writeConfig(current, 360, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);
		ProfilesManager.saveNewCompleteSnapshot(root, "N95", current);

		PresetSourceReplacement.Guard guard = PresetSourceReplacement.begin(preferences, current);

		assertTrue(guard.canWrite());
		assertEquals(360, readConfig(new File(root, "N95")).screenWidth);
		assertNull(new PresetLinkage(preferences, current).getOrigin());
		assertFalse(new PresetLinkage(preferences, current).isLinked());
	}

	@Test
	public void updateLeavesOtherFollowerMetadataUnchanged() throws Exception {
		File root = tempDir("follower-metadata-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("follower-current");
		File follower = tempDir("other-follower");
		writeConfig(current, 640, 1);
		writeLayout(current, 5);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);
		preferences.seedOrigin(follower, "K800i", true);

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.updateExisting(preferences, current, root, "K800i"));

		assertEquals(640, readConfig(source).screenWidth);
		assertLinked(preferences, follower, "K800i");
	}

	@Test
	public void existingFollowerNextSyncReceivesUpdatedCompleteSnapshot() throws Exception {
		File root = tempDir("follower-sync-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("sync-current");
		File follower = tempDir("sync-follower");
		writeConfig(current, 640, 1);
		writeLayout(current, 5);
		writeConfig(follower, 176, 1);
		writeLayout(follower, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);
		preferences.seedOrigin(follower, "K800i", true);

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.updateExisting(preferences, current, root, "K800i"));
		ProfilesManager.syncSnapshot(source, follower);

		assertEquals(640, readConfig(follower).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(follower));
		assertLinked(preferences, follower, "K800i");
	}

	@Test
	public void explicitUpdatePromotesLegacyProvenanceOnlyToLinked() throws Exception {
		File root = tempDir("legacy-origin-root");
		preset(root, "K800i", 176, 1, null);
		File current = tempDir("legacy-origin-current");
		writeConfig(current, 360, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetSourceSave.updateExisting(preferences, current, root, "K800i"));

		assertLinked(preferences, current, "K800i");
	}


	@Test
	public void runtimeLinkedOriginResolvesExistingTarget() throws Exception {
		File root = tempDir("runtime-linked-target-root");
		preset(root, "K800i", 176, 1, 1);
		File current = tempDir("runtime-linked-target-current");
		writeConfig(current, 176, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);

		assertEquals("K800i",
				PresetRuntimeUpdate.resolveUpdateTarget(preferences, current, root));
	}

	@Test
	public void runtimeCustomProvenanceAlsoResolvesExistingTarget() throws Exception {
		File root = tempDir("runtime-custom-target-root");
		preset(root, "K800i", 176, 1, 1);
		File current = tempDir("runtime-custom-target-current");
		writeConfig(current, 176, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);

		assertEquals("K800i",
				PresetRuntimeUpdate.resolveUpdateTarget(preferences, current, root));
	}

	@Test
	public void runtimeTargetRequiresOriginAndExistingSource() throws Exception {
		File root = tempDir("runtime-missing-target-root");
		File current = tempDir("runtime-missing-target-current");
		writeConfig(current, 176, 1);
		FakePreferences preferences = new FakePreferences();

		assertNull(PresetRuntimeUpdate.resolveUpdateTarget(preferences, current, root));

		preferences.seedOrigin(current, "K800i", false);
		assertNull(PresetRuntimeUpdate.resolveUpdateTarget(preferences, current, root));
	}

	@Test
	public void runtimeTargetSuppressesUnsafeRecovery() throws Exception {
		File root = tempDir("runtime-unsafe-target-root");
		File source = preset(root, "K800i", 176, 1, 1);
		createBrokenReadyRollback(source, "config.json");
		File current = tempDir("runtime-unsafe-target-current");
		writeConfig(current, 176, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);

		assertNull(PresetRuntimeUpdate.resolveUpdateTarget(preferences, current, root));
		assertTrue(new File(source, ProfilesManager.PRESET_SAVE_ROLLBACK_DIR).isDirectory());
	}

	@Test
	public void runtimeTargetAllowsMalformedButRecoverableSourceForExplicitRepair() throws Exception {
		File root = tempDir("runtime-malformed-target-root");
		File source = new File(root, "K800i");
		assertTrue(source.mkdir());
		Files.write(configFile(source).toPath(), "{malformed".getBytes(StandardCharsets.UTF_8));
		File current = tempDir("runtime-malformed-target-current");
		writeConfig(current, 176, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);

		assertEquals("K800i",
				PresetRuntimeUpdate.resolveUpdateTarget(preferences, current, root));
	}

	@Test
	public void runtimeLocalOnlyLayoutCommitDetachesButPreservesOriginAndSource() throws Exception {
		File root = tempDir("runtime-local-only-root");
		File source = preset(root, "K800i", 176, 1, 1);
		byte[] sourceConfig = readConfigBytes(source);
		byte[] sourceLayout = readLayout(source);
		File current = tempDir("runtime-local-only-current");
		writeConfig(current, 176, 1);
		writeLayout(current, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);

		PresetLocalOverride.Guard ownership =
				PresetLocalOverride.detachBeforeWrite(preferences, current);
		assertTrue(ownership.canWrite());
		writeLayout(current, 5);

		PresetLinkage linkage = new PresetLinkage(preferences, current);
		assertEquals("K800i", linkage.getOrigin());
		assertFalse(linkage.isLinked());
		assertArrayEquals(sourceConfig, readConfigBytes(source));
		assertArrayEquals(sourceLayout, readLayout(source));
	}

	@Test
	public void runtimeLocalLayoutFailureRestoresOldOwnershipAndDoesNotTouchSource()
			throws Exception {
		File root = tempDir("runtime-layout-fail-root");
		File source = preset(root, "K800i", 176, 1, 1);
		byte[] sourceConfig = readConfigBytes(source);
		byte[] sourceLayout = readLayout(source);
		File current = tempDir("runtime-layout-fail-current");
		writeConfig(current, 176, 1);
		writeLayout(current, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);

		PresetLocalOverride.Guard ownership =
				PresetLocalOverride.detachBeforeWrite(preferences, current);
		assertTrue(ownership.canWrite());
		assertTrue(ownership.restoreIfUnchanged());

		assertLinked(preferences, current, "K800i");
		assertArrayEquals(sourceConfig, readConfigBytes(source));
		assertArrayEquals(sourceLayout, readLayout(source));
	}

	@Test
	public void runtimeUpdateRunsAfterLocalCommitAndReplacesOnlyLayout() throws Exception {
		File root = tempDir("runtime-update-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("runtime-update-current");
		writeConfig(current, 176, 1);
		writeLayout(current, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);
		byte[] sourceConfig = readConfigBytes(source);

		PresetLocalOverride.Guard ownership =
				PresetLocalOverride.detachBeforeWrite(preferences, current);
		assertTrue(ownership.canWrite());
		writeConfig(current, 640, 1);
		writeLayout(current, 5);

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetRuntimeUpdate.updateExisting(preferences, current, root, "K800i", true));
		assertArrayEquals(sourceConfig, readConfigBytes(source));
		assertEquals(640, readConfig(current).screenWidth);
		assertArrayEquals(readLayout(current), readLayout(source));
		assertLinked(preferences, current, "K800i");
	}

	@Test
	public void runtimeUpdateDoesNotIncludeCommittedScreenParams() throws Exception {
		File root = tempDir("runtime-screen-success-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("runtime-screen-success-current");
		writeConfig(current, 240, 1);
		writeLayout(current, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);

		PresetLocalOverride.Guard ownership =
				PresetLocalOverride.detachBeforeWrite(preferences, current);
		assertTrue(ownership.canWrite());
		writeLayout(current, 4);
		writeConfig(current, 800, 1);

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetRuntimeUpdate.updateExisting(preferences, current, root, "K800i", true));
		assertEquals(176, readConfig(source).screenWidth);
		assertEquals(800, readConfig(current).screenWidth);
		assertArrayEquals(readLayout(current), readLayout(source));
	}

	@Test
	public void runtimeLayoutUpdateDoesNotCopyUnchangedLocalConfig() throws Exception {
		File root = tempDir("runtime-screen-fail-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("runtime-screen-fail-current");
		writeConfig(current, 240, 1);
		writeLayout(current, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);

		PresetLocalOverride.Guard ownership =
				PresetLocalOverride.detachBeforeWrite(preferences, current);
		assertTrue(ownership.canWrite());
		// Layout committed, while the failed screen-parameter write leaves config.json unchanged.
		writeLayout(current, 6);

		assertEquals(PresetSourceSave.Result.LINKED,
				PresetRuntimeUpdate.updateExisting(preferences, current, root, "K800i", true));
		assertEquals(176, readConfig(source).screenWidth);
		assertArrayEquals(readLayout(current), readLayout(source));
	}

	@Test
	public void runtimeSourceUpdateFailureKeepsCommittedLocalLayoutCustom() throws Exception {
		File root = tempDir("runtime-source-fail-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("runtime-source-fail-current");
		writeConfig(current, 240, 1);
		writeLayout(current, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);
		assertEquals("K800i",
				PresetRuntimeUpdate.resolveUpdateTarget(preferences, current, root));

		createBrokenReadyRollback(source, "config.json");
		PresetLocalOverride.Guard ownership =
				PresetLocalOverride.detachBeforeWrite(preferences, current);
		assertTrue(ownership.canWrite());
		writeLayout(current, 7);
		byte[] committedLocalLayout = readLayout(current);

		assertEquals(PresetSourceSave.Result.FAILED,
				PresetRuntimeUpdate.updateExisting(preferences, current, root, "K800i", true));
		assertArrayEquals(committedLocalLayout, readLayout(current));
		assertEquals(176, readConfig(source).screenWidth);
		PresetLinkage linkage = new PresetLinkage(preferences, current);
		assertEquals("K800i", linkage.getOrigin());
		assertFalse(linkage.isLinked());
	}

	@Test
	public void runtimeSavedUnlinkedKeepsCommittedSourceAndNoFalseLink() throws Exception {
		File root = tempDir("runtime-unlinked-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("runtime-unlinked-current");
		writeConfig(current, 240, 1);
		writeLayout(current, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);

		PresetLocalOverride.Guard ownership =
				PresetLocalOverride.detachBeforeWrite(preferences, current);
		assertTrue(ownership.canWrite()); // commit #1
		writeConfig(current, 640, 1);
		writeLayout(current, 5);
		preferences.failCommit(3); // source replacement clear #2; linkTo #3 fails.

		assertEquals(PresetSourceSave.Result.SAVED_UNLINKED,
				PresetRuntimeUpdate.updateExisting(preferences, current, root, "K800i", true));
		assertEquals(176, readConfig(source).screenWidth);
		assertArrayEquals(readLayout(current), readLayout(source));
		PresetLinkage linkage = new PresetLinkage(preferences, current);
		assertEquals("K800i", linkage.getOrigin());
		assertFalse(linkage.isLinked());
	}

	@Test
	public void runtimeStaleRenamedTargetIsRejectedEvenWhenOldDirectoryRemains()
			throws Exception {
		File root = tempDir("runtime-stale-rename-root");
		File oldSource = preset(root, "K800i", 176, 1, 1);
		File current = new File(root.getParentFile(), "configs/runtime-stale-rename-current");
		writeConfig(current, 176, 1);
		writeLayout(current, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);
		String shownTarget = PresetRuntimeUpdate.resolveUpdateTarget(preferences, current, root);
		assertEquals("K800i", shownTarget);

		assertEquals(PresetLifecycle.Result.CLEANUP_FAILED,
				PresetLifecycle.rename(preferences, root, "K800i", "Sony K800i",
						new PresetLifecycle.FileActions() {
							@Override
							public boolean publish(File staging, File published) {
								return staging.renameTo(published);
							}

							@Override
							public boolean deleteSource(File source) {
								return false;
							}
						}));
		assertTrue(oldSource.isDirectory());
		assertTrue(new File(root, "Sony K800i").isDirectory());

		PresetLocalOverride.Guard ownership =
				PresetLocalOverride.detachBeforeWrite(preferences, current);
		assertTrue(ownership.canWrite());
		writeConfig(current, 640, 1);
		writeLayout(current, 5);

		assertEquals(PresetSourceSave.Result.FAILED,
				PresetRuntimeUpdate.updateExisting(preferences, current, root, shownTarget, true));
		assertEquals(176, readConfig(oldSource).screenWidth);
		assertEquals(176, readConfig(new File(root, "Sony K800i")).screenWidth);
		PresetLinkage linkage = new PresetLinkage(preferences, current);
		assertEquals("Sony K800i", linkage.getOrigin());
		assertFalse(linkage.isLinked());
	}

	@Test
	public void runtimeStaleDeletedTargetDoesNotRecreateSource() throws Exception {
		File root = tempDir("runtime-stale-delete-root");
		preset(root, "K800i", 176, 1, 1);
		File current = new File(root.getParentFile(), "configs/runtime-stale-delete-current");
		writeConfig(current, 176, 1);
		writeLayout(current, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", true);
		String shownTarget = PresetRuntimeUpdate.resolveUpdateTarget(preferences, current, root);
		assertEquals("K800i", shownTarget);

		assertEquals(PresetLifecycle.Result.SUCCESS,
				PresetLifecycle.delete(preferences, root, "K800i"));
		PresetLocalOverride.Guard ownership =
				PresetLocalOverride.detachBeforeWrite(preferences, current);
		assertTrue(ownership.canWrite());
		writeLayout(current, 5);

		assertEquals(PresetSourceSave.Result.FAILED,
				PresetRuntimeUpdate.updateExisting(preferences, current, root, shownTarget, true));
		assertFalse(new File(root, "K800i").exists());
		assertNull(new PresetLinkage(preferences, current).getOrigin());
	}

	@Test
	public void runtimeUpdateLeavesOtherFollowersMetadataUnchanged() throws Exception {
		File root = tempDir("runtime-follower-metadata-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("runtime-follower-current");
		File follower = tempDir("runtime-other-follower");
		writeConfig(current, 640, 1);
		writeLayout(current, 5);
		writeConfig(follower, 176, 1);
		writeLayout(follower, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);
		preferences.seedOrigin(follower, "K800i", true);

		assertEquals(PresetSourceSave.Result.SAVED_UNLINKED,
				PresetRuntimeUpdate.updateExisting(preferences, current, root, "K800i", false));
		assertEquals(176, readConfig(source).screenWidth);
		assertFalse(new PresetLinkage(preferences, current).isLinked());
		assertLinked(preferences, follower, "K800i");
	}

	@Test
	public void runtimeUpdatedSourceReachesFollowerAtNextPreload() throws Exception {
		File root = tempDir("runtime-follower-preload-root");
		File source = preset(root, "K800i", 176, 1, 1);
		File current = tempDir("runtime-preload-current");
		File follower = tempDir("runtime-preload-follower");
		writeConfig(current, 640, 1);
		writeLayout(current, 5);
		writeConfig(follower, 176, 1);
		writeLayout(follower, 1);
		FakePreferences preferences = new FakePreferences();
		preferences.seedOrigin(current, "K800i", false);
		preferences.seedOrigin(follower, "K800i", true);

		assertEquals(PresetSourceSave.Result.SAVED_UNLINKED,
				PresetRuntimeUpdate.updateExisting(preferences, current, root, "K800i", false));
		assertTrue(MidletConfigLoadBoundary.prepare(preferences, follower, root));

		assertEquals(176, readConfig(follower).screenWidth);
		assertEquals(640, readConfig(current).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(follower));
		assertLinked(preferences, follower, "K800i");
	}

	private static File preset(File root, String name, int width, int vkType, Integer layout)
			throws Exception {
		File source = new File(root, name);
		assertTrue(source.mkdir());
		writeConfig(source, width, vkType);
		if (layout != null) writeLayout(source, layout);
		return source;
	}

	private static void createLiveRollback(File source, byte[] config, byte[] layout)
			throws Exception {
		File rollback = new File(source, ProfilesManager.PRESET_SAVE_ROLLBACK_DIR);
		assertTrue(rollback.mkdir());
		Files.write(new File(rollback, "config.json").toPath(), config);
		assertTrue(new File(rollback, "config.json.present").createNewFile());
		Files.write(new File(rollback, "VirtualKeyboardLayout").toPath(), layout);
		assertTrue(new File(rollback, "VirtualKeyboardLayout.present").createNewFile());
		assertTrue(new File(rollback, ProfilesManager.PRESET_SAVE_READY_MARKER).createNewFile());
	}

	private static void createBrokenReadyRollback(File source, String missingBackupName)
			throws Exception {
		File rollback = new File(source, ProfilesManager.PRESET_SAVE_ROLLBACK_DIR);
		assertTrue(rollback.mkdir());
		assertTrue(new File(rollback, missingBackupName + ".present").createNewFile());
		assertTrue(new File(rollback, ProfilesManager.PRESET_SAVE_READY_MARKER).createNewFile());
	}

	private static void assertLinked(FakePreferences preferences, File dir, String name) {
		PresetLinkage linkage = new PresetLinkage(preferences, dir);
		assertEquals(name, linkage.getOrigin());
		assertTrue(linkage.isLinked());
	}

	private static File tempDir(String suffix) throws Exception {
		File dir = Files.createTempDirectory("jlmod-source-save-" + suffix).toFile();
		dir.deleteOnExit();
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
				new String(readConfigBytes(dir), StandardCharsets.UTF_8), ProfileModel.class);
	}

	private static byte[] readConfigBytes(File dir) throws Exception {
		return Files.readAllBytes(configFile(dir).toPath());
	}

	private static void writeLayout(File dir, int type) throws Exception {
		if (!dir.isDirectory()) assertTrue(dir.mkdirs());
		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(layoutFile(dir)))) {
			out.writeInt(LAYOUT_SIGNATURE);
			out.writeInt(1);
			out.writeInt(LAYOUT_TYPE);
			out.writeInt(1);
			out.writeByte(type);
			out.writeInt(LAYOUT_EOF);
			out.writeInt(0);
		}
	}

	private static byte[] readLayout(File dir) throws Exception {
		return Files.readAllBytes(layoutFile(dir).toPath());
	}

	private static File configFile(File dir) {
		return new File(dir, Config.MIDLET_CONFIG_FILE);
	}

	private static File layoutFile(File dir) {
		return new File(dir, Config.MIDLET_KEY_LAYOUT_FILE);
	}

	private static final class FakePreferences implements SharedPreferences {
		private final Map<String, Object> values = new HashMap<>();
		private final Set<Integer> failedCommits = new HashSet<>();
		private int commitCount;

		void failCommit(int number) {
			failedCommits.add(number);
		}

		void seedOrigin(File dir, String origin, boolean linked) {
			values.put(PresetLinkage.originPreferenceKey(dir), origin);
			if (linked) values.put(PresetLinkage.linkedPreferenceKey(dir), true);
			else values.remove(PresetLinkage.linkedPreferenceKey(dir));
		}

		void seedBoolean(String key, boolean value) {
			values.put(key, value);
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
				return !failedCommits.contains(commitCount);
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
