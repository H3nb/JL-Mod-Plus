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

package ru.playsoftware.j2meloader.config;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ru.playsoftware.j2meloader.config.model.Size;

/**
 * Presentation snapshot for the host configuration screen.
 *
 * The form draft is deliberately kept separate from ProfileModel. The activity remains the
 * owner of persistence, file results, and compatibility-sensitive dialogs while Compose only
 * renders this snapshot and emits {@link ConfigFormEvents} callbacks.
 */
public final class ConfigUiState {
	@NonNull
	public final ConfigFormState form;
	@NonNull
	public final List<Size> screenPresets;
	@NonNull
	public final List<Size> removableScreenPresets;
	@NonNull
	public final List<FontPreset> fontPresets;
	@NonNull
	public final List<String> skins;
	@NonNull
	public final List<String> soundBanks;
	@NonNull
	public final List<ShaderInfo> shaders;
	@NonNull
	public final ProfileStatus profileStatus;
	@NonNull
	public final List<ProfileTemplate> profileTemplates;
	/** True when the current MIDlet artifact can execute the timing bridge. */
	public final boolean timingControlsEnabled;

	public ConfigUiState(
			@NonNull ConfigFormState form,
			@NonNull List<Size> screenPresets,
			@NonNull List<FontPreset> fontPresets,
			@NonNull List<String> skins,
			@NonNull List<String> soundBanks,
			@NonNull List<ShaderInfo> shaders) {
		this(form, screenPresets, fontPresets, skins, soundBanks, shaders,
				Collections.emptyList(), ProfileStatus.custom(null));
	}

	public ConfigUiState(
			@NonNull ConfigFormState form,
			@NonNull List<Size> screenPresets,
			@NonNull List<FontPreset> fontPresets,
			@NonNull List<String> skins,
			@NonNull List<String> soundBanks,
			@NonNull List<ShaderInfo> shaders,
			@NonNull List<Size> removableScreenPresets) {
		this(form, screenPresets, fontPresets, skins, soundBanks, shaders,
				removableScreenPresets, ProfileStatus.custom(null));
	}

	public ConfigUiState(
			@NonNull ConfigFormState form,
			@NonNull List<Size> screenPresets,
			@NonNull List<FontPreset> fontPresets,
			@NonNull List<String> skins,
			@NonNull List<String> soundBanks,
			@NonNull List<ShaderInfo> shaders,
			@NonNull List<Size> removableScreenPresets,
			@NonNull ProfileStatus profileStatus) {
		this(form, screenPresets, fontPresets, skins, soundBanks, shaders, removableScreenPresets,
				profileStatus, Collections.emptyList());
	}

	public ConfigUiState(
			@NonNull ConfigFormState form,
			@NonNull List<Size> screenPresets,
			@NonNull List<FontPreset> fontPresets,
			@NonNull List<String> skins,
			@NonNull List<String> soundBanks,
			@NonNull List<ShaderInfo> shaders,
			@NonNull List<Size> removableScreenPresets,
			@NonNull ProfileStatus profileStatus,
			@NonNull List<ProfileTemplate> profileTemplates) {
		this(form, screenPresets, fontPresets, skins, soundBanks, shaders, removableScreenPresets,
				profileStatus, profileTemplates, true);
	}

	public ConfigUiState(
			@NonNull ConfigFormState form,
			@NonNull List<Size> screenPresets,
			@NonNull List<FontPreset> fontPresets,
			@NonNull List<String> skins,
			@NonNull List<String> soundBanks,
			@NonNull List<ShaderInfo> shaders,
			@NonNull List<Size> removableScreenPresets,
			@NonNull ProfileStatus profileStatus,
			@NonNull List<ProfileTemplate> profileTemplates,
			boolean timingControlsEnabled) {
		this.form = form;
		this.screenPresets = immutableCopy(screenPresets);
		this.removableScreenPresets = immutableCopy(removableScreenPresets);
		this.fontPresets = immutableCopy(fontPresets);
		this.skins = immutableCopy(skins);
		this.soundBanks = immutableCopy(soundBanks);
		this.shaders = immutableCopy(shaders);
		this.profileStatus = profileStatus;
		this.profileTemplates = immutableCopy(profileTemplates);
		this.timingControlsEnabled = timingControlsEnabled;
	}

	private static <T> List<T> immutableCopy(List<T> values) {
		return Collections.unmodifiableList(new ArrayList<>(values));
	}

	/** A selectable font preset from the legacy configuration form. */
	public static final class FontPreset {
		@NonNull
		public final String title;
		public final int small;
		public final int medium;
		public final int large;

		public FontPreset(@NonNull String title, int small, int medium, int large) {
			this.title = title;
			this.small = small;
			this.medium = medium;
			this.large = large;
		}
	}

	public static final class ProfileTemplate {
		@NonNull public final String name;
		public final boolean isDefault;
		public final boolean hasSettings;
		public final boolean hasKeyboard;

		public ProfileTemplate(@NonNull String name, boolean isDefault,
				boolean hasSettings, boolean hasKeyboard) {
			this.name = name;
			this.isDefault = isDefault;
			this.hasSettings = hasSettings;
			this.hasKeyboard = hasKeyboard;
		}
	}

	/** Minimal per-component profile state consumed by the Basic destination. */
	public static final class ProfileStatus {
		@Nullable public final String defaultProfile;
		@Nullable public final String settingsProfile;
		@Nullable public final String keyboardProfile;
		public final boolean settingsBuiltIn;
		public final boolean settingsModified;
		public final boolean keyboardModified;

		private ProfileStatus(@Nullable String defaultProfile,
				@Nullable String settingsProfile, boolean settingsModified,
				@Nullable String keyboardProfile, boolean keyboardModified,
				boolean settingsBuiltIn) {
			this.defaultProfile = defaultProfile;
			this.settingsProfile = settingsProfile;
			this.settingsModified = settingsModified;
			this.keyboardProfile = keyboardProfile;
			this.keyboardModified = keyboardModified;
			this.settingsBuiltIn = settingsBuiltIn;
		}

		@NonNull
		public static ProfileStatus custom(@Nullable String defaultProfile) {
			return components(null, false, null, false, false, defaultProfile);
		}

		@NonNull
		public static ProfileStatus builtInDefault(@Nullable String defaultProfile) {
			return components(null, false, null, false, true, defaultProfile);
		}

		@NonNull
		public static ProfileStatus active(@NonNull String profile,
				@Nullable String defaultProfile) {
			return components(profile, false, profile, false, false, defaultProfile);
		}

		@NonNull
		public static ProfileStatus modified(@NonNull String profile,
				@Nullable String defaultProfile) {
			return components(profile, true, profile, true, false, defaultProfile);
		}

		@NonNull
		public static ProfileStatus components(@Nullable String settingsProfile,
				boolean settingsModified, @Nullable String keyboardProfile,
				boolean keyboardModified, boolean settingsBuiltIn,
				@Nullable String defaultProfile) {
			return new ProfileStatus(defaultProfile, settingsProfile, settingsModified,
					keyboardProfile, keyboardModified, settingsBuiltIn);
		}

		public boolean usesProfile(@NonNull String name) {
			return name.equals(settingsProfile) || name.equals(keyboardProfile);
		}

		public boolean isProfileModified(@NonNull String name) {
			return name.equals(settingsProfile) && settingsModified
					|| name.equals(keyboardProfile) && keyboardModified;
		}

		public boolean hasModifiedComponents() {
			return settingsModified || keyboardModified;
		}
	}

}
