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

class PointerControllersTest {
    private val viewport = GuestViewport(100, 200)
    private val target = "guest"
    private val cursorToken = PointerSourceToken(PointerSourceKind.VIRTUAL, 1, target, 4)
    private val joystickToken = PointerSourceToken(PointerSourceKind.VIRTUAL, 2, target, 4)
    private val physicalToken = PointerSourceToken(PointerSourceKind.PHYSICAL, 3, target, 4)

    @Test
    fun cursorUsesFloatPositionAndCapsLongFrameDelta() {
        val cursor = ProportionalCursorController(PointerLeaseController())
        cursor.setViewport(viewport, CursorPosition(50.0f, 100.0f))

        assertTrue(cursor.tick(0L, 1.0f, 0.0f).isEmpty())
        cursor.tick(10L, 1.0f, 0.0f)
        cursor.tick(1_000L, 1.0f, 0.0f)

        // Speed is one shortest-side (100 px) per second: 1 px + capped 5 px.
        assertEquals(56.0f, cursor.position().x, 0.0001f)
        assertEquals(100.0f, cursor.position().y, 0.0001f)
        cursor.onResume(10_000L)
        cursor.tick(11_000L, 1.0f, 0.0f)
        assertEquals(61.0f, cursor.position().x, 0.0001f)
    }

    @Test
    fun cursorClickEmitsPressDragReleaseAndPhysicalTouchTakesOver() {
        val lease = PointerLeaseController()
        val cursor = ProportionalCursorController(lease)
        cursor.setViewport(GuestViewport(80, 60), CursorPosition(10.4f, 20.6f))

        assertEquals(
            listOf(PointerLeaseAction.Press(cursorToken, 10, 21)),
            cursor.beginClick(cursorToken),
        )
        cursor.onResume(0L)
        assertEquals(
            listOf(PointerLeaseAction.Drag(cursorToken, 13, 21)),
            cursor.tick(50L, 1.0f, 0.0f),
        )
        assertEquals(
            listOf(PointerLeaseAction.Release(cursorToken, 13, 21)),
            cursor.beginPhysical(physicalToken, 2, 3),
        )
        assertTrue(!cursor.hasClick())
        assertTrue(cursor.endClick(cursorToken).isEmpty())
        cursor.endPhysical(physicalToken, 2, 3)
        assertEquals(listOf(PointerLeaseAction.Press(cursorToken, 13, 21)), cursor.beginClick(cursorToken))
    }

    @Test
    fun joystickClampsToRadiusAndReturnsToCenterBeforeRelease() {
        val joystick = VirtualTouchJoystickController(
            PointerLeaseController(),
            VirtualJoystickSettings(0.5f, 0.5f, 0.25f),
        )
        val joystickViewport = GuestViewport(200, 100)

        assertEquals(
            listOf(PointerLeaseAction.Press(joystickToken, 100, 50)),
            joystick.begin(joystickToken, joystickViewport),
        )
        assertEquals(
            listOf(PointerLeaseAction.Drag(joystickToken, 125, 50)),
            joystick.move(joystickToken, 200.0f, 50.0f),
        )
        assertEquals(
            listOf(
                PointerLeaseAction.Drag(joystickToken, 100, 50),
                PointerLeaseAction.Release(joystickToken, 100, 50),
            ),
            joystick.end(joystickToken),
        )
    }

    @Test
    fun joystickMapsProcessedVectorToRelativeGuestGeometryAndUpdatesVisualState() {
        val joystick = VirtualTouchJoystickController(
            PointerLeaseController(),
            VirtualJoystickSettings(0.25f, 0.75f, 0.15f),
        )
        val joystickViewport = GuestViewport(200, 100)

        assertEquals(
            listOf(PointerLeaseAction.Press(joystickToken, 50, 75)),
            joystick.begin(joystickToken, joystickViewport),
        )
        assertEquals(
            listOf(PointerLeaseAction.Drag(joystickToken, 62, 66)),
            joystick.moveVector(joystickToken, 0.8f, -0.6f),
        )
        val visual = joystick.visualState(joystickViewport)
        assertEquals(50.0f, visual.centerX, 0.0001f)
        assertEquals(75.0f, visual.centerY, 0.0001f)
        assertEquals(15.0f, visual.radius, 0.0001f)
        assertEquals(62.0f, visual.thumbX, 0.0001f)
        assertEquals(66.0f, visual.thumbY, 0.0001f)
        assertTrue(visual.active)
    }

    @Test
    fun cursorAndJoystickShareExclusiveVirtualLease() {
        val lease = PointerLeaseController()
        val cursor = ProportionalCursorController(lease)
        val joystick = VirtualTouchJoystickController(lease)
        cursor.setViewport(viewport)

        assertTrue(cursor.beginClick(cursorToken).isNotEmpty())
        assertTrue(joystick.begin(joystickToken, viewport).isEmpty())
        cursor.reset()
        assertTrue(joystick.begin(joystickToken, viewport).isNotEmpty())
        assertTrue(cursor.beginClick(cursorToken).isEmpty())
    }

    @Test
    fun joystickPhysicalTakeoverReturnsToCenterAndRejectsStaleVirtualToken() {
        val lease = PointerLeaseController()
        val joystick = VirtualTouchJoystickController(lease)
        val joystickViewport = GuestViewport(200, 100)
        joystick.begin(joystickToken, joystickViewport)
        joystick.move(joystickToken, 200.0f, 50.0f)

        assertEquals(
            listOf(
                PointerLeaseAction.Drag(joystickToken, 100, 50),
                PointerLeaseAction.Release(joystickToken, 100, 50),
            ),
            joystick.beginPhysical(physicalToken, 1, 2),
        )
        assertTrue(joystick.end(joystickToken).isEmpty())
        assertTrue(joystick.hasGesture().not())
    }
}
