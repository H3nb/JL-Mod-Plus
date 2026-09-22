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

public class LinkedPresetActivationTest {
	private static final int LAYOUT_SIGNATURE = 0x564B4C00;
	private static final int LAYOUT_TYPE = 3;
	private static final int LAYOUT_EOF = -1;

	@Test
	public void completeConfigAndLayoutBecomesLinked() throws Exception {
		File source = tempDir("combined-source");
		File target = tempDir("combined-target");
		writeConfig(source, 360, 1);
		writeLayout(source, 3);
		writeConfig(target, 176, 1);
		writeLayout(target, 1);
		FakePreferences preferences = new FakePreferences();

		assertEquals(
				LinkedPresetActivation.Result.LINKED,
				LinkedPresetActivation.activate(preferences, target, source, "K800i"));

		assertEquals(360, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(target));
		assertLinked(preferences, target, "K800i");
	}

	@Test
	public void validConfigOnlyBecomesLinkedAndRemovesStaleLayoutFamily() throws Exception {
		File source = tempDir("config-only-source");
		File target = tempDir("config-only-target");
		writeConfig(source, 360, 1);
		writeConfig(target, 176, 1);
		writeLayout(target, 2);
		Files.write(new File(target, "VirtualKeyboardLayout.new").toPath(), new byte[] {1});
		Files.write(new File(target, "VirtualKeyboardLayout.bak").toPath(), new byte[] {2});
		FakePreferences preferences = new FakePreferences();

		assertEquals(
				LinkedPresetActivation.Result.LINKED,
				LinkedPresetActivation.activate(preferences, target, source, "K800i"));

		assertEquals(360, readConfig(target).screenWidth);
		assertFalse(layoutFile(target).exists());
		assertFalse(new File(target, "VirtualKeyboardLayout.new").exists());
		assertFalse(new File(target, "VirtualKeyboardLayout.bak").exists());
		assertLinked(preferences, target, "K800i");
	}

	@Test
	public void customWithoutLayoutFailsSafelyAndDoesNotLink() throws Exception {
		File source = tempDir("custom-missing-layout-source");
		File target = tempDir("custom-missing-layout-target");
		writeConfig(source, 360, VirtualKeyboard.TYPE_CUSTOM);
		writeConfig(target, 176, 1);
		byte[] previous = Files.readAllBytes(configFile(target).toPath());
		FakePreferences preferences = new FakePreferences();

		assertEquals(
				LinkedPresetActivation.Result.FAILED_SAFE,
				LinkedPresetActivation.activate(preferences, target, source, "K800i"));

		assertArrayEquals(previous, Files.readAllBytes(configFile(target).toPath()));
		assertFalse(new PresetLinkage(preferences, target).isLinked());
	}

	@Test
	public void corruptLayoutFailsSafelyAndDoesNotLink() throws Exception {
		File source = tempDir("corrupt-layout-source");
		File target = tempDir("corrupt-layout-target");
		writeConfig(source, 360, 1);
		Files.write(layoutFile(source).toPath(), "corrupt".getBytes(StandardCharsets.UTF_8));
		writeConfig(target, 176, 1);
		byte[] previous = Files.readAllBytes(configFile(target).toPath());
		FakePreferences preferences = new FakePreferences();

		assertEquals(
				LinkedPresetActivation.Result.FAILED_SAFE,
				LinkedPresetActivation.activate(preferences, target, source, "K800i"));

		assertArrayEquals(previous, Files.readAllBytes(configFile(target).toPath()));
		assertFalse(new PresetLinkage(preferences, target).isLinked());
	}

	@Test
	public void switchingLinkedK800iToN95PublishesN95ThenLinksN95() throws Exception {
		File source = tempDir("n95-source");
		File target = tempDir("switch-target");
		writeConfig(source, 480, 1);
		writeLayout(source, 3);
		writeConfig(target, 240, 1);
		writeLayout(target, 1);
		FakePreferences preferences = linked(target, "K800i");

		assertEquals(
				LinkedPresetActivation.Result.LINKED,
				LinkedPresetActivation.activate(preferences, target, source, "N95"));

		assertEquals(480, readConfig(target).screenWidth);
		assertArrayEquals(readLayout(source), readLayout(target));
		assertLinked(preferences, target, "N95");
	}

	@Test
	public void clearMetadataFailureBlocksSyncAndLeavesFilesystemUntouched() throws Exception {
		File source = tempDir("clear-fail-source");
		File target = tempDir("clear-fail-target");
		writeConfig(source, 480, 1);
		writeConfig(target, 240, 1);
		byte[] previous = Files.readAllBytes(configFile(target).toPath());
		FakePreferences preferences = linked(target, "K800i");
		preferences.failCommit(2);

		assertEquals(
				LinkedPresetActivation.Result.FAILED_SAFE,
				LinkedPresetActivation.activate(preferences, target, source, "N95"));

		assertArrayEquals(previous, Files.readAllBytes(configFile(target).toPath()));
		assertLinked(preferences, target, "K800i");
	}

	@Test
	public void syncFailureRecoversOldSnapshotAndRestoresPreviousAssociation() throws Exception {
		File source = tempDir("sync-fail-source");
		File target = tempDir("sync-fail-target");
		writeConfig(source, 480, VirtualKeyboard.TYPE_CUSTOM);
		writeConfig(target, 240, 1);
		writeLayout(target, 2);
		byte[] previousConfig = Files.readAllBytes(configFile(target).toPath());
		byte[] previousLayout = readLayout(target);
		FakePreferences preferences = linked(target, "K800i");

		assertEquals(
				LinkedPresetActivation.Result.FAILED_SAFE,
				LinkedPresetActivation.activate(preferences, target, source, "N95"));

		assertArrayEquals(previousConfig, Files.readAllBytes(configFile(target).toPath()));
		assertArrayEquals(previousLayout, readLayout(target));
		assertLinked(preferences, target, "K800i");
	}

	@Test
	public void failedRecoveryReturnsUnsafeWithoutRestoringPreviousLink() throws Exception {
		File source = tempDir("unsafe-source");
		File target = tempDir("unsafe-target");
		writeConfig(source, 480, 1);
		writeConfig(target, 240, 1);
		File rollback = new File(target, ".preset-sync.rollback");
		assertTrue(rollback.mkdir());
		assertTrue(new File(rollback, "config.json.present").createNewFile());
		assertTrue(new File(rollback, ".ready").createNewFile());
		FakePreferences preferences = linked(target, "K800i");

		assertEquals(
				LinkedPresetActivation.Result.FAILED_UNSAFE,
				LinkedPresetActivation.activate(preferences, target, source, "N95"));

		assertFalse(new PresetLinkage(preferences, target).isLinked());
	}

	@Test
	public void linkFailureKeepsNewSnapshotAsCustomAndNeverRestoresOldLink() throws Exception {
		File source = tempDir("link-fail-source");
		File target = tempDir("link-fail-target");
		writeConfig(source, 480, 1);
		writeConfig(target, 240, 1);
		FakePreferences preferences = linked(target, "K800i");
		preferences.failCommit(3); // fixture #1, clear #2, linkTo(N95) #3.

		assertEquals(
				LinkedPresetActivation.Result.APPLIED_CUSTOM,
				LinkedPresetActivation.activate(preferences, target, source, "N95"));

		assertEquals(480, readConfig(target).screenWidth);
		PresetLinkage linkage = new PresetLinkage(preferences, target);
		assertFalse(linkage.isLinked());
		assertEquals("N95", linkage.getOrigin());
	}

	@Test
	public void newMidletValidCompleteDefaultCanActivateLinked() throws Exception {
		File source = tempDir("default-source");
		File target = tempDir("new-midlet");
		writeConfig(source, 360, 1);
		FakePreferences preferences = new FakePreferences();

		assertEquals(
				LinkedPresetActivation.Result.LINKED,
				LinkedPresetActivation.activate(preferences, target, source, "Default"));

		assertEquals(360, readConfig(target).screenWidth);
		assertLinked(preferences, target, "Default");
	}

	@Test
	public void invalidCustomDefaultFallsBackSafelyWithoutLiveLink() throws Exception {
		File source = tempDir("invalid-default-source");
		File target = tempDir("invalid-default-target");
		writeConfig(source, 360, VirtualKeyboard.TYPE_CUSTOM);
		FakePreferences preferences = new FakePreferences();

		assertEquals(
				LinkedPresetActivation.Result.FAILED_SAFE,
				LinkedPresetActivation.activate(preferences, target, source, "Default"));

		assertFalse(configFile(target).exists());
		assertFalse(new PresetLinkage(preferences, target).isLinked());
		assertNull(new PresetLinkage(preferences, target).getOrigin());
	}

	@Test
	public void completeSnapshotWithoutMarkerRemainsCustomOnNextPreload() throws Exception {
		File profilesRoot = tempDir("profiles-root");
		File source = new File(profilesRoot, "K800i");
		File target = tempDir("materialized-custom");
		assertTrue(source.mkdir());
		writeConfig(source, 360, 1);
		ProfilesManager.syncSnapshot(source, target);
		FakePreferences preferences = new FakePreferences();

		assertTrue(MidletConfigLoadBoundary.prepare(preferences, target, profilesRoot));

		PresetLinkage linkage = new PresetLinkage(preferences, target);
		assertFalse(linkage.isLinked());
		assertNull(linkage.getOrigin());
		assertEquals(360, readConfig(target).screenWidth);
		assertTrue(ConfigActivity.hasExistingSetupAfterRecovery(target, null, false));
	}


	@Test
	public void builtInOwnedCompleteSnapshotWithLinkFailureRemainsCustom() throws Exception {
		File source = tempDir("builtin-link-fail-source");
		File target = tempDir("builtin-link-fail-target");
		writeConfig(source, 480, 1);
		writeConfig(target, 240, 1);
		byte[] previous = Files.readAllBytes(configFile(target).toPath());
		FakePreferences preferences = builtInOwned(target);
		preferences.failCommit(3); // built-in fixture #1, ownership clear #2, link #3.

		assertEquals(
				LinkedPresetActivation.Result.APPLIED_CUSTOM,
				LinkedPresetActivation.activate(preferences, target, source, "K800i"));

		assertEquals(480, readConfig(target).screenWidth);
		assertFalse(java.util.Arrays.equals(previous, Files.readAllBytes(configFile(target).toPath())));
		assertFalse(isBuiltInOwned(preferences, target));
		assertFalse(new PresetLinkage(preferences, target).isLinked());
		assertEquals("K800i", new PresetLinkage(preferences, target).getOrigin());
	}

	@Test
	public void crashAfterExactSnapshotBeforeNamedLinkCannotReviveBuiltInOwnership() throws Exception {
		File profilesRoot = tempDir("crash-profiles-root");
		File source = new File(profilesRoot, "K800i");
		File target = tempDir("crash-after-snapshot");
		assertTrue(source.mkdir());
		writeConfig(source, 360, 1);
		writeConfig(target, 176, 1);
		FakePreferences preferences = builtInOwned(target);

		PresetSourceReplacement.Guard guard = PresetSourceReplacement.begin(preferences, target);
		assertTrue(guard.canWrite());
		assertFalse(isBuiltInOwned(preferences, target));
		ProfilesManager.syncSnapshot(source, target);

		// Simulated process death here: no linkTo() and no later Activity callback.
		assertTrue(MidletConfigLoadBoundary.prepare(preferences, target, profilesRoot));
		assertFalse(isBuiltInOwned(preferences, target));
		assertNull(new PresetLinkage(preferences, target).getOrigin());
		assertFalse(new PresetLinkage(preferences, target).isLinked());
		assertTrue(ConfigActivity.hasExistingSetupAfterRecovery(target, null, false));
	}

	@Test
	public void successfulCompleteActivationClearsBuiltInAndPublishesNamedLink() throws Exception {
		File source = tempDir("builtin-success-source");
		File target = tempDir("builtin-success-target");
		writeConfig(source, 360, 1);
		writeConfig(target, 176, 1);
		FakePreferences preferences = builtInOwned(target);

		assertEquals(
				LinkedPresetActivation.Result.LINKED,
				LinkedPresetActivation.activate(preferences, target, source, "K800i"));

		assertFalse(isBuiltInOwned(preferences, target));
		assertLinked(preferences, target, "K800i");
	}

	@Test
	public void failedSafeExactActivationRestoresBuiltInOwnership() throws Exception {
		File source = tempDir("builtin-failed-safe-source");
		File target = tempDir("builtin-failed-safe-target");
		writeConfig(source, 360, VirtualKeyboard.TYPE_CUSTOM);
		writeConfig(target, 176, 1);
		byte[] previous = Files.readAllBytes(configFile(target).toPath());
		FakePreferences preferences = builtInOwned(target);

		assertEquals(
				LinkedPresetActivation.Result.FAILED_SAFE,
				LinkedPresetActivation.activate(preferences, target, source, "K800i"));

		assertArrayEquals(previous, Files.readAllBytes(configFile(target).toPath()));
		assertTrue(isBuiltInOwned(preferences, target));
		assertNull(new PresetLinkage(preferences, target).getOrigin());
	}

	@Test
	public void failedUnsafeExactActivationDoesNotRestoreBuiltInOwnership() throws Exception {
		File source = tempDir("builtin-failed-unsafe-source");
		File target = tempDir("builtin-failed-unsafe-target");
		writeConfig(source, 480, 1);
		writeConfig(target, 240, 1);
		File rollback = new File(target, ".preset-sync.rollback");
		assertTrue(rollback.mkdir());
		assertTrue(new File(rollback, "config.json.present").createNewFile());
		assertTrue(new File(rollback, ".ready").createNewFile());
		FakePreferences preferences = builtInOwned(target);

		assertEquals(
				LinkedPresetActivation.Result.FAILED_UNSAFE,
				LinkedPresetActivation.activate(preferences, target, source, "K800i"));

		assertFalse(isBuiltInOwned(preferences, target));
		assertNull(new PresetLinkage(preferences, target).getOrigin());
	}

	@Test
	public void failedDurableBuiltInClearBlocksExactFilesystemPublication() throws Exception {
		File source = tempDir("builtin-clear-fail-source");
		File target = tempDir("builtin-clear-fail-target");
		writeConfig(source, 480, 1);
		writeConfig(target, 240, 1);
		byte[] previous = Files.readAllBytes(configFile(target).toPath());
		FakePreferences preferences = builtInOwned(target);
		preferences.failCommit(2);

		assertEquals(
				LinkedPresetActivation.Result.FAILED_SAFE,
				LinkedPresetActivation.activate(preferences, target, source, "K800i"));

		assertArrayEquals(previous, Files.readAllBytes(configFile(target).toPath()));
		assertTrue(isBuiltInOwned(preferences, target));
		assertNull(new PresetLinkage(preferences, target).getOrigin());
	}

	@Test
	public void settingsOnlyReplacementClearsBuiltInBeforePublication() throws Exception {
		File target = tempDir("settings-partial");
		writeConfig(target, 176, 1);
		FakePreferences preferences = builtInOwned(target);

		PresetSourceReplacement.Guard guard = PresetSourceReplacement.begin(preferences, target);
		assertTrue(guard.canWrite());
		assertFalse(isBuiltInOwned(preferences, target));

		writeConfig(target, 360, 1);
		assertEquals(360, readConfig(target).screenWidth);
		assertFalse(isBuiltInOwned(preferences, target));
	}

	@Test
	public void keyboardOnlyReplacementClearsBuiltInBeforePublication() throws Exception {
		File target = tempDir("keyboard-partial");
		writeConfig(target, 176, 1);
		writeLayout(target, 1);
		FakePreferences preferences = builtInOwned(target);

		PresetSourceReplacement.Guard guard = PresetSourceReplacement.begin(preferences, target);
		assertTrue(guard.canWrite());
		assertFalse(isBuiltInOwned(preferences, target));

		writeLayout(target, 3);
		assertFalse(isBuiltInOwned(preferences, target));
	}

	@Test
	public void ambiguousPartialPublicationFailureNeverRestoresOldBuiltInOwnership() throws Exception {
		File target = tempDir("partial-ambiguous");
		writeConfig(target, 176, 1);
		FakePreferences preferences = builtInOwned(target);

		PresetSourceReplacement.Guard guard = PresetSourceReplacement.begin(preferences, target);
		assertTrue(guard.canWrite());
		Files.write(configFile(target).toPath(), "partial".getBytes(StandardCharsets.UTF_8));
		// Publication may have begun, so the caller intentionally does not restore the guard.

		assertFalse(isBuiltInOwned(preferences, target));
		assertNull(new PresetLinkage(preferences, target).getOrigin());
	}

	@Test
	public void intentionalBuiltInReplacementPublishesBuiltInOnlyAfterSnapshot() throws Exception {
		File target = tempDir("intentional-builtin");
		writeConfig(target, 240, 1);
		FakePreferences preferences = linked(target, "K800i");

		PresetSourceReplacement.Guard guard = PresetSourceReplacement.begin(preferences, target);
		assertTrue(guard.canWrite());
		assertFalse(new PresetLinkage(preferences, target).isLinked());
		assertFalse(isBuiltInOwned(preferences, target));

		writeConfig(target, 176, 1);
		assertTrue(guard.publishBuiltInOwnership());

		assertTrue(isBuiltInOwned(preferences, target));
		assertFalse(new PresetLinkage(preferences, target).isLinked());
		assertNull(new PresetLinkage(preferences, target).getOrigin());
	}


	@Test
	public void localBuiltInEditDetachesDurablyBeforeConfigPublication() throws Exception {
		File target = tempDir("local-builtin-edit");
		ProfileModel current = configModel(target, 30);
		assertTrue(ProfilesManager.saveConfig(current));
		ProfileModel builtIn = ProfileConfigMatcher.copyConfig(current);
		ConfigFormState draft = ConfigFormState.fromProfile(current, "")
				.toBuilder()
				.fpsLimit("60")
				.build();
		FakePreferences preferences = builtInOwned(target);
		boolean detachRequired = ConfigActivity.shouldDetachBuiltInThemeLink(
				true, false, current, draft, builtIn);

		assertTrue(detachRequired);
		assertTrue(ConfigActivity.persistConfigAfterBuiltInOwnershipBarrier(
				detachRequired,
				() -> ConfigActivity.commitBuiltInThemeOwnership(preferences, target, false),
				() -> {
					assertFalse(isBuiltInOwned(preferences, target));
					ProfileModel candidate = ProfileConfigMatcher.effectiveConfig(current, draft);
					return ProfilesManager.saveConfig(candidate);
				},
				() -> ConfigActivity.commitBuiltInThemeOwnership(preferences, target, true)));

		assertFalse(isBuiltInOwned(preferences, target));
		assertEquals(2, preferences.commitCount()); // fixture + durable detach before file publication.
		assertEquals(60, readConfig(target).fpsLimit);
	}

	@Test
	public void failedDurableBuiltInDetachBlocksConfigPublication() throws Exception {
		File target = tempDir("local-builtin-detach-fail");
		ProfileModel current = configModel(target, 30);
		assertTrue(ProfilesManager.saveConfig(current));
		ProfileModel builtIn = ProfileConfigMatcher.copyConfig(current);
		ConfigFormState draft = ConfigFormState.fromProfile(current, "")
				.toBuilder()
				.fpsLimit("60")
				.build();
		FakePreferences preferences = builtInOwned(target);
		preferences.failCommit(2); // built-in fixture #1, detach attempt #2.
		boolean[] writeCalled = {false};
		boolean detachRequired = ConfigActivity.shouldDetachBuiltInThemeLink(
				true, false, current, draft, builtIn);

		assertFalse(ConfigActivity.persistConfigAfterBuiltInOwnershipBarrier(
				detachRequired,
				() -> ConfigActivity.commitBuiltInThemeOwnership(preferences, target, false),
				() -> {
					writeCalled[0] = true;
					return true;
				},
				() -> ConfigActivity.commitBuiltInThemeOwnership(preferences, target, true)));

		assertFalse(writeCalled[0]);
		assertEquals(30, readConfig(target).fpsLimit);
	}

	@Test
	public void failedConfigWriteRestoresBuiltInOwnershipAfterSuccessfulDetach() throws Exception {
		File target = tempDir("local-builtin-write-fail");
		ProfileModel current = configModel(target, 30);
		assertTrue(ProfilesManager.saveConfig(current));
		FakePreferences preferences = builtInOwned(target);

		assertFalse(ConfigActivity.persistConfigAfterBuiltInOwnershipBarrier(
				true,
				() -> ConfigActivity.commitBuiltInThemeOwnership(preferences, target, false),
				() -> false,
				() -> ConfigActivity.commitBuiltInThemeOwnership(preferences, target, true)));

		assertTrue(isBuiltInOwned(preferences, target));
		assertEquals(30, readConfig(target).fpsLimit);
	}

	@Test
	public void noOpBuiltInLifecycleSaveKeepsOwnershipLinked() throws Exception {
		File target = tempDir("local-builtin-noop");
		ProfileModel current = configModel(target, 30);
		ProfileModel builtIn = ProfileConfigMatcher.copyConfig(current);
		ConfigFormState draft = ConfigFormState.fromProfile(current, "");
		FakePreferences preferences = builtInOwned(target);
		boolean detachRequired = ConfigActivity.shouldDetachBuiltInThemeLink(
				true, false, current, draft, builtIn);
		boolean[] detachCalled = {false};

		assertFalse(detachRequired);
		assertTrue(ConfigActivity.persistConfigAfterBuiltInOwnershipBarrier(
				detachRequired,
				() -> {
					detachCalled[0] = true;
					return ConfigActivity.commitBuiltInThemeOwnership(preferences, target, false);
				},
				() -> true,
				() -> ConfigActivity.commitBuiltInThemeOwnership(preferences, target, true)));

		assertFalse(detachCalled[0]);
		assertTrue(isBuiltInOwned(preferences, target));
		assertEquals(1, preferences.commitCount()); // fixture only; no-op save does not detach.
	}

	@Test
	public void restoredDraftBeforeSaveKeepsBuiltInOwnershipLinked() throws Exception {
		File target = tempDir("local-builtin-restored-draft");
		ProfileModel current = configModel(target, 30);
		ProfileModel builtIn = ProfileConfigMatcher.copyConfig(current);
		ConfigFormState original = ConfigFormState.fromProfile(current, "");
		ConfigFormState edited = original.toBuilder().fpsLimit("60").build();
		ConfigFormState restored = edited.toBuilder().fpsLimit("30").build();

		assertTrue(ConfigActivity.shouldDetachBuiltInThemeLink(
				true, false, current, edited, builtIn));
		assertFalse(ConfigActivity.shouldDetachBuiltInThemeLink(
				true, false, current, restored, builtIn));
	}

	@Test
	public void sourceReplacementAlwaysCommitsClearBarrierWhenOwnershipAppearsEmpty() throws Exception {
		File target = tempDir("source-clear-empty");
		FakePreferences preferences = new FakePreferences();

		PresetSourceReplacement.Guard guard = PresetSourceReplacement.begin(preferences, target);

		assertTrue(guard.canWrite());
		assertEquals(1, preferences.commitCount());
		assertFalse(isBuiltInOwned(preferences, target));
		assertNull(new PresetLinkage(preferences, target).getOrigin());
	}

	private static File tempDir(String suffix) throws Exception {
		File dir = Files.createTempDirectory("jlmod-activation-" + suffix).toFile();
		dir.deleteOnExit();
		return dir;
	}

	private static ProfileModel configModel(File dir, int fpsLimit) {
		ProfileModel model = new ProfileModel();
		model.dir = dir;
		model.version = ProfileModel.VERSION;
		model.fpsLimit = fpsLimit;
		model.systemProperties = "";
		return model;
	}

	private static void writeConfig(File dir, int screenWidth, int vkType) throws Exception {
		if (!dir.isDirectory()) assertTrue(dir.mkdirs());
		ProfileModel model = new ProfileModel();
		model.version = ProfileModel.VERSION;
		model.screenWidth = screenWidth;
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

	private static FakePreferences linked(File target, String origin) {
		FakePreferences preferences = new FakePreferences();
		assertTrue(preferences.edit()
				.putString(PresetLinkage.originPreferenceKey(target), origin)
				.putBoolean(PresetLinkage.linkedPreferenceKey(target), true)
				.commit());
		return preferences;
	}

	private static FakePreferences builtInOwned(File target) {
		FakePreferences preferences = new FakePreferences();
		assertTrue(preferences.edit()
				.putBoolean(ProfileModel.builtInThemePreferenceKey(target), true)
				.commit());
		return preferences;
	}

	private static boolean isBuiltInOwned(FakePreferences preferences, File target) {
		return preferences.getBoolean(ProfileModel.builtInThemePreferenceKey(target), false);
	}

	private static void assertLinked(FakePreferences preferences, File target, String origin) {
		PresetLinkage linkage = new PresetLinkage(preferences, target);
		assertTrue(linkage.isLinked());
		assertEquals(origin, linkage.getOrigin());
	}

	private static final class FakePreferences implements SharedPreferences {
		private final Map<String, Object> values = new HashMap<>();
		private final Set<Integer> failedCommits = new HashSet<>();
		private int commitCount;

		void failCommit(int number) {
			failedCommits.add(number);
		}

		int commitCount() {
			return commitCount;
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
