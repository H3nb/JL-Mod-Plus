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

import ru.playsoftware.j2meloader.config.model.Size;

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

	/** Opens the existing profile-load flow from the General destination. */
	default void onUseProfile() {
	}

	/** Opens the existing profile-save flow from the General destination. */
	default void onSaveAsProfile() {
	}

	/** Opens profile template management from the General destination. */
	default void onManageProfiles() {
	}

	default void onApplyBuiltInTemplate() {
	}

	default void onApplyTemplate(@NonNull String name) {
	}

	default void onSaveTemplate(@NonNull String name) {
	}

	default void onUpdateTemplate(@NonNull String name) {
	}

	default void onRenameTemplate(@NonNull String oldName, @NonNull String newName) {
	}

	default void onDeleteTemplate(@NonNull String name) {
	}

	default void onSetDefaultTemplate(@Nullable String name) {
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
