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

import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lossless controller-command fan-out for the library surface.
 *
 * A SharedFlow with a dropping overflow policy can skip navigation commands while a lazy list
 * is animating. This small hub queues commands until the composition has a subscriber, then
 * emits them in order to both the page and any active modal collector.
 */
internal class LibraryControllerEventHub(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = MutableSharedFlow<LibraryControllerEvent>(replay = 0)
    private val pending = ArrayDeque<LibraryControllerEvent>()
    private var drainJob: Job? = null

    val flow: Flow<LibraryControllerEvent> = events

    fun offer(event: LibraryControllerEvent) {
        if (!scope.coroutineContext.isActive) return
        pending.addLast(event)
        startDrain()
    }

    fun close() {
        scope.cancel()
        pending.clear()
        drainJob = null
    }

    private fun startDrain() {
        if (drainJob?.isActive == true) return
        val job = scope.launch {
            while (pending.isNotEmpty()) {
                events.subscriptionCount.first { it > 0 }
                events.emit(pending.removeFirst())
            }
        }
        drainJob = job
        job.invokeOnCompletion {
            if (drainJob === job) drainJob = null
        }
    }
}
