/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.config

import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.model.Size
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Temporary physical-emulator qualification: deliberately driven with no-cutout/notch overlays. */
@RunWith(AndroidJUnit4::class)
class TemporaryCutoutQualificationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun realWindowInsetsControlSettingVisibilityAndOptOut() {
        val args = InstrumentationRegistry.getArguments()
        val expectedCutout = when (args.getString("cutoutMode")) {
            "none" -> false
            "notch" -> true
            else -> error("Explicit cutoutMode=none or notch is required")
        }
        val expectedOrientation = when (args.getString("orientation")) {
            "portrait" -> Configuration.ORIENTATION_PORTRAIT
            "landscape" -> Configuration.ORIENTATION_LANDSCAPE
            else -> error("Explicit orientation=portrait or landscape is required")
        }
        val activity = rule.activity
        rule.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attributes = activity.window.attributes
                attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                activity.window.attributes = attributes
            }
            ViewCompat.requestApplyInsets(activity.window.decorView)
        }
        rule.waitUntil(30_000) {
            activity.resources.configuration.orientation == expectedOrientation &&
                ViewCompat.getRootWindowInsets(activity.window.decorView) != null
        }
        val actualCutout = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.displayCutout?.boundingRects?.any { !it.isEmpty } == true
        assertEquals("Android window reports the overlay cutout geometry", expectedCutout, actualCutout)

        val initial = ConfigFormState.builder()
            .screenWidth("240").screenHeight("320")
            .screenBackground("D0D0D0")
            .screenScaleType(1).screenScaleRatio("100")
            .screenPadding("0")
            .fontSizeSmall("18").fontSizeMedium("22").fontSizeLarge("26")
            .systemProperties("microedition.platform: test\\n")
            .showKeyboard(true).touchInput(true)
            .build()
        val form = mutableStateOf(initial)
        val events = object : ConfigFormEvents {
            override fun onFormChanged(state: ConfigFormState) { form.value = state }
            override fun onAddResolutionPreset(size: Size) = Unit
            override fun onRemoveResolutionPreset(size: Size) = Unit
            override fun onColorPicker(field: ConfigFormEvents.ColorField) = Unit
            override fun onColorPicked(field: ConfigFormEvents.ColorField, value: String) = Unit
            override fun onKeyMappings() = Unit
            override fun onEncodingPicker() = Unit
            override fun onShaderTuning() = Unit
        }
        rule.setContent {
            JLModPlusTheme {
                ConfigScreen(
                    state = ConfigUiState(
                        form.value, listOf(Size(240, 320)), emptyList(), emptyList(),
                        emptyList(), emptyList(), emptyList(),
                    ),
                    events = events,
                )
            }
        }
        val name = activity.getString(R.string.config_avoid_camera_cutout_title)
        rule.waitForIdle()
        if (expectedCutout) {
            val setting = rule.onNodeWithText(name)
            setting.assertExists()
            assertTrue(initial.allowDisplayCutout)
            setting.performScrollTo().performClick()
            rule.runOnIdle {
                assertFalse("Opt-out must persist in the draft", form.value.allowDisplayCutout)
                assertEquals("240", form.value.screenWidth)
            }
            rule.onNodeWithText(name).performScrollTo().performClick()
            rule.runOnIdle { assertTrue("Opt-in must restore the prior cutout choice", form.value.allowDisplayCutout) }
        } else {
            rule.onNodeWithText(name).assertDoesNotExist()
            rule.runOnIdle { assertTrue("Hidden preference must not change the stored value", form.value.allowDisplayCutout) }
        }
    }
}
