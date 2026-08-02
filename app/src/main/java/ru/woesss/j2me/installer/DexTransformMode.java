/*
 * Copyright 2026 H3NB
 *
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

package ru.woesss.j2me.installer;

/** Independent conversion layers exposed by the diagnostic reinstall flow. */
public enum DexTransformMode {
	NORMAL(false, false, "normal"),
	SPEEDHACK(true, false, "speedhack"),
	MEMORY_EDITOR(false, true, "memory_editor"),
	SPEEDHACK_MEMORY_EDITOR(true, true, "speedhack_memory_editor");

	public final boolean speedhackEnabled;
	public final boolean memoryEditorEnabled;
	public final String manifestValue;

	DexTransformMode(boolean speedhackEnabled, boolean memoryEditorEnabled,
			String manifestValue) {
		this.speedhackEnabled = speedhackEnabled;
		this.memoryEditorEnabled = memoryEditorEnabled;
		this.manifestValue = manifestValue;
	}

	public static DexTransformMode fromManifestValue(String value) {
		for (DexTransformMode mode : values()) {
			if (mode.manifestValue.equals(value)) {
				return mode;
			}
		}
		// Archives created before the diagnostic mode marker used timing
		// transforms but did not inject Memory Editor hooks.
		return SPEEDHACK;
	}
}
