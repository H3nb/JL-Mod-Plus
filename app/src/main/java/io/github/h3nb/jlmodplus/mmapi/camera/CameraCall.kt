// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.media.MediaException

/** A guest may wait; the Android main thread and producer callbacks never do. */
internal class CameraCall<T>(private val timeoutMillis: Long) {
    private val latch = CountDownLatch(1)
    @Volatile private var result: T? = null
    @Volatile private var failure: Throwable? = null
    val isComplete: Boolean get() = latch.count == 0L

    @Synchronized fun complete(value: T) {
        if (!isComplete) {
            result = value
            latch.countDown()
        }
    }

    @Synchronized fun fail(error: Throwable) {
        if (!isComplete) {
            failure = error
            latch.countDown()
        }
    }

    @Throws(MediaException::class)
    fun await(): T {
        if (!isComplete && Looper.myLooper() == Looper.getMainLooper()) {
            fail(MediaException("Camera operations cannot wait on the Android UI thread"))
        }
        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                fail(MediaException("Camera operation timed out"))
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(MediaException("Camera operation was interrupted"))
        }
        failure?.let {
            when (it) {
                is SecurityException -> throw it
                is IllegalStateException -> throw it
                is IllegalArgumentException -> throw it
                is MediaException -> throw it
                is Error -> throw it
                else -> throw MediaException("Camera operation failed: ${it.message}")
            }
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
