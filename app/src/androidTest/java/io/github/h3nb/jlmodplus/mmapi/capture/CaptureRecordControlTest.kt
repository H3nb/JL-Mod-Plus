// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import javax.microedition.media.MediaException
import javax.microedition.media.Player
import javax.microedition.media.PlayerListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureRecordControlTest {
    private class Stub : CaptureRecordControl.Backend {
        override val contentType = "audio/wav"
        var lastFile: File? = null
        var failPause = false
        val actions = ArrayList<String>()
        override fun begin(file: File) {
            lastFile = file
            actions.add("begin")
            file.writeBytes(byteArrayOf(1, 2, 3))
        }
        override fun pause() {
            actions.add("pause")
            if (failPause) throw MediaException("Capture device disconnected")
        }
        override fun resume() { actions.add("resume") }
        override fun finish() { actions.add("finish") }
        override fun abandon() { actions.add("abandon") }
    }

    @Test fun armingPausingResumingAndCommitUseOneOutput() {
        val stub = Stub()
        var state = Player.REALIZED
        val received = ArrayList<String>()
        val bytes = ByteArrayOutputStream()
        val control = CaptureRecordControl(stub, { state }, { 1200L },
            { kind, _ -> received.add(kind) })
        control.setRecordStream(bytes)
        control.startRecord()
        assertTrue(stub.actions.isEmpty())
        state = Player.STARTED
        control.playerStarted()
        state = Player.PREFETCHED
        control.playerStopped()
        state = Player.STARTED
        control.playerStarted()
        control.stopRecord()
        control.startRecord()
        control.commit()
        assertEquals(listOf("begin", "pause", "resume", "pause", "resume", "pause",
            "finish", "abandon"), stub.actions)
        assertEquals(listOf(PlayerListener.RECORD_STARTED, PlayerListener.RECORD_STOPPED,
            PlayerListener.RECORD_STARTED, PlayerListener.RECORD_STOPPED,
            PlayerListener.RECORD_STARTED, PlayerListener.RECORD_STOPPED), received)
        assertTrue(bytes.toByteArray().contentEquals(byteArrayOf(1, 2, 3)))
        control.close()
    }

    @Test fun resetPreservesDestinationButCommitClearsIt() {
        val bytes = ByteArrayOutputStream()
        val control = CaptureRecordControl(Stub(), { Player.STARTED }, { 0L }, { _, _ -> })
        control.setRecordStream(bytes)
        control.startRecord()
        control.reset()
        control.startRecord()
        control.commit()
        assertEquals(3, bytes.size())
        try {
            control.startRecord()
            throw AssertionError("Must set another destination after commit")
        } catch (_: IllegalStateException) {}
        control.close()
    }

    @Test fun backendFailureIsReportedOnceAndRejectedUntilReset() {
        val stub = Stub()
        val events = ArrayList<String>()
        val bytes = ByteArrayOutputStream()
        val control = CaptureRecordControl(stub, { Player.STARTED }, { 0L },
            { kind, _ -> events.add(kind) })
        control.setRecordStream(bytes)
        control.startRecord()
        val failedFile = stub.lastFile!!
        control.backendFailed(failedFile, "Device disconnected")
        control.backendFailed(failedFile, "Stale failure")
        assertEquals(1, events.count { it == PlayerListener.RECORD_ERROR })
        try {
            control.startRecord()
            throw AssertionError("A failed recording cannot resume without reset")
        } catch (_: IllegalStateException) {}
        control.reset()
        control.startRecord()
        control.commit()
        assertEquals(3, bytes.size())
        control.close()
    }

    @Test fun failedCameraDeallocationCannotSilentlyCommitOrResume() {
        val stub = Stub()
        val events = ArrayList<String>()
        val control = CaptureRecordControl(stub, { Player.STARTED }, { 0L },
            { kind, _ -> events.add(kind) })
        control.setRecordStream(ByteArrayOutputStream())
        control.startRecord()
        control.playerStopped()
        control.cameraDeallocated()
        assertEquals(1, events.count { it == PlayerListener.RECORD_ERROR })
        try {
            control.commit()
            throw AssertionError("A lost video session is not a successful MP4")
        } catch (_: java.io.IOException) {}
        try {
            control.startRecord()
            throw AssertionError("Commit must clear the recording destination")
        } catch (_: IllegalStateException) {}
        control.close()
    }

    @Test fun synchronousPauseFailureEmitsOneErrorAndInvalidatesOutput() {
        val stub = Stub()
        stub.failPause = true
        val events = ArrayList<String>()
        val control = CaptureRecordControl(stub, { Player.STARTED }, { 0L },
            { kind, _ -> events.add(kind) })
        control.setRecordStream(ByteArrayOutputStream())
        control.startRecord()
        try {
            control.stopRecord()
            throw AssertionError("A failed pause must be propagated")
        } catch (_: IllegalStateException) {}
        control.backendFailed(stub.lastFile!!, "Late duplicate")
        assertEquals(1, events.count { it == PlayerListener.RECORD_ERROR })
        try {
            control.commit()
            throw AssertionError("A failed capture must not commit")
        } catch (_: java.io.IOException) {}
        assertEquals(1, events.count { it == PlayerListener.RECORD_ERROR })
        control.close()
    }

    @Test fun audioPlayerEnforcesRealizationAndClosedState() {
        val player = AudioCapturePlayer()
        try {
            try { player.getContentType(); throw AssertionError("Player unrealized") }
            catch (_: IllegalStateException) {}
            try { player.getTimeBase(); throw AssertionError("Player unrealized") }
            catch (_: IllegalStateException) {}
            try { player.setTimeBase(null); throw AssertionError("Player unrealized") }
            catch (_: IllegalStateException) {}
            player.realize()
            assertEquals("audio/raw", player.getContentType())
        } finally { player.close() }
        try { player.deallocate(); throw AssertionError("Player closed") }
        catch (_: IllegalStateException) {}
    }

    @Test fun runtimeTeardownClosesUnreleasedAudioPlayers() {
        val player = AudioCapturePlayer()
        player.realize()
        AudioCapturePlayer.closeForRuntime()
        assertEquals(Player.CLOSED, player.getState())
    }

    @Test fun unsupportedLimitIsReported() {
        val control = CaptureRecordControl(Stub(), { Player.REALIZED }, { 0L }, { _, _ -> })
        try {
            control.setRecordSizeLimit(512)
            throw AssertionError("Expected unsupported limit")
        } catch (_: MediaException) {}
        try {
            control.setRecordSizeLimit(0)
            throw AssertionError("Expected invalid argument")
        } catch (_: IllegalArgumentException) {}
        assertEquals("audio/wav", control.getContentType())
        control.close()
    }
}
