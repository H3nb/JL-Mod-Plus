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

package ru.playsoftware.j2meloader.applist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIconEnhancementTest {
    @Test
    fun disabledEnhancementIsNoOp() {
        val decision = decideLibraryIconEnhancement(
            enabled = false,
            pixelArt = false,
            sourceWidth = 32,
            sourceHeight = 32,
            targetSizePx = 128,
        )

        assertFalse(decision.apply)
        assertEquals(LibraryIconEnhancementMode.None, decision.mode)
        assertEquals(0f, decision.strength, 0f)
    }

    @Test
    fun lowResolutionPixelArtUsesSingleMmpxPass() {
        val decision = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = true,
            sourceWidth = 32,
            sourceHeight = 24,
            targetSizePx = 128,
        )

        assertTrue(decision.apply)
        assertEquals(LibraryIconEnhancementMode.Mmpx2x, decision.mode)
        assertEquals(64, decision.targetWidth)
        assertEquals(48, decision.targetHeight)
        assertEquals(0f, decision.strength, 0f)
    }

    @Test
    fun pixelArtAlreadyNearRenderTargetIsNotNeedlesslyMagnified() {
        val decision = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = true,
            sourceWidth = 100,
            sourceHeight = 80,
            targetSizePx = 128,
        )

        assertFalse(decision.apply)
        assertEquals(LibraryIconEnhancementMode.None, decision.mode)
    }

    @Test
    fun zeroStrengthScaleIsNoOp() {
        val decision = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = true,
            sourceWidth = 32,
            sourceHeight = 32,
            targetSizePx = 128,
            strengthScale = 0f,
        )

        assertFalse(decision.apply)
    }

    @Test
    fun invalidDimensionsAreNoOp() {
        val invalidWidth = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = false,
            sourceWidth = 0,
            sourceHeight = 32,
            targetSizePx = 128,
        )
        val invalidTarget = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = false,
            sourceWidth = 32,
            sourceHeight = 32,
            targetSizePx = 0,
        )

        assertFalse(invalidWidth.apply)
        assertFalse(invalidTarget.apply)
    }

    @Test
    fun lowResolutionRasterScalesOnlyToRenderTarget() {
        val decision = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = false,
            sourceWidth = 32,
            sourceHeight = 16,
            targetSizePx = 128,
        )

        assertTrue(decision.apply)
        assertEquals(LibraryIconEnhancementMode.RasterSharpen, decision.mode)
        assertEquals(128, decision.targetWidth)
        assertEquals(64, decision.targetHeight)
        assertEquals(0.20f, decision.strength, 0.0001f)
    }

    @Test
    fun targetSizedRasterGetsMildSharpenWithoutResampling() {
        val decision = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = false,
            sourceWidth = 128,
            sourceHeight = 96,
            targetSizePx = 128,
        )

        assertTrue(decision.apply)
        assertEquals(LibraryIconEnhancementMode.RasterSharpen, decision.mode)
        assertEquals(128, decision.targetWidth)
        assertEquals(96, decision.targetHeight)
        assertEquals(0.14f, decision.strength, 0.0001f)
    }

    @Test
    fun alreadyLargeRasterIsLeftUntouched() {
        val decision = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = false,
            sourceWidth = 256,
            sourceHeight = 256,
            targetSizePx = 128,
        )

        assertFalse(decision.apply)
    }

    @Test
    fun presentationStrengthScaleCanRestrainRasterBadgeEnhancement() {
        val decision = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = false,
            sourceWidth = 32,
            sourceHeight = 32,
            targetSizePx = 128,
            strengthScale = 0.65f,
        )

        assertTrue(decision.apply)
        assertEquals(LibraryIconEnhancementMode.RasterSharpen, decision.mode)
        assertEquals(0.13f, decision.strength, 0.0001f)
    }

    @Test
    fun mmpxPreservesPaletteAndOutputArea() {
        val transparent = 0x00000000
        val red = 0xffff0000.toInt()
        val blue = 0xff0000ff.toInt()
        val pixels = intArrayOf(
            transparent, red,
            blue, red,
        )

        val enhanced = mmpx2x(pixels, width = 2, height = 2)
        val palette = pixels.toSet()

        assertEquals(16, enhanced.size)
        assertTrue(enhanced.all { it in palette })
    }

    @Test
    fun mmpxReconstructsSimpleDiagonalInsteadOfPlainNearestNeighbor() {
        val dark = 0xff000000.toInt()
        val light = 0xffffffff.toInt()
        val pixels = intArrayOf(
            light, dark, light,
            dark, light, light,
            light, light, light,
        )

        val enhanced = mmpx2x(pixels, width = 3, height = 3)
        val outputWidth = 6
        val centerTopLeft = enhanced[2 * outputWidth + 2]

        assertEquals(dark, centerTopLeft)
    }

    @Test
    fun transparentNeighborPreservesCenterPixelExactlyDuringRasterSharpen() {
        val gray = 0xff808080.toInt()
        val transparent = 0x00808080
        val pixels = intArrayOf(
            gray, transparent, gray,
            gray, gray, gray,
            gray, gray, gray,
        )

        val enhanced = sharpenLibraryPixels(
            source = pixels,
            width = 3,
            height = 3,
            strength = 0.20f,
        )

        assertEquals(gray, enhanced[4])
    }

    @Test
    fun opaqueDetailIsSharpenedButLocallyClampedAndKeepsAlpha() {
        val dark = 0xff404040.toInt()
        val center = 0xff808080.toInt()
        val pixels = intArrayOf(
            dark, dark, dark,
            dark, center, dark,
            dark, dark, dark,
        )

        val enhanced = sharpenLibraryPixels(
            source = pixels,
            width = 3,
            height = 3,
            strength = 0.20f,
        )
        val result = enhanced[4]
        val alpha = (result ushr 24) and 0xff
        val red = (result ushr 16) and 0xff

        assertEquals(0xff, alpha)
        assertTrue(red > 0x80)
        assertTrue(red <= 0x8c)
    }
}
