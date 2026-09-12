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

import io.github.h3nb.jlmodplus.config.model.Size;

/** Presentation events emitted by the Compose form to its host activity. */
public interface ConfigFormEvents {
	/** Called for every presentation-only draft edit; persistence remains owned by ConfigActivity. */
	void onFormChanged(ConfigFormState state);

	void onAddResolutionPreset(@NonNull Size size);

	void onRemoveResolutionPreset(Size size);

	void onColorPicker(ColorField field);

	/** Called after the host-owned picker confirms a six-digit RGB value. */
	void onColorPicked(ColorField field, String value);

	void onKeyMappings();

	void onEncodingPicker();

	/** Called after the Compose charset picker confirms a charset. */
	default void onEncodingSelected(String charset) {
	}

	void onShaderTuning();

	/** Called after the Compose shader editor confirms its four-slot value array. */
	default void onShaderTuningComplete(float[] values) {
	}

	/** Opens preset management from the General destination. */
	default void onManageProfiles() {
	}

	/** Applies only the selected parts of a saved preset. */
	default boolean onApplyBuiltInTemplate(@NonNull PresetApplyScope scope) {
		return true;
	}

	/** Applies only the selected parts of a saved preset. */
	default boolean onApplyTemplate(@NonNull String name, @NonNull PresetApplyScope scope) {
		return true;
	}

	/** Saves application settings and optionally the separate virtual keyboard layout artifact. */
	default boolean onSaveTemplate(@NonNull String name, boolean includeKeyboard) {
		return true;
	}

	/** Opens the naming dialog for a layout-only saved entry. */
	default void onSaveKeyboardLayout() {
	}

	/** Saves only the current virtual keyboard layout as a reusable layout entry. */
	default boolean onSaveKeyboardLayout(@NonNull String name) {
		return true;
	}

	/** Opens the saved keyboard-layout picker without replacing the current application settings draft. */
	default void onChooseKeyboardLayout() {
	}

	enum PresetApplyScope {
		SETTINGS,
		KEYBOARD_LAYOUT,
		SETTINGS_AND_KEYBOARD
	}

	enum ColorField {
		SCREEN_BACKGROUND,
		VIRTUAL_KEYBOARD_BACKGROUND,
		VIRTUAL_KEYBOARD_FOREGROUND,
		VIRTUAL_KEYBOARD_SELECTED_BACKGROUND,
		VIRTUAL_KEYBOARD_SELECTED_FOREGROUND,
		VIRTUAL_KEYBOARD_OUTLINE,
	}
}
