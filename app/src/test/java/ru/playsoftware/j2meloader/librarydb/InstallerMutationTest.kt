package ru.playsoftware.j2meloader.librarydb

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InstallerMutationTest {
    @Test fun cancellationAfterHandoffCompletesExactlyOnce() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        var failure: Throwable? = null
        launchInstallerMutation<Unit>(scope, { _, error -> calls++; failure = error }) { awaitCancellation() }
        scope.cancel()
        assertEquals(1, calls)
        assertTrue(failure is CancellationException)
    }

    @Test fun alreadyCancelledScopeStillCompletes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.cancel()
        var calls = 0
        launchInstallerMutation(scope, { value: Int?, error ->
            calls++
            assertNull(value)
            assertTrue(error is CancellationException)
        }) { throw AssertionError("Cancelled work must never start") }
        assertEquals(1, calls)
    }

    @Test fun successIsNotReportedAgainWhenScopeCloses() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        launchInstallerMutation(scope, { value: Int?, error ->
            calls++; assertEquals(42, value); assertNull(error)
        }) { 42 }
        scope.cancel()
        assertEquals(1, calls)
    }
}
