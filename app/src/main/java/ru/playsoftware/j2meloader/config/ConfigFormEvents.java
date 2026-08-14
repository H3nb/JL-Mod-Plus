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

import ru.playsoftware.j2meloader.config.model.Size;

/** Presentation events emitted by the Compose form to its host activity. */
public interface ConfigFormEvents {
	/** Called for every presentation-only draft edit; persistence remains owned by ConfigActivity. */
	void onFormChanged(ConfigFormState state);

	void onAddResolutionPreset();

	void onRemoveResolutionPreset(Size size);

	void onColorPicker(ColorField field);

	/** Called after the host-owned picker confirms a six-digit RGB value. */
	void onColorPicked(ColorField field, String value);

	void onKeyMappings();

	void onEncodingPicker();

	void onShaderTuning();

	enum ColorField {
		SCREEN_BACKGROUND,
		VIRTUAL_KEYBOARD_BACKGROUND,
		VIRTUAL_KEYBOARD_FOREGROUND,
		VIRTUAL_KEYBOARD_SELECTED_BACKGROUND,
		VIRTUAL_KEYBOARD_SELECTED_FOREGROUND,
		VIRTUAL_KEYBOARD_OUTLINE,
	}
}
