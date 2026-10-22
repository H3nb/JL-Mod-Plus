// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.capture

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.microedition.media.Control
import javax.microedition.media.Manager
import javax.microedition.media.MediaException
import javax.microedition.media.Player
import javax.microedition.media.PlayerListener
import javax.microedition.media.TimeBase

/** Capture-only Player. No audio playback service or global output audio focus. */
open class AudioCapturePlayer : Player {
    companion object {
        private val live = java.util.Collections.newSetFromMap(
            ConcurrentHashMap<AudioCapturePlayer, Boolean>())
        private val hostPauses = Executors.newSingleThreadExecutor { task ->
            Thread(task, "MidletMicHostPause").apply { isDaemon = true }
        }
        /** The runtime owns Players even when a MIDlet forgets Player.close(). */
        @JvmStatic fun closeForRuntime() {
            for (player in live.toList()) {
                try { player.close() } catch (_: Exception) {}
            }
        }
        @JvmStatic fun pauseForHost() {
            hostPauses.execute {
                for (player in live) {
                    try { if (player.getState() == Player.STARTED) player.stop() }
                    catch (_: Exception) {}
                }
            }
        }
    }
    init { live.add(this) }
    private val listeners = CopyOnWriteArrayList<PlayerListener>()
    private val callbacks = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MidletAudioEvents").apply { isDaemon = true }
    }
    @Volatile private var state = Player.UNREALIZED
    @Volatile private var elapsedMicros = 0L
    @Volatile private var startedAt = 0L
    private val backend = WavCaptureBackend { file, reason -> reportCaptureError(file, reason) }
    private val recorder = CaptureRecordControl(backend, { state },
        { getMediaTime() }, { kind, data -> event(kind, data) })

    private fun reportCaptureError(file: File, reason: String) {
        try { callbacks.execute { recorder.backendFailed(file, reason) } }
        catch (_: RejectedExecutionException) { /* Closed session. */ }
    }

    @Synchronized override fun realize() {
        checkOpen()
        if (state == Player.UNREALIZED) state = Player.REALIZED
    }

    @Synchronized override fun prefetch() {
        realize()
        if (state < Player.PREFETCHED) state = Player.PREFETCHED
    }

    @Synchronized override fun start() {
        prefetch()
        if (state == Player.STARTED) return
        startedAt = System.nanoTime()
        state = Player.STARTED
        try { recorder.playerStarted() }
        catch (failure: Exception) {
            elapsedMicros = mediaTime()
            state = Player.PREFETCHED
            throw if (failure is SecurityException) failure else
                if (failure is MediaException) failure else MediaException(
                "Microphone Player could not start: " + failure.message)
        }
        event(PlayerListener.STARTED, mediaTime())
    }

    @Synchronized override fun stop() {
        checkOpen()
        if (state != Player.STARTED) return
        try { recorder.playerStopped() }
        finally {
            elapsedMicros = mediaTime()
            startedAt = 0
            state = Player.PREFETCHED
            event(PlayerListener.STOPPED, elapsedMicros)
        }
    }

    @Synchronized override fun deallocate() {
        checkOpen()
        try { stop() } finally {
            // Even a failed pause must release the exclusive input hardware.
            try { backend.deallocate() }
            finally { if (state >= Player.PREFETCHED) state = Player.REALIZED }
        }
    }

    @Synchronized override fun close() {
        if (state == Player.CLOSED) return
        recorder.close()
        live.remove(this)
        state = Player.CLOSED
        event(PlayerListener.CLOSED, null)
        callbacks.shutdown()
    }

    private fun checkOpen() {
        check(state != Player.CLOSED) { "Player is closed" }
    }

    private fun checkRealized() {
        checkOpen()
        check(state != Player.UNREALIZED) { "Player is unrealized" }
    }

    private fun mediaTime(): Long = elapsedMicros + if (state == Player.STARTED)
        (System.nanoTime() - startedAt) / 1000 else 0

    override fun getMediaTime(): Long { checkOpen(); return mediaTime() }
    override fun getDuration(): Long { checkOpen(); return Player.TIME_UNKNOWN }
    override fun getContentType(): String { checkRealized(); return "audio/raw" }
    override fun getState() = state
    override fun getTimeBase(): TimeBase { checkRealized(); return Manager.getSystemTimeBase() }
    override fun setTimeBase(master: TimeBase?) {
        checkRealized()
        if (state == Player.STARTED) throw IllegalStateException("Player started")
        if (master != null && master !== Manager.getSystemTimeBase()) throw MediaException("Unsupported time base")
    }
    override fun setMediaTime(now: Long): Long { checkRealized(); throw MediaException("Live source cannot seek") }
    override fun setLoopCount(count: Int) {
        checkOpen()
        if (state == Player.STARTED) throw IllegalStateException("Player started")
        require(count == -1 || count > 0)
    }
    override fun getControl(type: String?): Control? {
        checkOpen()
        if (state == Player.UNREALIZED) throw IllegalStateException("Player unrealized")
        return when (type) {
            "RecordControl", "javax.microedition.media.control.RecordControl" -> recorder
            else -> null
        }
    }
    override fun getControls(): Array<Control> {
        checkOpen()
        if (state == Player.UNREALIZED) throw IllegalStateException("Player unrealized")
        return arrayOf(recorder)
    }
    override fun addPlayerListener(listener: PlayerListener?) {
        checkOpen()
        if (listener != null) listeners.addIfAbsent(listener)
    }
    override fun removePlayerListener(listener: PlayerListener?) {
        checkOpen()
        if (listener != null) listeners.remove(listener)
    }
    private fun event(kind: String, value: Any?) {
        try {
            callbacks.execute {
                for (listener in listeners) {
                    try { listener.playerUpdate(this, kind, value) }
                    catch (_: Exception) { }
                }
            }
        } catch (_: RejectedExecutionException) { }
    }
}
