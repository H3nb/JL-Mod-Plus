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
package javax.microedition.lcdui.keyboard;

import io.github.h3nb.jlmodplus.config.ProfileModel;

/** One semantic color and opacity contract for legacy buttons and grouped controls. */
final class VirtualControlAppearance {
	private VirtualControlAppearance() {}

	static int background(ProfileModel profile, boolean pressed) {
		return (pressed ? profile.vkBgColorSelected : profile.vkBgColor) & 0xFFFFFF;
	}

	static int foreground(ProfileModel profile, boolean pressed) {
		return (pressed ? profile.vkFgColorSelected : profile.vkFgColor) & 0xFFFFFF;
	}

	static int outline(ProfileModel profile) {
		return profile.vkOutlineColor & 0xFFFFFF;
	}

	/** Only secondary details are blended; user-selected primary/pressed colors remain exact. */
	static int mixRgb(int from, int to, float ratio) {
		float amount = Math.max(0f, Math.min(1f, ratio));
		float inverse = 1f - amount;
		int red = Math.round(((from >> 16) & 255) * inverse + ((to >> 16) & 255) * amount);
		int green = Math.round(((from >> 8) & 255) * inverse + ((to >> 8) & 255) * amount);
		int blue = Math.round((from & 255) * inverse + (to & 255) * amount);
		return (red << 16) | (green << 8) | blue;
	}

	static int analogThumb(ProfileModel profile, boolean active) {
		return active ? background(profile, true) :
				mixRgb(background(profile, false), outline(profile), 0.18f);
	}

	static int opacity(int configured, boolean editing, boolean forceOpaqueOutsideGuest) {
		if (editing || forceOpaqueOutsideGuest) return 255;
		return Math.max(0, Math.min(255, configured));
	}
}
