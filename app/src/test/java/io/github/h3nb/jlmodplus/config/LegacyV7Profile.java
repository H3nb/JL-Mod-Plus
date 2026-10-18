/*
 * Copyright 2020-2026 Yury Kharchenko
 * Modified for JL-Mod Plus.
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

package io.github.h3nb.jlmodplus.config;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import javax.microedition.shell.timing.TimingMode;

/** Frozen pre-migration v7 schema, used only to read the new writer with the old engine. */
final class LegacyV7Profile {
	@SerializedName("Version")
	public int version;

	@SerializedName("ScreenWidth")
	public int screenWidth;

	@SerializedName("ScreenHeight")
	public int screenHeight;

	@SerializedName("ScreenBackgroundColor")
	public int screenBackgroundColor;

	/** Stable background strategy; the custom color remains stored while other modes are active. */
	@SerializedName("ScreenBackgroundMode")
	public int screenBackgroundMode = BackgroundMode.CUSTOM;

	@SerializedName("ScreenBackgroundImage")
	public String screenBackgroundImage;

	@SerializedName("ScreenScaleRatio")
	public int screenScaleRatio;

	public static int effectiveScreenScaleRatio(int scaleType, int ratio) {
		if (scaleType != 0 || ratio <= 0) return 100;
		return Math.min(ratio, 1000);
	}

	@SerializedName("Orientation")
	public int orientation;

	@SerializedName("ScreenScaleToFit")
	public boolean screenScaleToFit;

	@SerializedName("ScreenKeepAspectRatio")
	public boolean screenKeepAspectRatio;

	@SerializedName("ScreenScaleType")
	public int screenScaleType;

	@SerializedName("ScreenGravity")
	public int screenGravity;

	@SerializedName("ScreenPadding")
	public int screenPadding;

	@SerializedName("ScreenFilter")
	public boolean screenFilter;

	@SerializedName("ImmediateMode")
	public boolean immediateMode;

	@SerializedName("HwAcceleration")
	public boolean hwAcceleration;

	@SerializedName("GraphicsMode")
	public int graphicsMode;

	@SerializedName("Shader")
	public LegacyShader shader;

	@SerializedName("ParallelRedrawScreen")
	public boolean parallelRedrawScreen;

	@SerializedName("ShowFps")
	public boolean showFps;

	@SerializedName("PerformanceOverlayMetrics")
	public int performanceOverlayMetrics = PerformanceOverlayOptions.STANDARD;

	@SerializedName("PerformanceOverlayPosition")
	public int performanceOverlayPosition = PerformanceOverlayOptions.TOP_LEFT;

	@SerializedName("TimingMode")
	public int timingMode = TimingMode.FULL_GUEST_TIME;

	@SerializedName("FpsLimit")
	public int fpsLimit;

	@SerializedName("ForceFullscreen")
	public boolean forceFullscreen;

	@SerializedName("FontSizeSmall")
	public int fontSizeSmall;

	@SerializedName("FontSizeMedium")
	public int fontSizeMedium;

	@SerializedName("FontSizeLarge")
	public int fontSizeLarge;

	@SerializedName("FontApplyDimensions")
	public boolean fontApplyDimensions;

	@SerializedName("FontAntiAlias")
	public boolean fontAA;

	@SerializedName("TouchInput")
	public boolean touchInput;

	@SerializedName("ShowKeyboard")
	public boolean showKeyboard;

	/** Enables the grouped touch D-pad portion of the virtual controls overlay. */
	@SerializedName("VirtualDpadEnabled")
	public boolean virtualDpadEnabled = false;

	/** Enables the grouped touch analog-stick portion of the virtual controls overlay. */
	@SerializedName("VirtualAnalogEnabled")
	public boolean virtualAnalogEnabled = false;

	/** Normalized grouped-control geometry. Values are relative to the guest control viewport. */
	@SerializedName("VirtualDpadCenterX")
	public float virtualDpadCenterX = 0.82f;

	@SerializedName("VirtualDpadCenterY")
	public float virtualDpadCenterY = 0.78f;

	@SerializedName("VirtualDpadRadius")
	public float virtualDpadRadius = 0.16f;

	@SerializedName("VirtualAnalogCenterX")
	public float virtualAnalogCenterX = 0.18f;

	@SerializedName("VirtualAnalogCenterY")
	public float virtualAnalogCenterY = 0.78f;

	@SerializedName("VirtualAnalogRadius")
	public float virtualAnalogRadius = 0.16f;

	@SerializedName("VirtualKeyboardType")
	public int vkType;

	@SerializedName("ButtonShape")
	public int vkButtonShape;

	@SerializedName("VirtualKeyboardAlpha")
	public int vkAlpha;

	@SerializedName("VirtualKeyboardForceOpacity")
	public boolean vkForceOpacity;

	@SerializedName("VirtualKeyboardFeedback")
	public boolean vkFeedback;

	@SerializedName("VirtualKeyboardDelay")
	public int vkHideDelay;

	@SerializedName("VirtualKeyboardColorBackground")
	public int vkBgColor;

	@SerializedName("VirtualKeyboardColorBackgroundSelected")
	public int vkBgColorSelected;

	@SerializedName("VirtualKeyboardColorForeground")
	public int vkFgColor;

	@SerializedName("VirtualKeyboardColorForegroundSelected")
	public int vkFgColorSelected;

	@SerializedName("VirtualKeyboardColorOutline")
	public int vkOutlineColor;

	@SerializedName("Layout")
	public int keyCodesLayout;

	@SerializedName("CustomKeys")
	public List<LegacyKey> customKeys;

	@SerializedName("KeyMappings")
	public java.util.Map<Integer, Integer> keyMappings;

	/**
	 * Optional per-profile controller configuration. The subtree is deliberately kept as JSON so
	 * a newer controller schema can round-trip through older settings editors without losing
	 * fields that this build does not understand.
	 */
	@SerializedName(value = "Controller", alternate = {"controller"})
	public JsonElement controller;

	@SerializedName("SoundBank")
	public String soundBank;

	@SerializedName("SystemProperties")
	public String systemProperties;

	@SerializedName("SkipResumeCall")
	public boolean skipResumeCall;


    static final class LegacyShader {
        String fragment;
        String vertex;
        String name;
        String author;
        @SerializedName("Settings") float[] values;
    }
    static final class LegacyKey {
        @SerializedName("DefaultKeyCode") int defaultKeyCode;
        @SerializedName("CustomKeyCode") int customKeyCode;
        @SerializedName("GameAction") int gameAction;
        @SerializedName("KeyName") String keyName;
    }
}
