// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import java.io.File
import java.io.IOException
import io.github.h3nb.jlmodplus.mmapi.capture.CaptureRecordControl
import io.github.h3nb.jlmodplus.mmapi.capture.MicrophoneAccess

/** Thin adapter: the existing CameraRuntime continues to own every CameraX use case. */
internal class CameraVideoRecorder(
    private val player: CameraCapturePlayer,
    private val microphone: Boolean,
) : CaptureRecordControl.Backend {
    override val contentType = "video/mp4"

    override fun begin(file: File) {
        if (microphone) MicrophoneAccess.require()
        CameraRuntime.beginRecording(player, file, microphone)
    }
    override fun pause() = CameraRuntime.pauseRecording(player)
    override fun resume() = CameraRuntime.resumeRecording(player)
    @Throws(IOException::class)
    override fun finish() = CameraRuntime.finishRecording(player)
    override fun abandon() = CameraRuntime.abandonRecording(player)
}
