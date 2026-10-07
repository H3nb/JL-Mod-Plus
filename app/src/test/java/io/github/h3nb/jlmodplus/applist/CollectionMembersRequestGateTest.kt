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

package io.github.h3nb.jlmodplus.applist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionMembersRequestGateTest {
    @Test
    fun newerCollectionRejectsLatePreviousResult() {
        val gate = CollectionMembersRequestGate()
        val collectionA = gate.begin(1L, 4L)
        val collectionB = gate.begin(2L, 4L)

        assertFalse(gate.accepts(collectionA, 4L))
        assertTrue(gate.accepts(collectionB, 4L))
        assertEquals(2L, gate.targetCollectionId())
    }

    @Test
    fun newerRefreshOfSameCollectionRejectsOlderSnapshot() {
        val gate = CollectionMembersRequestGate()
        val oldSnapshot = gate.begin(2L, 4L)
        val newSnapshot = gate.begin(2L, 4L)

        assertFalse(gate.accepts(oldSnapshot, 4L))
        assertTrue(gate.accepts(newSnapshot, 4L))
    }

    @Test
    fun invalidationAndGenerationChangeRejectOutstandingRequest() {
        val gate = CollectionMembersRequestGate()
        val request = gate.begin(2L, 4L)

        assertFalse(gate.accepts(request, 5L))
        gate.invalidate()
        assertFalse(gate.accepts(request, 4L))
        assertFalse(gate.targets(2L))
    }
}
