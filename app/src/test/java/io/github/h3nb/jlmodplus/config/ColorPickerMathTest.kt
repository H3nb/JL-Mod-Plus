/*
 * Copyright 2026 H3NB
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

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorPickerMathTest {
    @Test
    fun primaryHsvColorsConvertToOpaqueRgb() {
        assertEquals(0xFFFF0000.toInt(), ColorPickerMath.toArgb(HsvColor(0f, 1f, 1f)))
        assertEquals(0xFFFFFF00.toInt(), ColorPickerMath.toArgb(HsvColor(60f, 1f, 1f)))
        assertEquals(0xFF00FF00.toInt(), ColorPickerMath.toArgb(HsvColor(120f, 1f, 1f)))
        assertEquals(0xFF00FFFF.toInt(), ColorPickerMath.toArgb(HsvColor(180f, 1f, 1f)))
        assertEquals(0xFF0000FF.toInt(), ColorPickerMath.toArgb(HsvColor(240f, 1f, 1f)))
        assertEquals(0xFFFF00FF.toInt(), ColorPickerMath.toArgb(HsvColor(300f, 1f, 1f)))
    }

    @Test
    fun rgbRoundTripPreservesOpaqueSixDigitValue() {
        val colors = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF336699.toInt(), 0xFFCC6600.toInt())

        colors.forEach { color ->
            assertEquals(color, ColorPickerMath.toArgb(ColorPickerMath.fromArgb(color)))
        }
    }

    @Test
    fun conversionAlwaysReturnsOpaqueArgb() {
        assertEquals(
            0xFF123456.toInt(),
            ColorPickerMath.toArgb(ColorPickerMath.fromArgb(0x00123456)),
        )
    }

    @Test
    fun pointerCoordinatesClampToPickerBounds() {
        assertEquals(0f, ColorPickerMath.hueFromPosition(-1f, 100f), 0.0001f)
        assertEquals(180f, ColorPickerMath.hueFromPosition(50f, 100f), 0.0001f)
        assertEquals(0f, ColorPickerMath.hueFromPosition(101f, 100f), 0.0001f)
        assertEquals(0f, ColorPickerMath.saturationFromPosition(-1f, 100f), 0.0001f)
        assertEquals(1f, ColorPickerMath.saturationFromPosition(101f, 100f), 0.0001f)
        assertEquals(1f, ColorPickerMath.valueFromPosition(-1f, 100f), 0.0001f)
        assertEquals(0f, ColorPickerMath.valueFromPosition(101f, 100f), 0.0001f)
    }
}
