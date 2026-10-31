/* Licensed under the Apache License, Version 2.0 (the "License"); */
package io.github.h3nb.jlmodplus.settings

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class RuntimeChromeState(val fullscreen: Boolean, val showStatusBar: Boolean)

/** Async lifecycle-bound access to the main-process runtime chrome preferences. */
class RuntimeChromeClient(context: Context, private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun onChromeChanged(state: RuntimeChromeState)
        fun onChromeError(failure: Exception)
    }

    private val resolver = context.applicationContext.contentResolver
    private val uri = RuntimeChromeProvider.uri(context.packageName)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var started = false
    // Registration is owned by the serial worker, including teardown.
    private var registered = false
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (started) request(RuntimeChromeProvider.READ, null)
        }
    }

    @MainThread
    fun start() {
        if (started) return
        started = true
        request(RuntimeChromeProvider.READ, null)
    }

    @MainThread
    fun setFullscreen(fullscreen: Boolean) {
        check(started) { "Start runtime chrome client before writing" }
        request(RuntimeChromeProvider.WRITE_FULLSCREEN,
            Bundle().apply { putBoolean(RuntimeChromeProvider.FULLSCREEN, fullscreen) })
    }

    @MainThread
    fun setShowStatusBar(showStatusBar: Boolean) {
        check(started) { "Start runtime chrome client before writing" }
        request(RuntimeChromeProvider.WRITE_STATUS_BAR,
            Bundle().apply { putBoolean(RuntimeChromeProvider.SHOW_STATUS_BAR, showStatusBar) })
    }

    private fun request(method: String, extras: Bundle?) {
        val token = generation.get()
        worker.execute {
            try {
                if (!registered && token == generation.get()) {
                    resolver.registerContentObserver(uri, false, observer)
                    registered = true
                }
                // Accepted writes complete even after stop; stale reads and callbacks are discarded.
                if (method == RuntimeChromeProvider.READ && token != generation.get()) return@execute
                val result = resolver.call(uri, method, null, extras)
                if (result == null || !result.containsKey(RuntimeChromeProvider.FULLSCREEN)
                    || !result.containsKey(RuntimeChromeProvider.SHOW_STATUS_BAR)) {
                    throw IOException("Runtime chrome authority returned no value")
                }
                val state = RuntimeChromeState(result.getBoolean(RuntimeChromeProvider.FULLSCREEN),
                    result.getBoolean(RuntimeChromeProvider.SHOW_STATUS_BAR))
                main.post {
                    if (token == generation.get()) listener.onChromeChanged(state)
                }
            } catch (failure: Exception) {
                main.post {
                    if (token == generation.get()) listener.onChromeError(failure)
                }
            }
        }
    }

    @MainThread
    override fun close() {
        if (!started) return
        started = false
        generation.incrementAndGet()
        worker.execute {
            if (registered) {
                resolver.unregisterContentObserver(observer)
                registered = false
            }
        }
    }

    private companion object {
        // One ordered queue per process prevents local reads overtaking local writes.
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "runtime-chrome").apply { isDaemon = true }
        }
    }
}
