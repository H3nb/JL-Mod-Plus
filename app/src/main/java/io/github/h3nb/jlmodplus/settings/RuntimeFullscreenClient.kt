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

/** Async, lifecycle-bound access to the main process's global fullscreen preference. */
class RuntimeFullscreenClient(context: Context, private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun onFullscreenChanged(fullscreen: Boolean)
        fun onFullscreenError(failure: Exception)
    }

    private val resolver = context.applicationContext.contentResolver
    private val uri = RuntimeFullscreenProvider.uri(context.packageName)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private var started = false
    // Registration is owned by the serial worker, including local-provider calls and teardown.
    private var registered = false
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (started) request(RuntimeFullscreenProvider.READ, null)
        }
    }

    @MainThread
    fun start() {
        if (started) return
        started = true
        request(RuntimeFullscreenProvider.READ, null)
    }

    @MainThread
    fun setFullscreen(fullscreen: Boolean) {
        check(started) { "Start fullscreen client before writing" }
        request(RuntimeFullscreenProvider.WRITE,
            Bundle().apply { putBoolean(RuntimeFullscreenProvider.VALUE, fullscreen) })
    }

    private fun request(method: String, extras: Bundle?) {
        val token = generation.get()
        worker.execute {
            try {
                if (!registered && token == generation.get()) {
                    resolver.registerContentObserver(uri, false, observer)
                    registered = true
                }
                // Accepted writes finish even when their caller stops; stale reads can be dropped.
                if (method == RuntimeFullscreenProvider.READ && token != generation.get()) {
                    return@execute
                }
                val result = resolver.call(uri, method, null, extras)
                if (result == null || !result.containsKey(RuntimeFullscreenProvider.VALUE)) {
                    throw IOException("Fullscreen authority returned no value")
                }
                val fullscreen = result.getBoolean(RuntimeFullscreenProvider.VALUE)
                main.post {
                    if (token == generation.get()) listener.onFullscreenChanged(fullscreen)
                }
            } catch (failure: Exception) {
                main.post {
                    if (token == generation.get()) listener.onFullscreenError(failure)
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
        // One ordered queue per process prevents local reads from overtaking local writes.
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "runtime-fullscreen").apply { isDaemon = true }
        }
    }
}
