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

package io.github.h3nb.jlmodplus.applist

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual rendered artwork must follow an icon replacement while its item stays composed. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LibraryIconRefreshTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var iconDirectory: File

    @Before
    fun createIconDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        iconDirectory = File(context.cacheDir, "library-icon-refresh-${System.nanoTime()}")
        check(iconDirectory.mkdirs())
    }

    @After
    fun removeIconDirectory() {
        if (::iconDirectory.isInitialized) iconDirectory.deleteRecursively()
    }

    @Test
    fun iconReplacementUpdatesArtworkAndCanReturnToCachedSource() {
        val original = File(iconDirectory, "original.png")
        val replacement = File(iconDirectory, "replacement.png")
        writeIcon(original, Color.RED)
        writeIcon(replacement, Color.BLUE)
        val app = mutableStateOf(
            LibraryAppUiItem(1, "Icon example", "Vendor", "1.0", original.path, true,
                iconRevision = 1L),
        )
        val ratio = mutableStateOf(LibraryIconRatio.Square)
        composeRule.setContent {
            JLModPlusTheme {
                LibraryIconSlot(
                    app = app.value,
                    modifier = Modifier.width(64.dp).testTag("icon-artwork"),
                    contentSize = 64.dp,
                    iconRatio = ratio.value,
                    enhancedIcons = false,
                )
            }
        }
        waitForArtwork(Color.RED)
        assertSlotRatio(LibraryIconRatio.Square)

        // The slot changes shape while retaining artwork decoded at the same fixed size.
        composeRule.runOnIdle { ratio.value = LibraryIconRatio.Portrait }
        waitForArtwork(Color.RED)
        assertSlotRatio(LibraryIconRatio.Portrait)

        // The persisted override replaces the file at the same path and advances its revision.
        writeIcon(original, Color.GREEN)
        composeRule.runOnIdle { app.value = app.value.copy(iconRevision = 2L) }
        waitForArtwork(Color.GREEN)

        composeRule.runOnIdle { app.value = app.value.copy(iconPath = replacement.path) }
        waitForArtwork(Color.BLUE)

        // Returning to a cached key must display that source, rather than keep the last bitmap.
        composeRule.runOnIdle { app.value = app.value.copy(iconPath = original.path) }
        waitForArtwork(Color.GREEN)

        composeRule.runOnIdle { ratio.value = LibraryIconRatio.Square }
        waitForArtwork(Color.GREEN)
        assertSlotRatio(LibraryIconRatio.Square)
    }

    private fun assertSlotRatio(ratio: LibraryIconRatio) {
        val image = composeRule.onNodeWithTag("icon-artwork").captureToImage()
        assertEquals(image.width / ratio.widthToHeight, image.height.toFloat(), 1f)
    }

    private fun waitForArtwork(expectedColor: Int) {
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) { artworkCenterColor() == expectedColor }
        assertEquals(expectedColor, artworkCenterColor())
    }

    private fun artworkCenterColor(): Int {
        val image = composeRule.onNodeWithTag("icon-artwork").captureToImage()
        return image.toPixelMap()[image.width / 2, image.height / 2].toArgb()
    }

    private fun writeIcon(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
