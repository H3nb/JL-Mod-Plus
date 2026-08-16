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
        assertEquals(0f, decision.strength, 0f)
    }

    @Test
    fun pixelArtIsHardBypass() {
        val decision = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = true,
            sourceWidth = 32,
            sourceHeight = 32,
            targetSizePx = 128,
        )

        assertFalse(decision.apply)
    }

    @Test
    fun zeroStrengthScaleIsNoOp() {
        val decision = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = false,
            sourceWidth = 32,
            sourceHeight = 32,
            targetSizePx = 128,
            strengthScale = 0f,
        )

        assertFalse(decision.apply)
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
    fun presentationStrengthScaleCanRestrainBadgeEnhancement() {
        val decision = decideLibraryIconEnhancement(
            enabled = true,
            pixelArt = false,
            sourceWidth = 32,
            sourceHeight = 32,
            targetSizePx = 128,
            strengthScale = 0.65f,
        )

        assertTrue(decision.apply)
        assertEquals(0.13f, decision.strength, 0.0001f)
    }

    @Test
    fun transparentNeighborPreservesCenterPixelExactly() {
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
