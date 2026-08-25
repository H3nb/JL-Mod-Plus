/*
 * Copyright 2020-2026 Yury Kharchenko
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

package ru.playsoftware.j2meloader.config;

import static ru.playsoftware.j2meloader.util.Constants.PREF_THEME;

import android.content.Context;
import android.content.res.Configuration;
import android.util.SparseIntArray;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.util.List;

import javax.microedition.lcdui.keyboard.KeyModel;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.util.ContextHolder;

import androidx.preference.PreferenceManager;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.util.SparseIntArrayAdapter;
import javax.microedition.shell.timing.EmulationSpeed;
import javax.microedition.shell.timing.TimingMode;

public class ProfileModel {
	public static final int VERSION = 5;

	/** Stable preference key used to keep the built-in palette linked to the host theme. */
	public static String builtInThemePreferenceKey(File configDir) {
		return "config_profile_builtin_theme:" + configDir.getAbsolutePath();
	}
	/** True if this is a new profile (not yet saved to file) */
	public final transient boolean isNew;

	public transient File dir;

	@SerializedName("Version")
	public int version;

	@SerializedName("ScreenWidth")
	public int screenWidth;

	@SerializedName("ScreenHeight")
	public int screenHeight;

	@SerializedName("ScreenBackgroundColor")
	public int screenBackgroundColor;

	@SerializedName("ScreenBackgroundImage")
	public String screenBackgroundImage;

	@SerializedName("ScreenScaleRatio")
	public int screenScaleRatio;

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
	public ShaderInfo shader;

	@SerializedName("ParallelRedrawScreen")
	public boolean parallelRedrawScreen;

	@SerializedName("ShowFps")
	public boolean showFps;

	@SerializedName("EmulationSpeedPercent")
	public int emulationSpeedPercent = EmulationSpeed.NORMAL_PERCENT;

	@SerializedName("ShowEmulationSpeed")
	public boolean showEmulationSpeed;

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
	public List<KeyModel> customKeys;

	@JsonAdapter(SparseIntArrayAdapter.class)
	@SerializedName("KeyMappings")
	public SparseIntArray keyMappings;

	@SerializedName("SoundBank")
	public String soundBank;

	@SerializedName("SystemProperties")
	public String systemProperties;

	@SerializedName("SkipResumeCall")
	public boolean skipResumeCall;

	@SuppressWarnings("unused") // Gson uses default constructor if present
	public ProfileModel() {
		isNew = false;
		emulationSpeedPercent = EmulationSpeed.NORMAL_PERCENT;
		showEmulationSpeed = false;
		timingMode = TimingMode.FULL_GUEST_TIME;
	}

	public ProfileModel(File dir) {
		this.dir = dir;
		isNew = true;
		version = VERSION;
		screenWidth = 240;
		screenHeight = 320;
		screenBackgroundColor = 0xD0D0D0;
		screenScaleType = 1;
		screenGravity = 1;
		screenScaleRatio = 100;
		screenScaleToFit = true;
		screenKeepAspectRatio = true;
		graphicsMode = 1;

		fontSizeSmall = 18;
		fontSizeMedium = 22;
		fontSizeLarge = 26;
		fontAA = true;

		showKeyboard = true;
		touchInput = true;

		vkButtonShape = VirtualKeyboard.SHAPE_ROUND_RECT;
		vkAlpha = 64;

		vkBgColor = 0xD0D0D0;
		vkFgColor = 0x000080;
		vkBgColorSelected = 0x000080;
		vkFgColorSelected = 0xFFFFFF;
		vkOutlineColor = 0xFFFFFF;
		systemProperties = ContextHolder.getAssetAsString("defaults/system.props");
	}

	/** Applies the theme-owned colors used by the app-provided profile template. */
	public static void applyBuiltInTheme(ProfileModel profile, boolean darkTheme) {
		int background = darkTheme ? 0x000000 : 0xFFFFFF;
		int foreground = darkTheme ? 0xFFFFFF : 0x000000;
		profile.screenBackgroundColor = background;
		profile.vkAlpha = 255;
		profile.vkBgColor = background;
		profile.vkFgColor = foreground;
		profile.vkBgColorSelected = foreground;
		profile.vkFgColorSelected = background;
		profile.vkOutlineColor = foreground;
	}

	/**
	 * Resolves the active app theme for both activities and the MIDlet runtime. AppCompat applies
	 * an explicit light/dark preference to activity resources, but the application context used by
	 * the MIDlet loader can still expose the device's original uiMode. Reading the explicit
	 * preference first keeps linked built-in profiles in sync even when the device theme differs.
	 */
	public static boolean isDarkTheme(Context context) {
		String preference = PreferenceManager.getDefaultSharedPreferences(context)
				.getString(PREF_THEME, context.getString(R.string.pref_theme_default));
		return isDarkTheme(preference, context.getResources().getConfiguration().uiMode);
	}

	/** Visible to JVM tests so explicit preference precedence stays regression-tested. */
	static boolean isDarkTheme(String preference, int uiMode) {
		if ("dark".equals(preference)) return true;
		if ("light".equals(preference)) return false;
		int nightMask = uiMode & Configuration.UI_MODE_NIGHT_MASK;
		return nightMask == Configuration.UI_MODE_NIGHT_YES;
	}

	/**
	 * Creates the app-provided profile template using the active host theme. Existing saved
	 * named profiles continue to load their own explicit colors.
	 */
	public static ProfileModel createBuiltIn(File dir, boolean darkTheme) {
		ProfileModel profile = new ProfileModel(dir);
		applyBuiltInTheme(profile, darkTheme);
		return profile;
	}
}
