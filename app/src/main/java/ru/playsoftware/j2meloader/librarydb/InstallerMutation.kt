/* Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package ru.playsoftware.j2meloader.librarydb

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Unlike UI-only mutations, an install completion releases a process-wide permit. */
internal fun <T> launchInstallerMutation(
    scope: CoroutineScope,
    complete: (T?, Throwable?) -> Unit,
    block: suspend () -> T,
) {
    val completed = AtomicBoolean()
    val job = scope.launch {
        val result = runCatching { block() }
        if (completed.compareAndSet(false, true)) complete(result.getOrNull(), result.exceptionOrNull())
    }
    job.invokeOnCompletion { error ->
        if (completed.compareAndSet(false, true)) {
            complete(null, error ?: CancellationException("Install mutation did not run"))
        }
    }
}
