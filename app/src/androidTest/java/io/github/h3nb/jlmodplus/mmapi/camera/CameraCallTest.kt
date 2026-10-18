// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.media.MediaException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraCallTest {
    @Test
    fun pendingCallRejectsMainThreadWaitInsteadOfUsingItsTimeout() {
        val call = CameraCall<String>(5_000)
        val error = AtomicReference<Throwable>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { call.await() } catch (failure: Throwable) { error.set(failure) }
        }
        assertTrue(call.isComplete)
        assertTrue(error.get() is MediaException)
        assertTrue(error.get().message.orEmpty().contains("Android UI thread"))
    }

    @Test
    fun alreadyCompletedValueCanBeReadOnMainThread() {
        val call = CameraCall<String>(5_000)
        call.complete("ready")
        val result = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(call.await()) }
        assertEquals("ready", result.get())
    }

    @Test
    fun explicitFailureReleasesWorkerAndPreservesApiException() {
        val executor = Executors.newSingleThreadExecutor()
        val call = CameraCall<String>(5_000)
        val entering = CountDownLatch(1)
        try {
            val future = executor.submit<Throwable?> {
                entering.countDown()
                try { call.await(); null } catch (failure: Throwable) { failure }
            }
            assertTrue(entering.await(2, TimeUnit.SECONDS))
            val failure = SecurityException("permission denied")
            call.fail(failure)
            assertSame(failure, future.get(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun competingCompletionsAndFailurePublishOnlyOneStableOutcome() {
        val executor = Executors.newFixedThreadPool(3)
        try {
            val call = CameraCall<String>(5_000)
            val start = CountDownLatch(1)
            val failure = MediaException("closed")
            val contenders = listOf(
                executor.submit { start.await(); call.complete("front") },
                executor.submit { start.await(); call.complete("rear") },
                executor.submit { start.await(); call.fail(failure) },
            )
            start.countDown()
            contenders.forEach { it.get(2, TimeUnit.SECONDS) }
            assertTrue(call.isComplete)
            val first = outcome(call)
            val second = outcome(call)
            assertSame(first.exceptionOrNull(), second.exceptionOrNull())
            assertEquals(first.getOrNull(), second.getOrNull())
            assertTrue(first.getOrNull() in listOf("front", "rear") || first.exceptionOrNull() === failure)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun outcome(call: CameraCall<String>): Result<String> = runCatching { call.await() }
}
