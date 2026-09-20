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
    fun targetBoundaryRequiresNeutralBeforeSameControllerRearms() {
        val gate = ControllerLifecycleGate()
        assertEquals(ControllerLifecycleDecision.ACTIVATED, gate.offerMotion(1, neutral = true))
        assertEquals(ControllerLifecycleDecision.ACTIVE, gate.offerMotion(1, neutral = false))

        gate.beginBoundary(waitForNeutral = true, nextDeviceId = 1)
        val waiting = gate.snapshot()
        assertEquals(ControllerLifecycleState.WAIT_NEUTRAL, waiting.state)
        assertEquals(1, waiting.activeDeviceId)
        assertEquals(1, waiting.waitingDeviceId)

        assertEquals(ControllerLifecycleDecision.CONSUMED, gate.offerMotion(1, neutral = false))
        assertEquals(waiting, gate.snapshot())

        assertEquals(ControllerLifecycleDecision.ACTIVATED, gate.offerMotion(1, neutral = true))
        assertEquals(ControllerLifecycleState.ACTIVE, gate.snapshot().state)

        assertEquals(ControllerLifecycleDecision.ACTIVE, gate.offerMotion(1, neutral = false))
    }

    @Test
    fun neutralSecondDeviceCanReplaceNoisyWaitingDevice() {
        val gate = ControllerLifecycleGate()

        assertEquals(ControllerLifecycleDecision.CONSUMED, gate.offerMotion(1, false))
        assertEquals(1, gate.snapshot().waitingDeviceId)

        assertEquals(ControllerLifecycleDecision.ACTIVATED, gate.offerMotion(2, true))
        assertEquals(ControllerLifecycleState.ACTIVE, gate.snapshot().state)
        assertEquals(2, gate.snapshot().activeDeviceId)
        assertEquals(null, gate.snapshot().waitingDeviceId)
    }

    @Test
    fun differentDeviceCannotTakeOverUntilItsOwnNeutralSample() {
        val gate = ControllerLifecycleGate()
        assertEquals(ControllerLifecycleDecision.ACTIVATED, gate.offerMotion(1, true))

        assertEquals(ControllerLifecycleDecision.CONSUMED, gate.offerMotion(2, false))
        assertEquals(ControllerLifecycleState.ACTIVE, gate.snapshot().state)
        assertEquals(1, gate.snapshot().activeDeviceId)

        assertEquals(ControllerLifecycleDecision.ACTIVATED, gate.offerMotion(2, true))
        assertEquals(ControllerLifecycleState.ACTIVE, gate.snapshot().state)
        assertEquals(2, gate.snapshot().activeDeviceId)
    }
    @Test
    fun foreignDeflectionDoesNotChangeActiveOwnerOrGeneration() {
        val gate = ControllerLifecycleGate()
        gate.offerMotion(1, neutral = true)
        val before = gate.snapshot()

        assertEquals(ControllerLifecycleDecision.CONSUMED, gate.offerMotion(2, neutral = false))

        assertEquals(before, gate.snapshot())
    }

    @Test
    fun neutralForeignDeviceTakesOverDirectlyAndOnlyOnce() {
        val gate = ControllerLifecycleGate()
        gate.offerMotion(1, neutral = true)
        val generationA = gate.snapshot().generation

        assertEquals(ControllerLifecycleDecision.ACTIVATED, gate.offerMotion(2, neutral = true))
        val takeover = gate.snapshot()
        assertEquals(ControllerLifecycleState.ACTIVE, takeover.state)
        assertEquals(2, takeover.activeDeviceId)
        assertEquals(null, takeover.waitingDeviceId)
        assertTrue(takeover.generation > generationA)

        assertEquals(ControllerLifecycleDecision.ACTIVE, gate.offerMotion(2, neutral = false))
        assertEquals(takeover, gate.snapshot())
    }

    @Test
    fun removingActiveDeviceDoesNotStarveNextController() {
        val gate = ControllerLifecycleGate()
        gate.offerMotion(1, neutral = true)
        gate.onDeviceRemoved(1)
        assertEquals(ControllerLifecycleState.INACTIVE, gate.snapshot().state)

        assertEquals(ControllerLifecycleDecision.CONSUMED, gate.offerMotion(2, neutral = false))
        assertEquals(ControllerLifecycleState.WAIT_NEUTRAL, gate.snapshot().state)
        assertEquals(ControllerLifecycleDecision.ACTIVATED, gate.offerMotion(2, neutral = true))
        assertEquals(2, gate.snapshot().activeDeviceId)
    }

}
