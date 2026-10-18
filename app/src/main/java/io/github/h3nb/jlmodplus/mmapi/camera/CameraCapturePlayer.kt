// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.media.Control
import javax.microedition.media.Manager
import javax.microedition.media.MediaException
import javax.microedition.media.Player
import javax.microedition.media.PlayerListener
import javax.microedition.media.TimeBase
import javax.microedition.shell.MidletSystem
import io.github.h3nb.jlmodplus.mmapi.video.VideoDisplay

/** JSR-135 live source. No recorder, audio output, or file decoder participates. */
class CameraCapturePlayer @Throws(MediaException::class) constructor(locator: String) : Player {
    private val defaultDimensions = CameraRuntime.defaultDimensions
    internal val defaultFacing = CameraRuntime.defaultFacing
    internal val source = CameraCaptureOptions.parseLocator(locator, defaultDimensions)
    internal val runtimeGeneration = CameraRuntime.generation()
    private val lock = Any()
    private val closed = AtomicBoolean()
    @Volatile private var state = Player.UNREALIZED
    private var elapsedMicros = 0L
    private var startedAtNanos = 0L
    private val listeners = CopyOnWriteArrayList<PlayerListener>()
    private val events = Executors.newSingleThreadExecutor { action ->
        Thread(action, "CameraPlayerEvents").apply { isDaemon = true }
    }
    private val calls = ConcurrentHashMap<CameraCall<*>, Boolean>()
    internal var previewContainer: FrameLayout? = null // Android main thread only
    internal val display = VideoDisplay(source.width, source.height, object : VideoDisplay.Content {
        override fun createView(context: Context): View = CameraRuntime.createPreview(this@CameraCapturePlayer, context)
        override fun detachView(view: View) = CameraRuntime.detachPreview(this@CameraCapturePlayer, view)
        override fun getSnapshot(type: String?): ByteArray = snapshot(type)
    }).apply { setSizeListener { postEvent(PlayerListener.SIZE_CHANGED, this) } }

    init { CameraRuntime.register(this) }

    internal fun checkOpen() {
        if (closed.get()) throw IllegalStateException("Camera Player is closed")
    }

    private fun checkRealized() {
        checkOpen()
        if (state == Player.UNREALIZED) throw IllegalStateException("Camera Player is unrealized")
    }

    override fun realize() = synchronized(lock) {
        checkOpen()
        if (state == Player.UNREALIZED) state = Player.REALIZED
    }

    @Throws(MediaException::class)
    override fun prefetch() {
        realize()
        if (state >= Player.PREFETCHED) return
        CameraRuntime.prepared(this, false)
    }

    @Throws(MediaException::class)
    override fun start() {
        realize()
        if (state == Player.STARTED) return
        CameraRuntime.prepared(this, true)
    }

    internal fun markPrepared(start: Boolean, call: CameraCall<Unit>) = synchronized(lock) {
        if (call.isComplete) return@synchronized
        checkOpen()
        if (state < Player.PREFETCHED) state = Player.PREFETCHED
        if (start && state != Player.STARTED) {
            startedAtNanos = System.nanoTime()
            state = Player.STARTED
            postEvent(PlayerListener.STARTED, mediaTimeLocked())
        }
        call.complete(Unit)
    }

    override fun stop() {
        checkOpen()
        synchronized(lock) {
            checkOpen()
            cancelCalls("Camera Player was stopped")
            if (state == Player.STARTED) {
                elapsedMicros = mediaTimeLocked()
                state = Player.PREFETCHED
                postEvent(PlayerListener.STOPPED, elapsedMicros)
            }
        }
        CameraRuntime.playerStopped(this)
    }

    override fun deallocate() {
        checkOpen()
        stop()
        synchronized(lock) { checkOpen(); if (state >= Player.PREFETCHED) state = Player.REALIZED }
        CameraRuntime.release(this)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelCalls("Camera Player was closed")
        synchronized(lock) { state = Player.CLOSED }
        CameraRuntime.release(this)
        display.close()
        postEvent(PlayerListener.CLOSED, null)
        events.shutdown()
    }

    internal fun fail(message: String) {
        if (closed.get()) return
        postEvent(PlayerListener.ERROR, message)
        close()
    }

    private fun snapshot(type: String?): ByteArray {
        checkRealized()
        // The first published encoding is the default, including an explicit profile override.
        val selected = type ?: MidletSystem.getProperty("video.snapshot.encodings")
            ?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() }
        val options = CameraCaptureOptions.parseSnapshot(selected, defaultDimensions)
        prefetch()
        return CameraRuntime.snapshot(this, options)
    }

    internal fun <T> waitFor(call: CameraCall<T>, action: () -> Unit): T {
        checkOpen()
        calls[call] = true
        try {
            if (closed.get()) call.fail(IllegalStateException("Camera Player is closed"))
            if (!call.isComplete) action()
            return call.await()
        } finally {
            calls.remove(call)
            CameraRuntime.discardCompletedCalls()
        }
    }

    internal fun cancelCalls(message: String) {
        for (call in calls.keys) call.fail(MediaException(message))
    }

    internal fun hostSuspended() = synchronized(lock) {
        if (state == Player.STARTED && startedAtNanos != 0L) {
            elapsedMicros = mediaTimeLocked()
            startedAtNanos = 0L
        }
        cancelCalls("Camera host is not active")
    }

    internal fun hostResumed() = synchronized(lock) {
        if (state == Player.STARTED && startedAtNanos == 0L) startedAtNanos = System.nanoTime()
    }

    private fun mediaTimeLocked(): Long = elapsedMicros +
        if (state == Player.STARTED && startedAtNanos != 0L) (System.nanoTime() - startedAtNanos) / 1000 else 0

    internal fun postEvent(event: String, value: Any?) {
        if (closed.get() && event != PlayerListener.CLOSED && event != PlayerListener.ERROR) return
        val recipients = listeners.toTypedArray()
        try {
            events.execute {
                for (listener in recipients) {
                    try { listener.playerUpdate(this, event, value) }
                    catch (error: Exception) { android.util.Log.w("CameraPlayer", "Player listener failed", error) }
                }
            }
        } catch (_: RejectedExecutionException) { /* CLOSED has already ended event delivery. */ }
    }

    override fun getState(): Int = if (closed.get()) Player.CLOSED else state
    override fun getMediaTime(): Long = synchronized(lock) { checkOpen(); mediaTimeLocked() }
    override fun getDuration(): Long { checkOpen(); return Player.TIME_UNKNOWN }
    override fun getContentType(): String { checkRealized(); return "video/raw" }
    override fun setMediaTime(now: Long): Long { checkRealized(); throw MediaException("Live camera cannot seek") }
    override fun getTimeBase(): TimeBase { checkRealized(); return Manager.getSystemTimeBase() }
    override fun setTimeBase(master: TimeBase?) {
        checkRealized()
        if (state == Player.STARTED) throw IllegalStateException("Camera Player is started")
        if (master != null && master !== Manager.getSystemTimeBase()) throw MediaException("Camera uses the system time base")
    }
    override fun setLoopCount(count: Int) {
        checkOpen()
        if (state == Player.STARTED) throw IllegalStateException("Camera Player is started")
        if (count == 0 || count < -1) throw IllegalArgumentException("Invalid loop count")
        // A live source has no end of media to loop over.
    }
    override fun getControl(type: String?): Control? {
        checkRealized()
        requireNotNull(type) { "Control name must not be null" }
        return when (type) {
            "VideoControl", "GUIControl", "javax.microedition.media.control.VideoControl", "javax.microedition.media.control.GUIControl" -> display
            else -> null
        }
    }
    override fun getControls(): Array<Control> { checkRealized(); return arrayOf(display) }
    override fun addPlayerListener(listener: PlayerListener?) { checkOpen(); if (listener != null) listeners.addIfAbsent(listener) }
    override fun removePlayerListener(listener: PlayerListener?) { checkOpen(); if (listener != null) listeners.remove(listener) }
}
