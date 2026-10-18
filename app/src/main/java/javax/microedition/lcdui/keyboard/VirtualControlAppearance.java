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
public final class VirtualControlAppearance {
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
	public static int mixRgb(int from, int to, float ratio) {
		float amount = Math.max(0f, Math.min(1f, ratio));
		float inverse = 1f - amount;
		int red = Math.round(((from >> 16) & 255) * inverse + ((to >> 16) & 255) * amount);
		int green = Math.round(((from >> 8) & 255) * inverse + ((to >> 8) & 255) * amount);
		int blue = Math.round((from & 255) * inverse + (to & 255) * amount);
		return (red << 16) | (green << 8) | blue;
	}

	/** Lightness-aware shading retains the selected background hue and leaves outline independent. */
	private static boolean isLight(int rgb) {
		int red = (rgb >>> 16) & 0xFF;
		int green = (rgb >>> 8) & 0xFF;
		int blue = rgb & 0xFF;
		return red * 54 + green * 183 + blue * 19 >= 150 * 256;
	}

	/** Keep the selected thumb exact; lift dark idle colors or shade light ones modestly. */
	public static int analogThumbColor(int normalBackground, int selectedBackground, boolean active) {
		if (active) return selectedBackground & 0xFFFFFF;
		int background = normalBackground & 0xFFFFFF;
		return mixRgb(background, isLight(background) ? 0x000000 : 0xFFFFFF, 0.10f);
	}

	/** The middle stop of the shell gradient matches the configured keypad background. */
	public static int analogShell(int background) {
		return background & 0xFFFFFF;
	}

	public static int analogShellTop(int background) {
		return mixRgb(analogShell(background), 0xFFFFFF, 0.065f);
	}

	public static int analogShellBottom(int background) {
		return mixRgb(analogShell(background), 0x000000, 0.085f);
	}

	public static int analogShellRim(int background) {
		return mixRgb(analogShell(background), 0xFFFFFF, 0.16f);
	}

	public static int analogBowlCenter(int background) {
		int base = analogShell(background);
		return mixRgb(base, isLight(base) ? 0x000000 : 0xFFFFFF,
				isLight(base) ? 0.22f : 0.025f);
	}

	public static int analogBowlEdge(int background) {
		return mixRgb(analogShell(background), 0x000000, 0.55f);
	}

	public static int analogThumbTop(int thumb) {
		return mixRgb(thumb, 0xFFFFFF, 0.09f);
	}

	public static int analogThumbBottom(int thumb) {
		return mixRgb(thumb, 0x000000, 0.12f);
	}

	public static int analogThumbRim(int thumb) {
		return mixRgb(thumb, 0xFFFFFF, 0.20f);
	}

	static int analogThumb(ProfileModel profile, boolean active) {
		return analogThumbColor(background(profile, false), background(profile, true), active);
	}

	static int opacity(int configured, boolean editing, boolean forceOpaqueOutsideGuest) {
		if (editing || forceOpaqueOutsideGuest) return 255;
		return Math.max(0, Math.min(255, configured));
	}
}
