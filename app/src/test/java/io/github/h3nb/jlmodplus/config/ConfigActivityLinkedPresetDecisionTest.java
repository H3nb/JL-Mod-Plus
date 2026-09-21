/*
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ConfigActivityLinkedPresetDecisionTest {
	@Test
	public void combinedPresetIsCompleteOnlyForSettingsAndKeyboard() {
		ProfilesManager.ProfileInfo combined = info(
				ProfilesManager.CapabilityStatus.READY,
				ProfilesManager.CapabilityStatus.READY);

		assertTrue(ConfigActivity.isCompletePresetCandidate(
				combined, ConfigFormEvents.PresetApplyScope.SETTINGS_AND_KEYBOARD));
		assertFalse(ConfigActivity.isCompletePresetCandidate(
				combined, ConfigFormEvents.PresetApplyScope.SETTINGS));
		assertFalse(ConfigActivity.isCompletePresetCandidate(
				combined, ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT));
	}

	@Test
	public void configOnlyPresetIsCompleteOnlyForSettings() {
		ProfilesManager.ProfileInfo configOnly = info(
				ProfilesManager.CapabilityStatus.READY,
				ProfilesManager.CapabilityStatus.ABSENT);

		assertTrue(ConfigActivity.isCompletePresetCandidate(
				configOnly, ConfigFormEvents.PresetApplyScope.SETTINGS));
		assertFalse(ConfigActivity.isCompletePresetCandidate(
				configOnly, ConfigFormEvents.PresetApplyScope.SETTINGS_AND_KEYBOARD));
		assertFalse(ConfigActivity.isCompletePresetCandidate(
				configOnly, ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT));
	}

	@Test
	public void unavailableKeyboardArtifactStillMakesCombinedRequestTheCompleteCandidate() {
		ProfilesManager.ProfileInfo corruptCombined = info(
				ProfilesManager.CapabilityStatus.READY,
				ProfilesManager.CapabilityStatus.UNAVAILABLE);

		assertTrue(ConfigActivity.isCompletePresetCandidate(
				corruptCombined, ConfigFormEvents.PresetApplyScope.SETTINGS_AND_KEYBOARD));
		assertFalse(ConfigActivity.isCompletePresetCandidate(
				corruptCombined, ConfigFormEvents.PresetApplyScope.SETTINGS));
	}

	@Test
	public void keyboardOnlyPresetRemainsLegacyPartialDefault() {
		ProfilesManager.ProfileInfo keyboardOnly = info(
				ProfilesManager.CapabilityStatus.ABSENT,
				ProfilesManager.CapabilityStatus.READY);

		assertFalse(ConfigActivity.hasApplicationSettingsArtifact(keyboardOnly));
		assertTrue(ConfigActivity.isLegacyKeyboardOnlyDefault(keyboardOnly));
		assertFalse(ConfigActivity.isCompletePresetCandidate(
				keyboardOnly, ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT));
	}

	@Test
	public void unavailableSettingsArtifactStillRoutesThroughExactDefaultActivation() {
		ProfilesManager.ProfileInfo malformedSettings = info(
				ProfilesManager.CapabilityStatus.UNAVAILABLE,
				ProfilesManager.CapabilityStatus.ABSENT);

		assertTrue(ConfigActivity.hasApplicationSettingsArtifact(malformedSettings));
		assertFalse(ConfigActivity.isLegacyKeyboardOnlyDefault(malformedSettings));
	}

	@Test
	public void abandonedNewSidecarAloneDoesNotMakeMidletExisting() throws Exception {
		File target = tempDir("new-sidecar");
		Files.write(new File(target, Config.MIDLET_KEY_LAYOUT_FILE + ".new").toPath(), new byte[] {1});

		assertFalse(ConfigActivity.hasExistingSetupAfterRecovery(target, null, false));
	}

	@Test
	public void recoverableLayoutBackupCountsAsExistingSetup() throws Exception {
		File target = tempDir("layout-backup");
		Files.write(new File(target, Config.MIDLET_KEY_LAYOUT_FILE + ".bak").toPath(), new byte[] {1});

		assertTrue(ConfigActivity.hasExistingSetupAfterRecovery(target, null, false));
	}

	@Test
	public void provenanceAndBuiltInOwnershipEachCountAsExistingSetup() throws Exception {
		File target = tempDir("ownership-evidence");

		assertTrue(ConfigActivity.hasExistingSetupAfterRecovery(target, "K800i", false));
		assertTrue(ConfigActivity.hasExistingSetupAfterRecovery(target, null, true));
	}

	@Test
	public void interruptedDefaultPublicationRecoveredToEmptyIsStillNew() throws Exception {
		File target = tempDir("interrupted-empty");
		Files.write(new File(target, Config.MIDLET_CONFIG_FILE).toPath(),
				"partially-published".getBytes(StandardCharsets.UTF_8));
		File rollback = new File(target, ".preset-sync.rollback");
		assertTrue(rollback.mkdir());
		assertTrue(new File(rollback, ".ready").createNewFile());

		ProfilesManager.recoverInterruptedSnapshotSync(target);

		assertFalse(new File(target, Config.MIDLET_CONFIG_FILE).exists());
		assertFalse(ConfigActivity.hasExistingSetupAfterRecovery(target, null, false));
	}

	@Test
	public void originOnlyEqualConfigurationCannotBePromotedToActive() {
		ConfigUiState.ProfileStatus status = ConfigActivity.resolveNamedPresetStatus(
				false, "K800i", true, null);

		assertNull(status.activeProfile);
		assertEquals("K800i", status.sourceProfile);
		assertTrue(status.modified);
	}

	@Test
	public void explicitLinkedOriginIsActiveWithoutMatcherEquality() {
		ConfigUiState.ProfileStatus status = ConfigActivity.resolveNamedPresetStatus(
				true, "K800i", true, null);

		assertEquals("K800i", status.activeProfile);
		assertEquals("K800i", status.sourceProfile);
		assertFalse(status.modified);
	}

	@Test
	public void explicitLinkedOriginRemainsActiveWhenSourceIsUnavailable() {
		ConfigUiState.ProfileStatus status = ConfigActivity.resolveNamedPresetStatus(
				true, "K800i", false, "Default");

		assertEquals("K800i", status.activeProfile);
		assertEquals("K800i", status.sourceProfile);
		assertEquals("Default", status.defaultProfile);
	}

	@Test
	public void unlinkedMissingOriginSourceFallsThroughToCustomOrBuiltInDecision() {
		assertNull(ConfigActivity.resolveNamedPresetStatus(
				false, "K800i", false, null));
	}

	private static ProfilesManager.ProfileInfo info(
			ProfilesManager.CapabilityStatus settings,
			ProfilesManager.CapabilityStatus keyboard) {
		ProfileModel config = settings == ProfilesManager.CapabilityStatus.READY
				? new ProfileModel() : null;
		return new ProfilesManager.ProfileInfo(
				new Profile("fixture"),
				config,
				new ProfilesManager.Capability(settings, null),
				new ProfilesManager.Capability(keyboard, null));
	}

	private static File tempDir(String suffix) throws Exception {
		File dir = Files.createTempDirectory("jlmod-config-decision-" + suffix).toFile();
		dir.deleteOnExit();
		return dir;
	}
}
