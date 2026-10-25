package io.github.h3nb.jlmodplus.config

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Temporary PR195 visual probe. Removed after image inspection. */
@RunWith(AndroidJUnit4::class)
class ColorPickerVisualProbeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun capturePickerInBothDynamicStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val orientation = if (landscape) "landscape" else "portrait"
        rule.setContent {
            JLModPlusTheme {
                ConfigColorPickerDialog(
                    initialHex = "000000",
                    allowDynamic = true,
                    onDismissRequest = {},
                    onConfirm = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
        val sat = rule.onNodeWithContentDescription(
            context.getString(R.string.config_color_picker_saturation_value)).fetchSemanticsNode().boundsInRoot
        val hex = rule.onNodeWithText("000000").fetchSemanticsNode().boundsInRoot
        val option = rule.onNodeWithText(context.getString(R.string.config_background_dynamic))
            .fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText(context.getString(R.string.config_color_picker_hint)).assertExists()
        rule.onNodeWithText(context.getString(R.string.config_background_dynamic_help)).assertExists()
        if (landscape) {
            assertTrue("HEX belongs in the right column", hex.left > sat.right)
            assertTrue("Dynamic option stays in the right column", option.left > sat.right)
        } else {
            assertTrue("HEX follows the HSV field", hex.top > sat.bottom)
            assertTrue("Dynamic option follows HEX", option.top > hex.bottom)
        }
        fun capture(state: String) {
            rule.waitForIdle()
            val shot = instrumentation.uiAutomation.takeScreenshot()
            val folder = File(context.getExternalFilesDir(null), "color-picker-probe")
            assertTrue(folder.exists() || folder.mkdirs())
            FileOutputStream(File(folder, "$" + "{orientation}-$" + "{state}.png")).use { out ->
                assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
            shot.recycle()
        }
        capture("manual")
        rule.onNodeWithText(context.getString(R.string.config_background_dynamic)).performClick()
        rule.onNodeWithText("000000").assertExists()
        rule.onNodeWithText(context.getString(R.string.config_color_picker_hint)).assertExists()
        rule.onNodeWithText(context.getString(R.string.config_background_dynamic_help)).assertExists()
        capture("dynamic")
    }
}
