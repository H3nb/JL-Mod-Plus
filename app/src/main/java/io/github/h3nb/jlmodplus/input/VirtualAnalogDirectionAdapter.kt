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

/**
 * Converts normalized virtual-stick samples through the same [StickProcessor] stages used by
 * physical controller sticks. It deliberately emits logical direction keys rather than Canvas
 * events; the caller decides which guest target/ownership token receives them.
 */
class VirtualAnalogDirectionAdapter @JvmOverloads constructor(
    private val stickConfig: StickProcessor.StickConfig = StickProcessor.StickConfig(),
    private val directionConfig: StickProcessor.DirectionConfig = StickProcessor.DirectionConfig(),
) {
    private var directionState = StickProcessor.DirectionState()

    fun update(sample: VirtualAnalogSample): List<StickProcessor.DirectionKey> {
        if (!sample.active) {
            directionState = StickProcessor.DirectionState()
            return emptyList()
        }
        val processed = StickProcessor.processNormalizedStick(sample.x, sample.y, stickConfig)
        val resolved = StickProcessor.resolveDirection(processed, directionState, directionConfig)
        directionState = resolved.state
        return resolved.keys
    }

    fun reset(): List<StickProcessor.DirectionKey> {
        directionState = StickProcessor.DirectionState()
        return emptyList()
    }

    fun state(): StickProcessor.DirectionState = directionState
}
