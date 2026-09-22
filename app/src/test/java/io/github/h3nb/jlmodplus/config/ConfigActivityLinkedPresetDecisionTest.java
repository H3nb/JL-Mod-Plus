/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
	public void keyboardOnlyPresetRemainsPartialOnly() {
		ProfilesManager.ProfileInfo keyboardOnly = info(
				ProfilesManager.CapabilityStatus.ABSENT,
				ProfilesManager.CapabilityStatus.READY);

		assertFalse(ConfigActivity.isCompletePresetCandidate(
				keyboardOnly, ConfigFormEvents.PresetApplyScope.KEYBOARD_LAYOUT));
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

	@Test
	public void linkedWithoutDraftChangeDoesNotOfferUpdate() {
		ProfileModel persisted = model(240);
		ConfigFormState draft = ConfigFormState.fromProfile(persisted, persisted.systemProperties);
		ConfigUiState.ProfileStatus status = ConfigUiState.ProfileStatus.active("K800i", null);

		boolean divergent = ConfigActivity.hasEffectiveDraftDivergence(
				persisted, draft, ProfileConfigMatcher.copyConfig(persisted));

		assertFalse(divergent);
		assertNull(ConfigActivity.resolveUpdatePresetName(false, status, true, divergent));
	}

	@Test
	public void linkedEffectiveDraftChangeOffersUpdate() {
		ProfileModel persisted = model(240);
		ConfigFormState draft = ConfigFormState.fromProfile(persisted, persisted.systemProperties)
				.toBuilder().screenWidth("360").build();
		ConfigUiState.ProfileStatus status = ConfigUiState.ProfileStatus.active("K800i", null);

		boolean divergent = ConfigActivity.hasEffectiveDraftDivergence(
				persisted, draft, ProfileConfigMatcher.copyConfig(persisted));

		assertTrue(divergent);
		assertEquals("K800i",
				ConfigActivity.resolveUpdatePresetName(false, status, true, divergent));
	}

	@Test
	public void linkedDraftChangedThenRestoredHidesUpdateAgain() {
		ProfileModel persisted = model(240);
		ConfigFormState changed = ConfigFormState.fromProfile(persisted, persisted.systemProperties)
				.toBuilder().screenWidth("360").build();
		ConfigFormState restored = changed.toBuilder().screenWidth("240").build();
		ConfigUiState.ProfileStatus status = ConfigUiState.ProfileStatus.active("K800i", null);

		boolean divergent = ConfigActivity.hasEffectiveDraftDivergence(
				persisted, restored, ProfileConfigMatcher.copyConfig(persisted));

		assertFalse(divergent);
		assertNull(ConfigActivity.resolveUpdatePresetName(false, status, true, divergent));
	}

	@Test
	public void customBasedOnExistingSourceOffersUpdateWithoutDraftDivergence() {
		ConfigUiState.ProfileStatus status = ConfigUiState.ProfileStatus.modified("K800i", null);

		assertEquals("K800i",
				ConfigActivity.resolveUpdatePresetName(false, status, true, false));
	}

	@Test
	public void customWithoutSourceDoesNotOfferUpdate() {
		ConfigUiState.ProfileStatus status = ConfigUiState.ProfileStatus.custom(null);

		assertNull(ConfigActivity.resolveUpdatePresetName(false, status, false, true));
	}

	@Test
	public void missingNamedSourceSuppressesUpdateForActiveOrModifiedState() {
		assertNull(ConfigActivity.resolveUpdatePresetName(
				false, ConfigUiState.ProfileStatus.active("K800i", null), false, true));
		assertNull(ConfigActivity.resolveUpdatePresetName(
				false, ConfigUiState.ProfileStatus.modified("K800i", null), false, false));
	}

	private static ProfileModel model(int width) {
		ProfileModel model = new ProfileModel();
		model.version = ProfileModel.VERSION;
		model.screenWidth = width;
		model.screenHeight = 320;
		model.systemProperties = "";
		return model;
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
				new ProfilesManager.Capability(keyboard, null),
				false);
	}
}
