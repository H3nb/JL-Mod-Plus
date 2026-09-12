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

package io.github.h3nb.jlmodplus.config;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.h3nb.jlmodplus.config.model.Size;

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
	/** Saved layout projection of the same profile collection; combined entries are not duplicated on disk. */
	@NonNull
	public final List<ProfileTemplate> keyboardLayouts;
	/** True when the current MIDlet artifact can execute the timing bridge. */
	public final boolean timingControlsEnabled;
	/** True when the current application owns a separate virtual keyboard layout artifact. */
	public final boolean hasKeyboardLayout;
	/** Names already occupied by profiles, including layout-only and unavailable entries. */
	@NonNull
	public final List<String> profileNames;

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
		this(form, screenPresets, fontPresets, skins, soundBanks, shaders, removableScreenPresets,
				profileStatus, profileTemplates, timingControlsEnabled, false,
				Collections.emptyList(), Collections.emptyList());
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
			boolean timingControlsEnabled,
			boolean hasKeyboardLayout) {
		this(form, screenPresets, fontPresets, skins, soundBanks, shaders, removableScreenPresets,
				profileStatus, profileTemplates, timingControlsEnabled, hasKeyboardLayout,
				Collections.emptyList(), Collections.emptyList());
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
			boolean timingControlsEnabled,
			boolean hasKeyboardLayout,
			@NonNull List<String> profileNames) {
		this(form, screenPresets, fontPresets, skins, soundBanks, shaders, removableScreenPresets,
				profileStatus, profileTemplates, timingControlsEnabled, hasKeyboardLayout,
				profileNames, Collections.emptyList());
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
			boolean timingControlsEnabled,
			boolean hasKeyboardLayout,
			@NonNull List<String> profileNames,
			@NonNull List<ProfileTemplate> keyboardLayouts) {
		this.form = form;
		this.screenPresets = immutableCopy(screenPresets);
		this.removableScreenPresets = immutableCopy(removableScreenPresets);
		this.fontPresets = immutableCopy(fontPresets);
		this.skins = immutableCopy(skins);
		this.soundBanks = immutableCopy(soundBanks);
		this.shaders = immutableCopy(shaders);
		this.profileStatus = profileStatus;
		this.profileTemplates = immutableCopy(profileTemplates);
		this.keyboardLayouts = immutableCopy(keyboardLayouts);
		this.timingControlsEnabled = timingControlsEnabled;
		this.hasKeyboardLayout = hasKeyboardLayout;
		this.profileNames = immutableCopy(profileNames);
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
		public final boolean hasKeyboardLayout;
		/** True when settings are readable but the separate layout artifact is not. */
		public final boolean keyboardLayoutUnavailable;
		public final int screenWidth;
		public final int screenHeight;
		public final int orientation;

		public ProfileTemplate(@NonNull String name, boolean isDefault) {
			this(name, isDefault, false, 0, 0, 0);
		}

		public ProfileTemplate(@NonNull String name, boolean isDefault, boolean hasKeyboardLayout,
				int screenWidth, int screenHeight, int orientation) {
			this(name, isDefault, hasKeyboardLayout, false, screenWidth, screenHeight, orientation);
		}

		public ProfileTemplate(@NonNull String name, boolean isDefault, boolean hasKeyboardLayout,
				boolean keyboardLayoutUnavailable, int screenWidth, int screenHeight, int orientation) {
			this.name = name;
			this.isDefault = isDefault;
			this.hasKeyboardLayout = hasKeyboardLayout;
			this.keyboardLayoutUnavailable = keyboardLayoutUnavailable;
			this.screenWidth = screenWidth;
			this.screenHeight = screenHeight;
			this.orientation = orientation;
		}
	}

	/** Profile matching result used by the MIDlet General destination. */
	public static final class ProfileStatus {
		@Nullable
		public final String activeProfile;
		@Nullable
		public final String sourceProfile;
		@Nullable
		public final String defaultProfile;
		public final boolean builtInDefault;
		public final boolean modified;

		private ProfileStatus(@Nullable String activeProfile, @Nullable String sourceProfile,
				@Nullable String defaultProfile, boolean builtInDefault, boolean modified) {
			this.activeProfile = activeProfile;
			this.sourceProfile = sourceProfile;
			this.defaultProfile = defaultProfile;
			this.builtInDefault = builtInDefault;
			this.modified = modified;
		}

		@NonNull
		public static ProfileStatus custom(@Nullable String defaultProfile) {
			return new ProfileStatus(null, null, defaultProfile, false, false);
		}

		@NonNull
		public static ProfileStatus builtInDefault(@Nullable String defaultProfile) {
			return new ProfileStatus(null, null, defaultProfile, true, false);
		}

		@NonNull
		public static ProfileStatus active(@NonNull String activeProfile,
				@Nullable String defaultProfile) {
			return new ProfileStatus(activeProfile, activeProfile, defaultProfile, false, false);
		}

		@NonNull
		public static ProfileStatus modified(@NonNull String sourceProfile,
				@Nullable String defaultProfile) {
			return new ProfileStatus(null, sourceProfile, defaultProfile, false, true);
		}
	}
}
