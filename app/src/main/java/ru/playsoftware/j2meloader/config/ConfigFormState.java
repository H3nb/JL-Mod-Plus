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

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * UI-independent representation of the Config form.
 *
 * Text values intentionally stay as text until {@link #applyTo(ProfileModel)} so the next
 * presentation can report validation without changing the persisted ProfileModel format.
 */
public final class ConfigFormState {
	public final String screenWidth;
	public final String screenHeight;
	public final String screenBackground;
	public final String screenScaleRatio;
	public final String screenPadding;
	public final String fpsLimit;
	public final String fontSizeSmall;
	public final String fontSizeMedium;
	public final String fontSizeLarge;
	public final String vkHideDelay;
	public final String vkBackground;
	public final String vkForeground;
	public final String vkSelectedBackground;
	public final String vkSelectedForeground;
	public final String vkOutline;
	public final String systemProperties;

	@Nullable
	public final String screenBackgroundImage;
	@Nullable
	public final String soundBank;
	@Nullable
	public final ShaderInfo shader;

	public final int orientation;
	public final int screenScaleType;
	public final int screenGravity;
	public final int graphicsMode;
	public final int keyCodesLayout;
	public final int vkButtonShape;
	public final int vkAlpha;

	public final boolean screenFilter;
	public final boolean immediateMode;
	public final boolean parallelRedrawScreen;
	public final boolean forceFullscreen;
	public final boolean showFps;
	public final boolean fontApplyDimensions;
	public final boolean fontAA;
	public final boolean showKeyboard;
	public final boolean vkFeedback;
	public final boolean vkForceOpacity;
	public final boolean touchInput;
	public final boolean skipResumeCall;

	private ConfigFormState(Builder builder) {
		screenWidth = builder.screenWidth;
		screenHeight = builder.screenHeight;
		screenBackground = builder.screenBackground;
		screenScaleRatio = builder.screenScaleRatio;
		screenPadding = builder.screenPadding;
		fpsLimit = builder.fpsLimit;
		fontSizeSmall = builder.fontSizeSmall;
		fontSizeMedium = builder.fontSizeMedium;
		fontSizeLarge = builder.fontSizeLarge;
		vkHideDelay = builder.vkHideDelay;
		vkBackground = builder.vkBackground;
		vkForeground = builder.vkForeground;
		vkSelectedBackground = builder.vkSelectedBackground;
		vkSelectedForeground = builder.vkSelectedForeground;
		vkOutline = builder.vkOutline;
		systemProperties = builder.systemProperties;
		screenBackgroundImage = builder.screenBackgroundImage;
		soundBank = builder.soundBank;
		shader = builder.shader;
		orientation = builder.orientation;
		screenScaleType = builder.screenScaleType;
		screenGravity = builder.screenGravity;
		graphicsMode = builder.graphicsMode;
		keyCodesLayout = builder.keyCodesLayout;
		vkButtonShape = builder.vkButtonShape;
		vkAlpha = builder.vkAlpha;
		screenFilter = builder.screenFilter;
		immediateMode = builder.immediateMode;
		parallelRedrawScreen = builder.parallelRedrawScreen;
		forceFullscreen = builder.forceFullscreen;
		showFps = builder.showFps;
		fontApplyDimensions = builder.fontApplyDimensions;
		fontAA = builder.fontAA;
		showKeyboard = builder.showKeyboard;
		vkFeedback = builder.vkFeedback;
		vkForceOpacity = builder.vkForceOpacity;
		touchInput = builder.touchInput;
		skipResumeCall = builder.skipResumeCall;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static ConfigFormState fromProfile(ProfileModel params, String systemProperties) {
		return builder()
				.screenWidth(optionalInt(params.screenWidth))
				.screenHeight(optionalInt(params.screenHeight))
				.screenBackground(formatColor(params.screenBackgroundColor))
				.screenBackgroundImage(params.screenBackgroundImage)
				.screenScaleRatio(Integer.toString(params.screenScaleRatio))
				.orientation(params.orientation)
				.screenScaleType(params.screenScaleType)
				.screenGravity(params.screenGravity)
				.screenPadding(Integer.toString(params.screenPadding))
				.screenFilter(params.screenFilter)
				.immediateMode(params.immediateMode)
				.parallelRedrawScreen(params.parallelRedrawScreen)
				.forceFullscreen(params.forceFullscreen)
				.graphicsMode(params.graphicsMode)
				.shader(params.shader)
				.showFps(params.showFps)
				.fpsLimit(optionalInt(params.fpsLimit))
				.fontSizeSmall(Integer.toString(params.fontSizeSmall))
				.fontSizeMedium(Integer.toString(params.fontSizeMedium))
				.fontSizeLarge(Integer.toString(params.fontSizeLarge))
				.fontApplyDimensions(params.fontApplyDimensions)
				.fontAA(params.fontAA)
				.showKeyboard(params.showKeyboard)
				.vkFeedback(params.vkFeedback)
				.vkForceOpacity(params.vkForceOpacity)
				.touchInput(params.touchInput)
				.keyCodesLayout(params.keyCodesLayout)
				.vkButtonShape(params.vkButtonShape)
				.vkAlpha(params.vkAlpha)
				.vkHideDelay(optionalInt(params.vkHideDelay))
				.vkBackground(formatColor(params.vkBgColor))
				.vkForeground(formatColor(params.vkFgColor))
				.vkSelectedBackground(formatColor(params.vkBgColorSelected))
				.vkSelectedForeground(formatColor(params.vkFgColorSelected))
				.vkOutline(formatColor(params.vkOutlineColor))
				.skipResumeCall(params.skipResumeCall)
				.soundBank(params.soundBank)
				.systemProperties(systemProperties)
				.build();
	}

	/** Applies the legacy widget parsing/default rules to the existing model instance. */
	public ProfileModel applyTo(ProfileModel params) {
		params.screenWidth = parseInt(screenWidth, 0);
		params.screenHeight = parseInt(screenHeight, 0);
		params.screenBackgroundColor = parseHexOrKeep(screenBackground, params.screenBackgroundColor);
		params.screenBackgroundImage = screenBackgroundImage;
		params.screenScaleRatio = parseInt(screenScaleRatio, 100);
		params.orientation = orientation;
		params.screenGravity = screenGravity;
		params.screenPadding = parseInt(screenPadding, 0);
		params.screenScaleType = screenScaleType;
		params.screenFilter = screenFilter;
		params.immediateMode = immediateMode;
		params.graphicsMode = graphicsMode;
		if (graphicsMode == 1) {
			params.shader = shader;
		}
		params.parallelRedrawScreen = parallelRedrawScreen;
		params.forceFullscreen = forceFullscreen;
		params.showFps = showFps;
		params.fpsLimit = parseInt(fpsLimit, 0);

		params.fontSizeSmall = parseInt(fontSizeSmall, 0);
		params.fontSizeMedium = parseInt(fontSizeMedium, 0);
		params.fontSizeLarge = parseInt(fontSizeLarge, 0);
		params.fontApplyDimensions = fontApplyDimensions;
		params.fontAA = fontAA;
		params.showKeyboard = showKeyboard;
		params.vkFeedback = vkFeedback;
		params.vkForceOpacity = vkForceOpacity;
		params.touchInput = touchInput;

		params.keyCodesLayout = keyCodesLayout;
		params.vkButtonShape = vkButtonShape;
		params.vkAlpha = vkAlpha;
		params.vkHideDelay = parseInt(vkHideDelay, 0);
		params.vkBgColor = parseHexOrKeep(vkBackground, params.vkBgColor);
		params.vkFgColor = parseHexOrKeep(vkForeground, params.vkFgColor);
		params.vkBgColorSelected = parseHexOrKeep(vkSelectedBackground, params.vkBgColorSelected);
		params.vkFgColorSelected = parseHexOrKeep(vkSelectedForeground, params.vkFgColorSelected);
		params.vkOutlineColor = parseHexOrKeep(vkOutline, params.vkOutlineColor);
		params.skipResumeCall = skipResumeCall;
		params.soundBank = soundBank;
		params.systemProperties = normalizeSystemProperties(systemProperties);
		return params;
	}

	public static String normalizeSystemProperties(String text) {
		if (text == null) {
			return "";
		}
		String[] lines = text.split("[\\r\\n]+");
		List<String> list = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		for (int i = lines.length - 1; i >= 0; i--) {
			String line = lines[i];
			int colon = line.indexOf(':');
			if (colon != -1 && keys.add(line.substring(0, colon).trim())) {
				list.add(line);
			}
		}
		Collections.sort(list);
		StringBuilder result = new StringBuilder();
		for (String line : list) {
			result.append(line).append('\n');
		}
		return result.toString();
	}

	private static int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException | NullPointerException ignored) {
			return fallback;
		}
	}

	private static int parseHexOrKeep(String value, int previous) {
		try {
			return Integer.parseInt(value, 16);
		} catch (NumberFormatException | NullPointerException ignored) {
			return previous;
		}
	}

	private static String optionalInt(int value) {
		return value > 0 ? Integer.toString(value) : "";
	}

	private static String formatColor(int color) {
		return String.format("%06X", color);
	}

	public static final class Builder {
		private String screenWidth = "";
		private String screenHeight = "";
		private String screenBackground = "";
		private String screenScaleRatio = "";
		private String screenPadding = "";
		private String fpsLimit = "";
		private String fontSizeSmall = "";
		private String fontSizeMedium = "";
		private String fontSizeLarge = "";
		private String vkHideDelay = "";
		private String vkBackground = "";
		private String vkForeground = "";
		private String vkSelectedBackground = "";
		private String vkSelectedForeground = "";
		private String vkOutline = "";
		private String systemProperties = "";
		private String screenBackgroundImage;
		private String soundBank;
		private ShaderInfo shader;
		private int orientation;
		private int screenScaleType;
		private int screenGravity;
		private int graphicsMode;
		private int keyCodesLayout;
		private int vkButtonShape;
		private int vkAlpha;
		private boolean screenFilter;
		private boolean immediateMode;
		private boolean parallelRedrawScreen;
		private boolean forceFullscreen;
		private boolean showFps;
		private boolean fontApplyDimensions;
		private boolean fontAA;
		private boolean showKeyboard;
		private boolean vkFeedback;
		private boolean vkForceOpacity;
		private boolean touchInput;
		private boolean skipResumeCall;

		public Builder screenWidth(String value) { screenWidth = value; return this; }
		public Builder screenHeight(String value) { screenHeight = value; return this; }
		public Builder screenBackground(String value) { screenBackground = value; return this; }
		public Builder screenScaleRatio(String value) { screenScaleRatio = value; return this; }
		public Builder screenPadding(String value) { screenPadding = value; return this; }
		public Builder fpsLimit(String value) { fpsLimit = value; return this; }
		public Builder fontSizeSmall(String value) { fontSizeSmall = value; return this; }
		public Builder fontSizeMedium(String value) { fontSizeMedium = value; return this; }
		public Builder fontSizeLarge(String value) { fontSizeLarge = value; return this; }
		public Builder vkHideDelay(String value) { vkHideDelay = value; return this; }
		public Builder vkBackground(String value) { vkBackground = value; return this; }
		public Builder vkForeground(String value) { vkForeground = value; return this; }
		public Builder vkSelectedBackground(String value) { vkSelectedBackground = value; return this; }
		public Builder vkSelectedForeground(String value) { vkSelectedForeground = value; return this; }
		public Builder vkOutline(String value) { vkOutline = value; return this; }
		public Builder systemProperties(String value) { systemProperties = value; return this; }
		public Builder screenBackgroundImage(String value) { screenBackgroundImage = value; return this; }
		public Builder soundBank(String value) { soundBank = value; return this; }
		public Builder shader(ShaderInfo value) { shader = value; return this; }
		public Builder orientation(int value) { orientation = value; return this; }
		public Builder screenScaleType(int value) { screenScaleType = value; return this; }
		public Builder screenGravity(int value) { screenGravity = value; return this; }
		public Builder graphicsMode(int value) { graphicsMode = value; return this; }
		public Builder keyCodesLayout(int value) { keyCodesLayout = value; return this; }
		public Builder vkButtonShape(int value) { vkButtonShape = value; return this; }
		public Builder vkAlpha(int value) { vkAlpha = value; return this; }
		public Builder screenFilter(boolean value) { screenFilter = value; return this; }
		public Builder immediateMode(boolean value) { immediateMode = value; return this; }
		public Builder parallelRedrawScreen(boolean value) { parallelRedrawScreen = value; return this; }
		public Builder forceFullscreen(boolean value) { forceFullscreen = value; return this; }
		public Builder showFps(boolean value) { showFps = value; return this; }
		public Builder fontApplyDimensions(boolean value) { fontApplyDimensions = value; return this; }
		public Builder fontAA(boolean value) { fontAA = value; return this; }
		public Builder showKeyboard(boolean value) { showKeyboard = value; return this; }
		public Builder vkFeedback(boolean value) { vkFeedback = value; return this; }
		public Builder vkForceOpacity(boolean value) { vkForceOpacity = value; return this; }
		public Builder touchInput(boolean value) { touchInput = value; return this; }
		public Builder skipResumeCall(boolean value) { skipResumeCall = value; return this; }

		public ConfigFormState build() {
			return new ConfigFormState(this);
		}
	}
}
