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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryControllerEventHubTest {
    @Test
    fun commandsQueuedBeforeCompositionSubscriberAreDeliveredInOrder() = runBlocking {
        val hub = LibraryControllerEventHub(Dispatchers.Unconfined)
        val expected = listOf(
            LibraryControllerEvent(1L, LibraryControllerCommand.MoveDown),
            LibraryControllerEvent(2L, LibraryControllerCommand.Activate),
            LibraryControllerEvent(3L, LibraryControllerCommand.Back),
        )
        expected.forEach(hub::offer)

        val received = async {
            withTimeout(1_000L) {
                hub.flow.take(expected.size).toList()
            }
        }.await()
        hub.close()

        assertEquals(expected, received)
    }
}
