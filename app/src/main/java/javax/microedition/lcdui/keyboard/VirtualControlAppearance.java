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
	private static int adjustTone(int rgb, float amount) {
		float strength = Math.max(-1f, Math.min(1f, amount));
		int red = adjustChannel((rgb >>> 16) & 0xFF, strength);
		int green = adjustChannel((rgb >>> 8) & 0xFF, strength);
		int blue = adjustChannel(rgb & 0xFF, strength);
		return (red << 16) | (green << 8) | blue;
	}

	private static int adjustChannel(int channel, float strength) {
		return Math.round(strength < 0f ? channel * (1f + strength)
				: channel + (255 - channel) * strength);
	}

	/** Lift only genuinely near-black colors; the strength fades smoothly as brightness rises. */
	private static float nearBlackLift(int rgb, float maximum) {
		int bright = Math.max((rgb >>> 16) & 0xFF,
				Math.max((rgb >>> 8) & 0xFF, rgb & 0xFF));
		return maximum * Math.max(0f, 1f - bright / 48f);
	}

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
		return adjustTone(background, isLight(background) ? -0.10f : 0.10f);
	}

	/** The middle stop of the shell gradient matches the configured keypad background. */
	public static int analogShell(int background) {
		return background & 0xFFFFFF;
	}

	public static int analogShellTop(int background) {
		return adjustTone(analogShell(background), 0.065f);
	}

	public static int analogShellBottom(int background) {
		return adjustTone(analogShell(background), -0.085f);
	}

	public static int analogShellRim(int background) {
		return adjustTone(analogShell(background), 0.16f);
	}

	public static int analogBowlCenter(int background) {
		int base = analogShell(background);
		return adjustTone(adjustTone(base, -0.08f), nearBlackLift(base, 0.035f));
	}

	public static int analogBowlEdge(int background) {
		int base = analogShell(background);
		return adjustTone(adjustTone(base, -0.27f), nearBlackLift(base, 0.015f));
	}

	/** A tinted drop shadow follows the background instead of using solid black. */
	public static int analogThumbShadow(int background) {
		return adjustTone(analogShell(background), -0.42f);
	}

	public static int analogThumbTop(int thumb) {
		return adjustTone(thumb, 0.09f);
	}

	public static int analogThumbBottom(int thumb) {
		return adjustTone(thumb, -0.12f);
	}

	public static int analogThumbRim(int thumb) {
		return adjustTone(thumb, 0.20f);
	}

	static int analogThumb(ProfileModel profile, boolean active) {
		return analogThumbColor(background(profile, false), background(profile, true), active);
	}

	static int opacity(int configured, boolean editing, boolean forceOpaqueOutsideGuest) {
		if (editing || forceOpaqueOutsideGuest) return 255;
		return Math.max(0, Math.min(255, configured));
	}
}
