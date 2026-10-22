// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.capture

import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.microedition.io.Connector
import javax.microedition.media.MediaException
import javax.microedition.media.Player
import javax.microedition.media.PlayerListener
import javax.microedition.media.control.RecordControl

/** The only recording state owner; Player lifecycle and recorder state are distinct. */
internal class CaptureRecordControl(
    private val backend: Backend,
    private val playerState: () -> Int,
    private val mediaTime: () -> Long,
    private val event: (String, Any?) -> Unit,
) : RecordControl {
    interface Backend {
        val contentType: String
        @Throws(MediaException::class) fun begin(file: File)
        @Throws(MediaException::class) fun pause()
        @Throws(MediaException::class) fun resume()
        @Throws(IOException::class) fun finish()
        fun abandon()
    }

    private var destination: OutputStream? = null
    private var ownsDestination = false
    private var armed = false
    private var running = false
    private var begun = false
    private var scratch: File? = null
    private var closed = false
    private var failureMessage: String? = null

    override fun getContentType(): String = backend.contentType

    @Synchronized override fun setRecordStream(stream: OutputStream?) {
        requireNotNull(stream) { "Record stream must not be null" }
        checkAvailable()
        check(destination == null) { "Recording destination is already configured" }
        destination = stream
        ownsDestination = false
    }

    @Synchronized override fun setRecordLocation(locator: String?) {
        requireNotNull(locator) { "Recording locator must not be null" }
        checkAvailable()
        check(destination == null) { "Recording destination is already configured" }
        // The Java ME Connector enforces virtual filesystem permissions, not Android paths.
        val output = Connector.openOutputStream(locator)
        destination = output
        ownsDestination = true
    }

    @Synchronized override fun startRecord() {
        checkAvailable()
        check(destination != null) { "Recording destination is not set" }
        check(failureMessage == null) { "Recording failed; reset before starting again" }
        if (armed) return
        armed = true
        if (playerState() == Player.STARTED) activate()
    }

    @Synchronized override fun stopRecord() {
        if (closed || !armed) return
        armed = false
        suspendCapture()
    }

    /** Called on the MIDlet thread, after the Player has transitioned to STARTED. */
    @Synchronized fun playerStarted() {
        if (armed && !running) activate()
    }

    /** Player.stop() pauses capture but preserves the intent to resume it. */
    @Synchronized fun playerStopped() = suspendCapture()

    private fun activate() {
        try {
            if (!begun) {
                val file = File.createTempFile("midlet-record-", ".tmp",
                    javax.microedition.util.ContextHolder.getAppContext().cacheDir)
                scratch = file
                backend.begin(file)
                begun = true
            } else {
                backend.resume()
            }
            running = true
            event(PlayerListener.RECORD_STARTED, mediaTime())
        } catch (failure: Exception) {
            armed = false
            running = false
            try { backend.abandon() } catch (_: Exception) {}
            scratch?.delete()
            scratch = null
            begun = false
            event(PlayerListener.RECORD_ERROR, failure.message ?: "Recording failed")
            throw if (failure is SecurityException) failure else
                IllegalStateException("Could not start capture", failure)
        }
    }

    private fun suspendCapture() {
        if (!running) return
        try {
            backend.pause()
            running = false
            event(PlayerListener.RECORD_STOPPED, mediaTime())
        } catch (failure: Exception) {
            val reason = failure.message ?: "Recording interrupted"
            armed = false
            running = false
            val file = scratch
            if (file != null) backendFailed(file, reason)
            else event(PlayerListener.RECORD_ERROR, reason)
            throw IllegalStateException("Could not pause capture", failure)
        }
    }

    @Synchronized override fun commit() {
        if (closed) throw IllegalStateException("Player is closed")
        if (destination == null) return
        try {
            armed = false
            failureMessage?.let { throw IOException("Recording failed: $it") }
            suspendCapture()
            if (begun) {
                backend.finish()
                val file = scratch ?: throw IOException("Missing recording output")
                if (!file.isFile || file.length() == 0L) throw IOException("Empty recording")
                file.inputStream().use { it.copyTo(destination!!) }
                destination!!.flush()
            }
        } catch (failure: Exception) {
            // A previously delivered backend failure must not emit a duplicate.
            if (failureMessage == null)
                event(PlayerListener.RECORD_ERROR, failure.message ?: "Recording commit failed")
            throw if (failure is IOException) failure else IOException("Recording commit failed", failure)
        } finally {
            clearSession()
        }
    }

    @Synchronized override fun reset() {
        if (closed) return
        armed = false
        try {
            suspendCapture()
        } finally {
            backend.abandon()
            scratch?.delete()
            scratch = null
            begun = false
            running = false
            failureMessage = null
        }
        // A successful reset keeps the previously selected destination.
    }

    @Synchronized override fun setRecordSizeLimit(size: Int): Int {
        require(size > 0) { "Record size limit must be positive" }
        // JSR-135 explicitly permits rejecting unsupported limits.
        throw MediaException("Record size limits are not supported")
    }

    /**
     * Called outside the guest recording operation. A matching scratch file
     * rejects late platform callbacks from an older session.
     */
    @Synchronized fun backendFailed(file: File, reason: String) {
        if (closed || scratch != file || failureMessage != null) return
        failureMessage = reason
        armed = false
        running = false
        begun = false
        try { backend.abandon() } catch (_: Exception) {}
        scratch?.delete()
        scratch = null
        event(PlayerListener.RECORD_ERROR, reason)
    }

    /** CameraX cannot preserve an active encoder across hardware deallocation. */
    @Synchronized fun cameraDeallocated() {
        scratch?.let { backendFailed(it, "Video recording interrupted by deallocate") }
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        armed = false
        clearSession()
    }

    private fun clearSession() {
        try { if (ownsDestination) destination?.close() } catch (_: IOException) {}
        destination = null
        ownsDestination = false
        scratch?.delete()
        scratch = null
        begun = false
        running = false
        armed = false
        failureMessage = null
        try { backend.abandon() } catch (_: Exception) {}
    }

    private fun checkAvailable() {
        check(!closed) { "Player is closed" }
    }
}
