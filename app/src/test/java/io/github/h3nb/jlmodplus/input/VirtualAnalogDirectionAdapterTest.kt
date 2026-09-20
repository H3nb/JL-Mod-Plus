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
import org.junit.Test

class VirtualAnalogDirectionAdapterTest {
    @Test
    fun normalizedVirtualSampleUsesSharedDeadzoneAndEightWayDirectionLogic() {
        val adapter = VirtualAnalogDirectionAdapter()

        assertEquals(
            emptyList<StickProcessor.DirectionKey>(),
            adapter.update(VirtualAnalogSample(0.05f, 0.05f, true)),
        )
        assertEquals(
            listOf(StickProcessor.DirectionKey.RIGHT),
            adapter.update(VirtualAnalogSample(0.9f, 0.0f, true)),
        )
        assertEquals(
            listOf(StickProcessor.DirectionKey.UP, StickProcessor.DirectionKey.RIGHT),
            adapter.update(VirtualAnalogSample(0.9f, -0.9f, true)),
        )
        assertEquals(
            emptyList<StickProcessor.DirectionKey>(),
            adapter.update(VirtualAnalogSample(0.0f, 0.0f, false)),
        )
    }

    @Test
    fun virtualAndPhysicalNormalizedVectorsResolveIdentically() {
        val sample = VirtualAnalogSample(-0.82f, 0.76f, true)
        val adapter = VirtualAnalogDirectionAdapter()

        val virtualKeys = adapter.update(sample)
        val processed = StickProcessor.processNormalizedStick(sample.x, sample.y)
        val physicalEquivalent = StickProcessor.resolveDirection(processed).keys

        assertEquals(physicalEquivalent, virtualKeys)
    }
}
