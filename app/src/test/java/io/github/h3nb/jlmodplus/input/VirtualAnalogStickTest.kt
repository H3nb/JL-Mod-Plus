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

package io.github.h3nb.jlmodplus.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualAnalogStickTest {
    private val viewport = GuestViewport(200, 100)
    private val geometryTarget = "virtual-stick"
    private val first = PointerSourceToken(PointerSourceKind.VIRTUAL, 1, geometryTarget, 1)
    private val second = PointerSourceToken(PointerSourceKind.VIRTUAL, 2, geometryTarget, 1)

    @Test
    fun fixedStickClampsAndNormalizesCoordinates() {
        val stick = VirtualAnalogStick(
            VirtualAnalogStickSettings(0.5f, 0.5f, 0.25f),
        )

        assertEquals(VirtualAnalogSample(0.0f, 0.0f, true), stick.begin(first, viewport))
        assertEquals(
            VirtualAnalogSample(1.0f, 0.0f, true),
            stick.move(first, 200.0f, 50.0f),
        )
        assertEquals(
            VirtualAnalogSample(-0.5f, 0.5f, true),
            stick.move(first, 87.5f, 62.5f),
        )
        assertEquals(VirtualAnalogSample(0.0f, 0.0f, false), stick.end(first))
        assertTrue(!stick.hasGesture())
    }

    @Test
    fun floatingStickUsesTouchCenterAndRecentersAfterRelease() {
        val stick = VirtualAnalogStick(
            VirtualAnalogStickSettings(
                centerXFraction = 0.5f,
                centerYFraction = 0.5f,
                radiusFractionOfShortestSide = 0.2f,
                mode = VirtualAnalogStickMode.FLOATING,
            ),
        )

        stick.begin(first, viewport, 20.0f, 30.0f)
        val activeVisual = stick.visualState(viewport)
        assertEquals(20.0f, activeVisual.centerX, 0.0001f)
        assertEquals(30.0f, activeVisual.centerY, 0.0001f)
        stick.move(first, 40.0f, 30.0f)
        stick.end(first)

        val idleVisual = stick.visualState(viewport)
        assertEquals(100.0f, idleVisual.centerX, 0.0001f)
        assertEquals(50.0f, idleVisual.centerY, 0.0001f)
        assertTrue(!idleVisual.active)
    }

    @Test
    fun oneContactOwnsStickAndStaleReleaseCannotEndIt() {
        val stick = VirtualAnalogStick()
        assertTrue(stick.begin(first, viewport) != null)
        assertNull(stick.begin(second, viewport))
        assertNull(stick.end(second))
        assertTrue(stick.hasGesture())
        assertTrue(stick.end(first) != null)
    }
}
