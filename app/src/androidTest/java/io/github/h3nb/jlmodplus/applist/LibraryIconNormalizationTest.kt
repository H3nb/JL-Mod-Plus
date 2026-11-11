/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end bitmap characterization of the existing enhanced-icon normalization path. */
@RunWith(AndroidJUnit4::class)
class LibraryIconNormalizationTest {
    @Test
    fun transparentSubjectCropsOnlyEmptyMargins() {
        val source = makeBitmap(64, 64) { x, y ->
            if (x in 22 until 42 && y in 22 until 42) Color.RED else Color.TRANSPARENT
        }
        val icon = requireNotNull(normalizeLibraryIcon(source, enhanceIcon = true))

        assertEquals(LibraryIconPresentationMode.Subject, icon.presentationMode)
        assertEquals(0.60f, icon.visualScale, 0.0001f)
        assertEquals(FilterQuality.None, icon.filterQuality)
        assertNotNull(icon.tileColor)
        val pixels = icon.bitmap.asAndroidBitmap()
        assertEquals(20, pixels.width)
        assertEquals(20, pixels.height)
        assertEquals(Color.RED, pixels.getPixel(0, 0))
        assertEquals(Color.RED, pixels.getPixel(19, 19))
    }

    @Test
    fun opaqueBackplateKeepsItsPixelsAndSourceColor() {
        val source = makeBitmap(64, 64) { _, _ -> Color.BLUE }
        val icon = requireNotNull(normalizeLibraryIcon(source, enhanceIcon = true))

        assertEquals(LibraryIconPresentationMode.Backed, icon.presentationMode)
        assertEquals(0.92f, icon.visualScale, 0.0001f)
        assertEquals(Color.BLUE, requireNotNull(icon.tileColor).toArgb())
        assertEquals(64, icon.bitmap.width)
        assertEquals(64, icon.bitmap.height)
        assertEquals(Color.BLUE, icon.bitmap.asAndroidBitmap().getPixel(32, 32))
    }

    @Test
    fun fullBleedMulticolorArtworkUsesCoverWithoutSyntheticFill() {
        val source = makeBitmap(96, 96) { x, y ->
            Color.rgb((x * 11 + y * 3) and 255, (x * 13 + y * 7) and 255, (x * 17 + y * 23) and 255)
        }
        val icon = requireNotNull(normalizeLibraryIcon(source, enhanceIcon = true))

        assertEquals(LibraryIconPresentationMode.Cover, icon.presentationMode)
        assertEquals(1f, icon.visualScale, 0.0001f)
        assertNull(icon.tileColor)
        assertEquals(FilterQuality.Medium, icon.filterQuality)
        assertEquals(96, icon.bitmap.width)
        assertEquals(96, icon.bitmap.height)
    }

    @Test
    fun coverUsesEdgeColorToFillLetterboxWithoutChangingArtwork() {
        val source = makeBitmap(96, 128) { x, y ->
            val inCorner = (x < 12 || x >= 84) && (y < 12 || y >= 116)
            if (inCorner) {
                Color.rgb(12, 99, 172)
            } else {
                Color.rgb((x * 11 + y * 3) and 255, (x * 13 + y * 7) and 255, (x * 17 + y * 23) and 255)
            }
        }
        val icon = requireNotNull(normalizeLibraryIcon(source, enhanceIcon = true))

        assertEquals(LibraryIconPresentationMode.Cover, icon.presentationMode)
        assertEquals(Color.rgb(12, 99, 172), requireNotNull(icon.tileColor).toArgb())
        assertEquals(96, icon.bitmap.width)
        assertEquals(128, icon.bitmap.height)
        assertEquals(Color.rgb(12, 99, 172), icon.bitmap.asAndroidBitmap().getPixel(0, 0))
        assertEquals(source.getPixel(48, 64), icon.bitmap.asAndroidBitmap().getPixel(48, 64))
    }

    @Test
    fun quantizedColorDiversityPreservesPixelArtThresholds() {
        val lowDiversity = normalizeLibraryIcon(
            colorPattern(64, 64, distinctColors = 48),
            enhanceIcon = true,
        )
        val aboveThreshold = normalizeLibraryIcon(
            colorPattern(64, 64, distinctColors = 49),
            enhanceIcon = true,
        )
        val aboveAnalysisCap = normalizeLibraryIcon(
            colorPattern(64, 64, distinctColors = 65),
            enhanceIcon = true,
        )
        assertEquals(FilterQuality.None, requireNotNull(lowDiversity).filterQuality)
        assertEquals(FilterQuality.Medium, requireNotNull(aboveThreshold).filterQuality)
        assertEquals(FilterQuality.Medium, requireNotNull(aboveAnalysisCap).filterQuality)
    }

    @Test
    fun aFramedIconRetainsItsOpaqueMatteAndForegroundPixels() {
        val source = makeBitmap(96, 96) { x, y ->
            if (x in 28 until 68 && y in 28 until 68) Color.RED else Color.BLUE
        }
        val icon = requireNotNull(normalizeLibraryIcon(source, enhanceIcon = true))
        val result = icon.bitmap.asAndroidBitmap()
        assertEquals(LibraryIconPresentationMode.Backed, icon.presentationMode)
        assertEquals(Color.BLUE, requireNotNull(icon.tileColor).toArgb())
        assertEquals(96, result.width)
        assertEquals(96, result.height)
        assertEquals(Color.BLUE, result.getPixel(0, 0))
        assertEquals(Color.RED, result.getPixel(48, 48))
    }

    @Test
    fun explicitPortraitArtworkIsNeverCroppedOrRescaled() {
        val source = makeBitmap(60, 80) { x, y ->
            if (x in 10 until 50 && y in 10 until 70) Color.GREEN else Color.WHITE
        }
        val icon = requireNotNull(normalizeLibraryIcon(source, enhanceIcon = true, preserveArtwork = true))

        assertEquals(LibraryIconPresentationMode.SafeFit, icon.presentationMode)
        assertEquals(1f, icon.visualScale, 0.0001f)
        val pixels = icon.bitmap.asAndroidBitmap()
        assertEquals(60, pixels.width)
        assertEquals(80, pixels.height)
        assertEquals(Color.WHITE, pixels.getPixel(0, 0))
        assertEquals(Color.GREEN, pixels.getPixel(30, 40))
    }

    @Test
    fun disabledEnhancementPreservesTransparentSourceExactly() {
        val source = makeBitmap(64, 64) { x, y ->
            if (x in 22 until 42 && y in 22 until 42) Color.RED else Color.TRANSPARENT
        }
        val icon = requireNotNull(normalizeLibraryIcon(source, enhanceIcon = false))

        assertEquals(LibraryIconPresentationMode.SafeFit, icon.presentationMode)
        assertEquals(1f, icon.visualScale, 0.0001f)
        assertNull(icon.tileColor)
        val pixels = icon.bitmap.asAndroidBitmap()
        assertEquals(64, pixels.width)
        assertEquals(64, pixels.height)
        assertEquals(Color.TRANSPARENT, pixels.getPixel(0, 0))
        assertEquals(Color.RED, pixels.getPixel(32, 32))
    }

    private fun colorPattern(width: Int, height: Int, distinctColors: Int): Bitmap =
        makeBitmap(width, height) { x, y ->
            val index = (y * width + x) % distinctColors
            Color.rgb(((index ushr 8) and 15) * 17, ((index ushr 4) and 15) * 17, (index and 15) * 17)
        }

    private fun makeBitmap(
        width: Int,
        height: Int,
        colorAt: (x: Int, y: Int) -> Int,
    ): Bitmap {
        val colors = IntArray(width * height) { i -> colorAt(i % width, i / width) }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }
}
