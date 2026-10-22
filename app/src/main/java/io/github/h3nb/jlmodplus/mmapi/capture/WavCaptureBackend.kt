// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.capture

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.media.MediaException
import javax.microedition.util.ContextHolder

/** Mono PCM capture: no native encoder or SDK-24 pause dependency. */
internal class WavCaptureBackend : CaptureRecordControl.Backend {
    override val contentType = "audio/wav"
    private val guard = Any()
    private var device: AudioRecord? = null
    private var output: RandomAccessFile? = null
    private var reader: Thread? = null
    @Volatile private var capturing = false
    @Volatile private var readError: IOException? = null
    private var samples = 0L

    override fun begin(file: File) {
        MicrophoneAccess.require()
        try {
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw MediaException("Microphone format unavailable")
            val record = AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 2, 4096))
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw MediaException("Microphone could not be initialized")
            }
            device = record
            val writer = RandomAccessFile(file, "rw")
            output = writer
            writer.setLength(0)
            writer.write(ByteArray(44))
            samples = 0
            readError = null
            resume()
        } catch (failure: Exception) {
            abandon()
            throw if (failure is MediaException) failure else MediaException(
                "Microphone initialization failed: " + failure.message)
        }
    }

    override fun resume() {
        if (capturing) return
        readError?.let { throw MediaException("Microphone read failed: " + it.message) }
        val record = device ?: throw MediaException("Microphone is unavailable")
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw MediaException("Microphone could not start")
            }
            capturing = true
            reader = Thread({
                val bytes = ByteArray(4096)
                try {
                    while (capturing) {
                        val count = record.read(bytes, 0, bytes.size)
                        if (!capturing) break
                        if (count < 0) throw IOException("Microphone read error " + count)
                        if (count > 0) synchronized(guard) {
                            if (samples + count > MAX_PCM_BYTES) {
                                throw IOException("Recording exceeded its temporary storage limit")
                            }
                            output?.write(bytes, 0, count)
                            samples += count
                        }
                    }
                } catch (failure: Exception) {
                    readError = if (failure is IOException) failure else IOException(failure)
                    capturing = false
                }
            }, "MidletAudioCapture").apply { isDaemon = true; start() }
        } catch (failure: Exception) {
            capturing = false
            throw if (failure is MediaException) failure else MediaException(
                "Microphone could not start: " + failure.message)
        }
    }

    override fun pause() {
        if (!capturing && reader == null) {
            readError?.let { throw MediaException("Microphone capture failed: " + it.message) }
            return
        }
        capturing = false
        try {
            if (device?.recordingState == AudioRecord.RECORDSTATE_RECORDING) device?.stop()
        } catch (_: IllegalStateException) {}
        val active = reader
        if (active != null && active !== Thread.currentThread()) {
            try { active.join(3000) } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw MediaException("Interrupted while pausing microphone")
            }
            if (active.isAlive) throw MediaException("Microphone capture did not stop")
        }
        reader = null
        readError?.let { throw MediaException("Microphone capture failed: " + it.message) }
    }

    override fun finish() {
        try {
            pause()
            val writer = output ?: throw IOException("Missing audio output")
            synchronized(guard) {
                writer.seek(0)
                writeHeader(writer, samples)
                writer.fd.sync()
            }
        } catch (failure: Exception) {
            throw if (failure is IOException) failure else IOException("Could not finish WAV recording", failure)
        } finally {
            abandon()
        }
    }

    override fun abandon() {
        capturing = false
        try { if (device?.recordingState == AudioRecord.RECORDSTATE_RECORDING) device?.stop() }
        catch (_: Exception) {}
        val active = reader
        if (active != null && active !== Thread.currentThread()) {
            try { active.join(3000) } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        reader = null
        device?.release()
        device = null
        try { output?.close() } catch (_: IOException) {}
        output = null
    }

    private fun writeHeader(writer: RandomAccessFile, length: Long) {
        if (length > Int.MAX_VALUE - 36L) throw IOException("WAV recording exceeds RIFF limit")
        writer.writeBytes("RIFF")
        le32(writer, (length + 36).toInt())
        writer.writeBytes("WAVEfmt ")
        le32(writer, 16)
        le16(writer, 1)
        le16(writer, 1)
        le32(writer, RATE)
        le32(writer, RATE * 2)
        le16(writer, 2)
        le16(writer, 16)
        writer.writeBytes("data")
        le32(writer, length.toInt())
    }

    private fun le16(file: RandomAccessFile, value: Int) {
        file.write(value and 255); file.write((value ushr 8) and 255)
    }

    private fun le32(file: RandomAccessFile, value: Int) {
        le16(file, value and 65535); le16(file, value ushr 16)
    }

    companion object {
        private const val RATE = 16000
        private const val MAX_PCM_BYTES = 64L * 1024 * 1024
    }
}
