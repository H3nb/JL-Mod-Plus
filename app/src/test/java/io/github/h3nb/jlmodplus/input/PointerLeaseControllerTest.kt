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

class PointerLeaseControllerTest {
    private val target = "canvas"

    @Test
    fun virtualLeaseUsesGuestChannelZeroAndPhysicalTouchTakesItOver() {
        val controller = PointerLeaseController()
        val virtual = PointerSourceToken(PointerSourceKind.VIRTUAL, 17, target, 3)
        val physical = PointerSourceToken(PointerSourceKind.PHYSICAL, 2, target, 3)

        assertEquals(
            listOf(PointerLeaseAction.Press(virtual, 10, 20)),
            controller.beginVirtual(virtual, 10, 20),
        )
        val takeover = controller.beginPhysical(physical, 4, 5)
        assertEquals(listOf(PointerLeaseAction.Release(virtual, 10, 20)), takeover)
        assertTrue(controller.hasPhysicalGesture())
        assertTrue(!controller.hasVirtualGesture())
        assertEquals(0, controller.guestPointerId())
    }

    @Test
    fun virtualCannotStartWhilePhysicalIsActiveAndStaleTokenCannotRelease() {
        val controller = PointerLeaseController()
        val physical = PointerSourceToken(PointerSourceKind.PHYSICAL, 1, target, 7)
        val virtual = PointerSourceToken(PointerSourceKind.VIRTUAL, 1, target, 7)

        controller.beginPhysical(physical, 0, 0)
        assertTrue(controller.beginVirtual(virtual, 1, 1).isEmpty())
        controller.endPhysical(physical, 0, 0)
        assertEquals(1, controller.beginVirtual(virtual, 1, 1).size)
        val stale = virtual.copy(generation = 8)
        assertTrue(controller.endVirtual(stale, 2, 2).isEmpty())
        assertTrue(controller.hasVirtualGesture())
    }

    @Test
    fun resetReleasesVirtualOnceAndClearsPhysicalContacts() {
        val controller = PointerLeaseController()
        val virtual = PointerSourceToken(PointerSourceKind.VIRTUAL, 9, target, 1)
        controller.beginVirtual(virtual, 5, 6)
        assertEquals(listOf(PointerLeaseAction.Release(virtual, 5, 6)), controller.reset())
        val physical = PointerSourceToken(PointerSourceKind.PHYSICAL, 4, target, 1)
        controller.beginPhysical(physical, 2, 3)
        assertTrue(controller.reset().isEmpty())
        assertTrue(!controller.hasPhysicalGesture())
        assertTrue(!controller.hasVirtualGesture())
    }
}
