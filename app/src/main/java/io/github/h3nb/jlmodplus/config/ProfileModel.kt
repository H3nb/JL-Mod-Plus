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

package io.github.h3nb.jlmodplus.config

import android.content.Context
import android.content.res.Configuration
import androidx.preference.PreferenceManager
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.mmapi.camera.CameraCaptureOptions
import io.github.h3nb.jlmodplus.ui.AppBackgroundColors
import io.github.h3nb.jlmodplus.util.Constants.PREF_THEME
import java.io.File
import javax.microedition.lcdui.keyboard.VirtualKeyboard
import javax.microedition.shell.timing.TimingMode
import javax.microedition.util.ContextHolder
import kotlinx.serialization.Transient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Persistable values only. Storage identity and publication belong to the caller. */
@Serializable
data class ProfileModel(
    @SerialName("Version") @JvmField var version: Int = 0,
    @SerialName("ScreenWidth") @JvmField var screenWidth: Int = 0,
    @SerialName("ScreenHeight") @JvmField var screenHeight: Int = 0,
    @SerialName("ScreenBackgroundColor") @JvmField var screenBackgroundColor: Int = 0,
    @SerialName("ScreenBackgroundMode") @JvmField var screenBackgroundMode: Int = BackgroundMode.CUSTOM,
    @SerialName("ScreenBackgroundImage") @JvmField var screenBackgroundImage: String? = null,
    @SerialName("ScreenScaleRatio") @JvmField var screenScaleRatio: Int = 0,
    @SerialName("Orientation") @JvmField var orientation: Int = 0,
    @SerialName("ScreenScaleType") @JvmField var screenScaleType: Int = 0,
    @SerialName("ScreenGravity") @JvmField var screenGravity: Int = 0,
    @SerialName("ScreenPadding") @JvmField var screenPadding: Int = 0,
    @SerialName("ScreenFilter") @JvmField var screenFilter: Boolean = false,
    @SerialName("ImmediateMode") @JvmField var immediateMode: Boolean = false,
    @SerialName("GraphicsMode") @JvmField var graphicsMode: Int = 0,
    @SerialName("Shader") @JvmField var shader: ShaderSelection? = null,
    @SerialName("ParallelRedrawScreen") @JvmField var parallelRedrawScreen: Boolean = false,
    @SerialName("ShowFps") @JvmField var showFps: Boolean = false,
    @SerialName("PerformanceOverlayMetrics") @JvmField var performanceOverlayMetrics: Int = PerformanceOverlayOptions.STANDARD,
    @SerialName("PerformanceOverlayPosition") @JvmField var performanceOverlayPosition: Int = PerformanceOverlayOptions.TOP_LEFT,
    @SerialName("TimingMode") @JvmField var timingMode: Int = TimingMode.FULL_GUEST_TIME,
    @SerialName("FpsLimit") @JvmField var fpsLimit: Int = 0,
    @SerialName("ForceFullscreen") @JvmField var forceFullscreen: Boolean = false,
    @SerialName("AllowDisplayCutout") @JvmField var allowDisplayCutout: Boolean = true,
    @SerialName("FontSizeSmall") @JvmField var fontSizeSmall: Int = 0,
    @SerialName("FontSizeMedium") @JvmField var fontSizeMedium: Int = 0,
    @SerialName("FontSizeLarge") @JvmField var fontSizeLarge: Int = 0,
    @SerialName("FontApplyDimensions") @JvmField var fontApplyDimensions: Boolean = false,
    @SerialName("FontAntiAlias") @JvmField var fontAA: Boolean = false,
    @SerialName("TouchInput") @JvmField var touchInput: Boolean = false,
    @SerialName("ShowKeyboard") @JvmField var showKeyboard: Boolean = false,
    @SerialName("VirtualDpadEnabled") @JvmField var virtualDpadEnabled: Boolean = false,
    @SerialName("VirtualAnalogEnabled") @JvmField var virtualAnalogEnabled: Boolean = false,
    @SerialName("VirtualDpadCenterX") @JvmField var virtualDpadCenterX: Float = 0.82f,
    @SerialName("VirtualDpadCenterY") @JvmField var virtualDpadCenterY: Float = 0.78f,
    @SerialName("VirtualDpadRadius") @JvmField var virtualDpadRadius: Float = 0.16f,
    @SerialName("VirtualAnalogCenterX") @JvmField var virtualAnalogCenterX: Float = 0.18f,
    @SerialName("VirtualAnalogCenterY") @JvmField var virtualAnalogCenterY: Float = 0.78f,
    @SerialName("VirtualAnalogRadius") @JvmField var virtualAnalogRadius: Float = 0.16f,
    @SerialName("VirtualKeyboardType") @JvmField var vkType: Int = 0,
    @SerialName("ButtonShape") @JvmField var vkButtonShape: Int = 0,
    @SerialName("VirtualKeyboardAlpha") @JvmField var vkAlpha: Int = 0,
    @SerialName("VirtualKeyboardForceOpacity") @JvmField var vkForceOpacity: Boolean = false,
    @SerialName("VirtualKeyboardFeedback") @JvmField var vkFeedback: Boolean = false,
    @SerialName("VirtualKeyboardDelay") @JvmField var vkHideDelay: Int = 0,
    @SerialName("ControlSkinSetId") @JvmField var controlSkinSetId: String? = null,
    @SerialName("VirtualKeyboardColorBackground") @JvmField var vkBgColor: Int = 0,
    @SerialName("VirtualKeyboardColorBackgroundSelected") @JvmField var vkBgColorSelected: Int = 0,
    @SerialName("VirtualKeyboardColorForeground") @JvmField var vkFgColor: Int = 0,
    @SerialName("VirtualKeyboardColorForegroundSelected") @JvmField var vkFgColorSelected: Int = 0,
    @SerialName("VirtualKeyboardColorOutline") @JvmField var vkOutlineColor: Int = 0,
    @SerialName("Layout") @JvmField var keyCodesLayout: Int = 0,
    @SerialName("CustomKeys") @JvmField var customKeys: List<CustomKey>? = null,
    @SerialName("KeyMappings") @JvmField var keyMappings: Map<Int, Int> = emptyMap(),
    @SerialName("Controller") @JvmField var controller: JsonElement? = null,
    @SerialName("SoundBank") @JvmField var soundBank: String? = null,
    @SerialName("CameraDefaultFacing") @JvmField var cameraDefaultFacing: Int = CameraCaptureOptions.BACK,
    @SerialName("CameraResolution") @JvmField var cameraResolution: Int = CameraCaptureOptions.RESOLUTION_AUTO,
    @Transient @JvmField var systemProperties: String? = null,
    @SerialName("SystemPropertyOverrides") @JvmField var systemPropertyOverrides: Map<String, String?> = emptyMap(),
    @Transient @JvmField var systemPropertyDefaults: String? = null,
    @SerialName("SkipResumeCall") @JvmField var skipResumeCall: Boolean = false,
) {
    companion object {
        const val VERSION = 9

        @JvmStatic fun builtInThemePreferenceKey(configDir: File): String =
            "config_profile_builtin_theme:" + configDir.absolutePath

        @JvmStatic fun effectiveScreenScaleRatio(scaleType: Int, ratio: Int): Int =
            if (scaleType != 0 || ratio <= 0) 100 else minOf(ratio, 1000)

        @JvmStatic fun fresh(): ProfileModel = fresh(ContextHolder.getAssetAsString("defaults/system.props"))

        @JvmStatic fun fresh(defaultSystemProperties: String): ProfileModel = ProfileModel(
            version = VERSION, screenWidth = 240, screenHeight = 320,
            screenBackgroundColor = 0xD0D0D0, screenScaleType = 1,
            screenGravity = 1, screenScaleRatio = 100, graphicsMode = 1,
            fontSizeSmall = 18, fontSizeMedium = 22, fontSizeLarge = 26, fontAA = true,
            showKeyboard = true, touchInput = true,
            vkButtonShape = VirtualKeyboard.SHAPE_ROUND_RECT, vkAlpha = 64,
            vkBgColor = 0xD0D0D0, vkFgColor = 0x000080,
            vkBgColorSelected = 0x000080, vkFgColorSelected = 0xFFFFFF,
            vkOutlineColor = 0xFFFFFF,
            systemProperties = SystemPropertyLayers.effective(emptyMap(), defaultSystemProperties),
            systemPropertyDefaults = defaultSystemProperties,
        )

        @JvmStatic fun applyBuiltInTheme(profile: ProfileModel, darkTheme: Boolean) {
            val background = if (darkTheme) 0x000000 else 0xFFFFFF
            val foreground = if (darkTheme) 0xFFFFFF else 0x000000
            profile.vkAlpha = 255
            profile.vkBgColor = background
            profile.vkFgColor = foreground
            profile.vkBgColorSelected = foreground
            profile.vkFgColorSelected = background
            profile.vkOutlineColor = foreground
        }

        @JvmStatic fun isDarkTheme(context: Context): Boolean = isDarkTheme(
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_THEME, context.getString(R.string.pref_theme_default)),
            context.resources.configuration.uiMode,
        )

        @JvmStatic fun isDarkTheme(preference: String?, uiMode: Int): Boolean = when (preference) {
            "dark" -> true
            "light" -> false
            else -> uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }

        @JvmStatic fun createBuiltIn(darkTheme: Boolean): ProfileModel = createBuiltIn(
            darkTheme, ContextHolder.getAssetAsString("defaults/system.props"),
        )

        @JvmStatic fun createBuiltIn(darkTheme: Boolean, defaultSystemProperties: String): ProfileModel =
            fresh(defaultSystemProperties).apply {
                vkType = VirtualKeyboard.TYPE_NUMBERS_ARROWS
                screenBackgroundMode = BackgroundMode.THEME
                screenBackgroundColor = AppBackgroundColors.rgb(darkTheme)
                applyBuiltInTheme(this, darkTheme)
            }
    }
}
