// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.capture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.media.MediaException
import javax.microedition.util.ContextHolder

/** The Android grant is requested on the host; the MIDlet thread waits, never the UI thread. */
internal object MicrophoneAccess {
    fun require() {
        val host = ContextHolder.getActivity() ?: throw MediaException("No active MIDlet host")
        if (ContextCompat.checkSelfPermission(host, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw MediaException("Microphone permission cannot be awaited on the UI thread")
        }
        val ready = CountDownLatch(1)
        var granted = false
        host.runOnUiThread {
            host.requestRecordingAudioPermission { accepted ->
                granted = accepted
                ready.countDown()
            }
        }
        try {
            if (!ready.await(60, TimeUnit.SECONDS)) throw MediaException("Microphone permission timed out")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MediaException("Microphone permission interrupted")
        }
        if (!granted) throw SecurityException("Microphone permission denied")
    }
}
