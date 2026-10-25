package io.github.h3nb.jlmodplus.config

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.h3nb.jlmodplus.R
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    @Test fun dpadDirectionPreviewComposesIndividualAndDiagonalOverlays() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "dpad-preview-${System.nanoTime()}")
        val set = ControlSkinStore.create(folder, "D-pad Preview")
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        try {
            image.eraseColor(Color.GREEN)
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output))
        } finally { image.recycle() }
        for (stem in arrayOf("dpad_base", "dpad_up", "dpad_down", "dpad_right")) {
            ControlSkinStore.importAsset(folder, set.id, stem,
                ByteArrayInputStream(output.toByteArray()), ".png")
        }
        val intent = Intent(context, ControlSkinManagerActivity::class.java)
            .putExtra(ControlSkinManagerActivity.EXTRA_WORKDIR, folder.absolutePath)
        try {
            ActivityScenario.launch<ControlSkinManagerActivity>(intent).use {
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithText(set.name).fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithContentDescription(
                    context.getString(R.string.control_skin_manage_named, set.name)
                ).performClick()
                rule.onNodeWithText(context.getString(R.string.control_skin_edit)).performClick()
                rule.onNodeWithText(context.getString(R.string.control_skin_dpad)).performClick()
                val up = context.getString(R.string.control_skin_preview_direction,
                    context.getString(R.string.control_skin_overlay_up))
                val down = context.getString(R.string.control_skin_preview_direction,
                    context.getString(R.string.control_skin_overlay_down))
                val right = context.getString(R.string.control_skin_preview_direction,
                    context.getString(R.string.control_skin_overlay_right))
                rule.onNodeWithTag("control_skin_preview_dpad_up").assertDoesNotExist()
                rule.onNodeWithContentDescription(up).performClick()
                rule.onNodeWithTag("control_skin_preview_dpad_up").assertExists()
                rule.onNodeWithContentDescription(right).performClick()
                rule.onNodeWithTag("control_skin_preview_dpad_up").assertExists()
                rule.onNodeWithTag("control_skin_preview_dpad_right").assertExists()
                rule.onNodeWithContentDescription(down).performClick()
                rule.onNodeWithTag("control_skin_preview_dpad_up").assertDoesNotExist()
                rule.onNodeWithTag("control_skin_preview_dpad_down").assertExists()
                rule.onNodeWithTag("control_skin_preview_dpad_right").assertExists()
            }
        } finally { folder.deleteRecursively() }
    }

}
