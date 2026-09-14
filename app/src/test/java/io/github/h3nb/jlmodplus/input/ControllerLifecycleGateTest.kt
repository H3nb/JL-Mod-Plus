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

class ControllerLifecycleGateTest {
    @Test
    fun motionNoiseMustReturnToNeutralBeforeActivation() {
        val gate = ControllerLifecycleGate()

        assertEquals(
            ControllerLifecycleDecision.CONSUMED,
            gate.offerMotion(deviceId = 4, neutral = false),
        )
        assertEquals(ControllerLifecycleState.WAIT_NEUTRAL, gate.snapshot().state)
        assertEquals(
            ControllerLifecycleDecision.ACTIVATED,
            gate.offerMotion(deviceId = 4, neutral = true),
        )
        assertEquals(ControllerLifecycleState.ACTIVE, gate.snapshot().state)
    }

    @Test
    fun staleUpCannotOpenDigitalNeutralGate() {
        val gate = ControllerLifecycleGate()
        gate.offerDigital(7, keyCode = 10, down = true)

        assertEquals(
            ControllerLifecycleDecision.CONSUMED,
            gate.offerDigital(7, keyCode = 11, down = false),
        )
        assertEquals(ControllerLifecycleState.WAIT_NEUTRAL, gate.snapshot().state)
        assertEquals(
            ControllerLifecycleDecision.ACTIVATED,
            gate.offerDigital(7, keyCode = 10, down = false),
        )
        assertTrue(gate.snapshot().waitingDigitalKeys.isEmpty())
    }

    @Test
    fun deviceAndTargetBoundariesAdvanceGenerationAndReleaseActivation() {
        val gate = ControllerLifecycleGate()
        gate.offerMotion(1, neutral = true)
        val activeGeneration = gate.snapshot().generation

        gate.onDeviceChanged(1)
        assertEquals(ControllerLifecycleState.WAIT_NEUTRAL, gate.snapshot().state)
        assertTrue(gate.snapshot().generation > activeGeneration)
        gate.offerMotion(1, neutral = true)
        gate.beginBoundary(waitForNeutral = true, nextDeviceId = 2)
        assertEquals(ControllerLifecycleState.WAIT_NEUTRAL, gate.snapshot().state)
        assertEquals(2, gate.snapshot().waitingDeviceId)

        gate.onDeviceRemoved(2)
        assertEquals(ControllerLifecycleState.INACTIVE, gate.snapshot().state)
        assertEquals(null, gate.snapshot().activeDeviceId)
    }

    @Test
    fun differentDeviceCannotTakeOverUntilItsOwnNeutralSample() {
        val gate = ControllerLifecycleGate()
        assertEquals(ControllerLifecycleDecision.ACTIVATED, gate.offerMotion(1, true))

        assertEquals(ControllerLifecycleDecision.CONSUMED, gate.offerMotion(2, false))
        assertEquals(ControllerLifecycleState.WAIT_NEUTRAL, gate.snapshot().state)
        assertEquals(ControllerLifecycleDecision.ACTIVATED, gate.offerMotion(2, true))
        assertEquals(2, gate.snapshot().activeDeviceId)
    }

    @Test
    fun inputFromAnotherActiveDeviceIsConsumedUntilThatDeviceIsNeutral() {
        val gate = ControllerLifecycleGate()
        assertEquals(ControllerLifecycleDecision.ACTIVATED, gate.offerMotion(1, neutral = true))

        assertEquals(
            ControllerLifecycleDecision.CONSUMED,
            gate.offerDigital(deviceId = 2, keyCode = 10, down = true),
        )
        assertEquals(ControllerLifecycleState.WAIT_NEUTRAL, gate.snapshot().state)
        assertEquals(2, gate.snapshot().waitingDeviceId)
        assertTrue(gate.snapshot().waitingDigitalKeys.isEmpty())
        assertEquals(
            ControllerLifecycleDecision.CONSUMED,
            gate.offerDigital(deviceId = 1, keyCode = 10, down = false),
        )
        assertEquals(
            ControllerLifecycleDecision.ACTIVATED,
            gate.offerMotion(deviceId = 2, neutral = true),
        )
    }
}
