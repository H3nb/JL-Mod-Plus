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
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDpadTest {
    private val geometry = VirtualDpadGeometry(50.0f, 50.0f, 50.0f)
    private val first = PointerSourceToken(PointerSourceKind.VIRTUAL, 1, "dpad", 2)
    private val second = PointerSourceToken(PointerSourceKind.VIRTUAL, 2, "dpad", 2)

    @Test
    fun eightWayDpadSupportsCenterDeadzoneDiagonalAndSlide() {
        val dpad = VirtualDpadController()

        assertEquals(
            emptySet<VirtualDpadDirection>(),
            dpad.begin(first, geometry, 50.0f, 50.0f),
        )
        assertEquals(
            setOf(VirtualDpadDirection.UP),
            dpad.move(first, geometry, 50.0f, 0.0f),
        )
        assertEquals(
            linkedSetOf(VirtualDpadDirection.UP, VirtualDpadDirection.RIGHT),
            dpad.move(first, geometry, 85.0f, 15.0f),
        )
        assertEquals(
            setOf(VirtualDpadDirection.RIGHT),
            dpad.move(first, geometry, 100.0f, 50.0f),
        )
        assertEquals(
            setOf(VirtualDpadDirection.RIGHT),
            dpad.end(first),
        )
        assertTrue(!dpad.hasContact(first))
    }

    @Test
    fun fourWayChoosesDominantAxisAndContactsAreIndependent() {
        val dpad = VirtualDpadController(
            VirtualDpadSettings(VirtualDpadDirectionMode.FOUR_WAY, 0.1f),
        )

        assertEquals(
            setOf(VirtualDpadDirection.DOWN),
            dpad.begin(first, geometry, 58.0f, 90.0f),
        )
        assertEquals(
            setOf(VirtualDpadDirection.LEFT),
            dpad.begin(second, geometry, 0.0f, 45.0f),
        )
        val cancelled = dpad.cancelAll()
        assertEquals(2, cancelled.size)
        assertEquals(setOf(VirtualDpadDirection.DOWN), cancelled[first])
        assertEquals(setOf(VirtualDpadDirection.LEFT), cancelled[second])
        assertTrue(!dpad.hasContact(first))
    }

    @Test
    fun heldDirectionUsesRadialAndAngularHysteresis() {
        val dpad = VirtualDpadController(
            VirtualDpadSettings(
                directionMode = VirtualDpadDirectionMode.EIGHT_WAY,
                deadzoneFraction = 0.20f,
                releaseDeadzoneFraction = 0.12f,
                angularHysteresisDegrees = 8.0f,
            ),
        )

        assertEquals(
            setOf(VirtualDpadDirection.RIGHT),
            dpad.begin(first, geometry, 100.0f, 50.0f),
        )
        // Nominally beyond the 22.5-degree boundary, but still inside the retained sector.
        assertEquals(
            setOf(VirtualDpadDirection.RIGHT),
            dpad.move(first, geometry, 95.0f, 72.0f),
        )
        assertEquals(
            linkedSetOf(VirtualDpadDirection.DOWN, VirtualDpadDirection.RIGHT),
            dpad.move(first, geometry, 88.0f, 82.0f),
        )
        // Moving back through the press/release band keeps the held direction stable.
        assertEquals(
            linkedSetOf(VirtualDpadDirection.DOWN, VirtualDpadDirection.RIGHT),
            dpad.move(first, geometry, 57.0f, 55.0f),
        )
        assertEquals(
            emptySet<VirtualDpadDirection>(),
            dpad.move(first, geometry, 54.0f, 53.0f),
        )
    }
}
