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

/** Lifecycle state shared by the Android controller adapter and its tests. */
enum class ControllerLifecycleState {
    INACTIVE,
    WAIT_NEUTRAL,
    ACTIVE,
}

/** Result of offering a controller sample to [ControllerLifecycleGate]. */
enum class ControllerLifecycleDecision {
    /** The caller may continue normal processing for the sample. */
    ACTIVE,
    /** The sample belongs to the controller boundary and must be consumed. */
    CONSUMED,
    /** The neutral gate has just opened; the current sample is still consumed. */
    ACTIVATED,
}

data class ControllerLifecycleSnapshot(
    val state: ControllerLifecycleState,
    val activeDeviceId: Int?,
    val waitingDeviceId: Int?,
    val generation: Long,
    /** Always empty: the lifecycle barrier is intentionally analog-only. */
    val waitingDigitalKeys: Set<Int> = emptySet(),
)

/**
 * Pure lifecycle barrier for controller ownership.
 *
 * A newly selected or reconnected device cannot emit analog gameplay until a neutral motion
 * sample has been observed. Digital contacts are deliberately outside this barrier: a first
 * button press after resume must never be sacrificed to activate the controller.
 */
class ControllerLifecycleGate {
    private var state = ControllerLifecycleState.INACTIVE
    private var activeDeviceId: Int? = null
    private var waitingDeviceId: Int? = null
    private var generation = 0L

    @Synchronized
    fun snapshot(): ControllerLifecycleSnapshot = ControllerLifecycleSnapshot(
        state = state,
        activeDeviceId = activeDeviceId,
        waitingDeviceId = waitingDeviceId,
        generation = generation,
        waitingDigitalKeys = emptySet(),
    )

    @Synchronized
    fun clear() {
        state = ControllerLifecycleState.INACTIVE
        activeDeviceId = null
        waitingDeviceId = null
        generation = nextGeneration(generation)
    }

    @Synchronized
    fun beginBoundary(waitForNeutral: Boolean, nextDeviceId: Int? = activeDeviceId) {
        generation = nextGeneration(generation)
        if (waitForNeutral && nextDeviceId != null) {
            activeDeviceId = nextDeviceId
            waitingDeviceId = nextDeviceId
            state = ControllerLifecycleState.WAIT_NEUTRAL
        } else {
            activeDeviceId = null
            waitingDeviceId = null
            state = ControllerLifecycleState.INACTIVE
        }
    }

    @Synchronized
    fun offerMotion(deviceId: Int, neutral: Boolean): ControllerLifecycleDecision {
        when (state) {
            ControllerLifecycleState.INACTIVE -> {
                beginWaiting(deviceId)
                return if (neutral) activate() else ControllerLifecycleDecision.CONSUMED
            }

            ControllerLifecycleState.WAIT_NEUTRAL -> {
                if (waitingDeviceId != deviceId || !neutral) {
                    return ControllerLifecycleDecision.CONSUMED
                }
                return activate()
            }

            ControllerLifecycleState.ACTIVE -> {
                if (activeDeviceId != null && activeDeviceId != deviceId) {
                    beginWaiting(deviceId)
                    return ControllerLifecycleDecision.CONSUMED
                }
                activeDeviceId = deviceId
                return ControllerLifecycleDecision.ACTIVE
            }
        }
    }

    @Synchronized
    @Deprecated("Digital key events are not lifecycle-gated")
    fun offerDigital(
        deviceId: Int,
        keyCode: Int,
        down: Boolean,
    ): ControllerLifecycleDecision = ControllerLifecycleDecision.ACTIVE

    @Synchronized
    fun onDeviceChanged(deviceId: Int) {
        if (state == ControllerLifecycleState.ACTIVE && activeDeviceId == deviceId) {
            beginBoundary(waitForNeutral = true, nextDeviceId = deviceId)
        }
    }

    @Synchronized
    fun onDeviceRemoved(deviceId: Int) {
        if (activeDeviceId == deviceId || waitingDeviceId == deviceId) {
            clear()
        }
    }

    private fun beginWaiting(deviceId: Int) {
        activeDeviceId = deviceId
        waitingDeviceId = deviceId
        state = ControllerLifecycleState.WAIT_NEUTRAL
        generation = nextGeneration(generation)
    }

    private fun activate(): ControllerLifecycleDecision {
        waitingDeviceId = null
        state = if (activeDeviceId == null) {
            ControllerLifecycleState.INACTIVE
        } else {
            ControllerLifecycleState.ACTIVE
        }
        generation = nextGeneration(generation)
        return if (state == ControllerLifecycleState.ACTIVE) {
            ControllerLifecycleDecision.ACTIVATED
        } else {
            ControllerLifecycleDecision.CONSUMED
        }
    }

    private fun nextGeneration(value: Long): Long =
        if (value == Long.MAX_VALUE) 1L else value + 1L
}
