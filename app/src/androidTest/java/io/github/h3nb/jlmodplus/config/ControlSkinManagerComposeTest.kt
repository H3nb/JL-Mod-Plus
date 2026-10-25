package io.github.h3nb.jlmodplus.config

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.h3nb.jlmodplus.R
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlSkinManagerComposeTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun portraitGalleryAndLandscapeDetailStayUsable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "skin-ui-${System.nanoTime()}")
        val set = ControlSkinStore.create(folder, "Responsive skin")
        val intent = Intent(context, ControlSkinManagerActivity::class.java)
            .putExtra(ControlSkinManagerActivity.EXTRA_WORKDIR, folder.absolutePath)

        try {
            ActivityScenario.launch<ControlSkinManagerActivity>(intent).use { scenario ->
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithText(set.name).fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithContentDescription(
                    context.getString(R.string.control_skin_manage_named, set.name)
                ).performClick()
                rule.onNodeWithText(context.getString(R.string.control_skin_edit)).performClick()
                val movement = context.getString(R.string.control_skin_movement)
                val keypad = context.getString(R.string.control_skin_numeric)
                val navigation = context.getString(R.string.control_skin_navigation)
                val dpad = context.getString(R.string.control_skin_dpad)
                rule.onNodeWithText(movement).assertIsDisplayed()
                rule.onNodeWithText(dpad).assertIsDisplayed()
                rule.onNodeWithText(keypad).performScrollTo().assertIsDisplayed()

                // On compact portrait screens movement controls precede the keypad.
                val movementY = rule.onNodeWithText(movement).getUnclippedBoundsInRoot().top
                val keypadY = rule.onNodeWithText(keypad).getUnclippedBoundsInRoot().top
                assertTrue(movementY < keypadY)

                scenario.onActivity {
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithText(navigation).fetchSemanticsNodes().isNotEmpty()
                }
                rule.waitForIdle()
                val keypadX = rule.onNodeWithText(keypad).getUnclippedBoundsInRoot().left
                val navigationX = rule.onNodeWithText(navigation).getUnclippedBoundsInRoot().left
                assertTrue("Wide windows should show adjacent control groups", navigationX > keypadX)

                rule.onNodeWithText(dpad).performScrollTo().performClick()
                rule.onNodeWithText(context.getString(R.string.control_skin_overlay_right))
                    .performScrollTo().assertIsDisplayed()
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}
