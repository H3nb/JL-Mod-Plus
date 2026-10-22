// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.microedition.media.Manager
import javax.microedition.media.MediaException
import javax.microedition.media.Player
import javax.microedition.media.PlayerListener
import javax.microedition.media.TimeBase
import javax.microedition.lcdui.Displayable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** API/lifecycle coverage without opening an Android camera or requesting permission. */
@RunWith(AndroidJUnit4::class)
class CameraCapturePlayerTest {
    @Test
    fun sessionDefaultsRemainStableAcrossVirtualDisplayChangesAndExplicitSources() {
        val oldWidth = Displayable.getVirtualWidth()
        val oldHeight = Displayable.getVirtualHeight()
        CameraRuntime.beginRuntime(CameraCaptureOptions.defaultsForScreen(240, 320), CameraCaptureOptions.FRONT)
        try {
            Displayable.setVirtualSize(320, 240)
            val player = CameraCapturePlayer("capture://video")
            try {
                player.realize()
                assertEquals(480, player.display.sourceWidth)
                assertEquals(640, player.display.sourceHeight)
                assertEquals(CameraCaptureOptions.FRONT, player.defaultFacing)
                assertNull(player.source.facing)
            } finally { player.close() }
            val explicit = CameraCapturePlayer("capture://video?width=320&height=240")
            try {
                explicit.realize()
                assertEquals(320, explicit.display.sourceWidth)
                assertEquals(240, explicit.display.sourceHeight)
            } finally { explicit.close() }
            val named = CameraCapturePlayer("capture://devcam0")
            try {
                assertEquals(CameraCaptureOptions.BACK, named.source.facing)
                assertEquals(CameraCaptureOptions.BACK,
                    CameraCaptureOptions.selectFacing(named.source.facing, named.defaultFacing, true, true))
            } finally { named.close() }
        } finally { Displayable.setVirtualSize(oldWidth, oldHeight) }
    }

    @Before
    fun startRuntime() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { CameraRuntime.beginRuntime() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun closeRuntime() {
        CameraRuntime.closeRuntime()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun legacyStillImageLocatorCanExposeVideoControlWithoutStartingCamera() {
        val player = Manager.createPlayer("capture://image")
        try {
            assertEquals(Player.UNREALIZED, player.getState())
            player.realize()
            assertEquals("video/raw", player.getContentType())
            assertTrue(player.getControl("VideoControl") is javax.microedition.media.control.VideoControl)
        } finally { player.close() }
    }

    @Test
    fun realizationExposesPhotoAndRecordControlsWithoutPrefetchingHardware() {
        val player = CameraCapturePlayer("capture://video")
        try {
            assertEquals(Player.UNREALIZED, player.getState())
            expect<IllegalStateException> { player.getControl("VideoControl") }
            expect<IllegalStateException> { player.getControls() }
            expect<IllegalStateException> { player.getContentType() }
            player.realize()
            player.realize()
            assertEquals(Player.REALIZED, player.getState())
            assertEquals("video/raw", player.getContentType())
            assertEquals(2, player.getControls().size)
            assertSame(player.display, player.getControl("VideoControl"))
            assertSame(player.display, player.getControl("javax.microedition.media.control.VideoControl"))
            assertSame(player.display, player.getControl("GUIControl"))
            assertSame(player.display, player.getControl("javax.microedition.media.control.GUIControl"))
            assertTrue(player.getControl("RecordControl") is javax.microedition.media.control.RecordControl)
            assertNull(player.getControl("VolumeControl"))
            assertNull(player.getControl("CameraControl"))
            assertFalse(CameraRuntime.canSwitchCamera())
        } finally {
            player.close()
        }
    }

    @Test
    fun liveSourceHasUnknownDurationAndRejectsSeekAndForeignClock() {
        val player = CameraCapturePlayer("capture://video")
        try {
            assertEquals(Player.TIME_UNKNOWN, player.getDuration())
            assertEquals(0L, player.getMediaTime())
            player.realize()
            assertEquals(Player.TIME_UNKNOWN, player.getDuration())
            expect<MediaException> { player.setMediaTime(1_000L) }
            val system = Manager.getSystemTimeBase()
            assertSame(system, player.getTimeBase())
            player.setTimeBase(null)
            player.setTimeBase(system)
            expect<MediaException> { player.setTimeBase(TimeBase { 0L }) }
            expect<IllegalArgumentException> { player.setLoopCount(0) }
            expect<IllegalArgumentException> { player.setLoopCount(-2) }
            player.setLoopCount(-1)
            player.setLoopCount(1)
        } finally {
            player.close()
        }
    }

    @Test
    fun closeUnblocksPendingGuestCallAndMakesControlsUnusable() {
        val player = CameraCapturePlayer("capture://video")
        val executor = Executors.newSingleThreadExecutor()
        val registered = CountDownLatch(1)
        val call = CameraCall<String>(5_000)
        try {
            player.realize()
            val future = executor.submit<Throwable?> {
                try {
                    player.waitFor(call) { registered.countDown() }
                    null
                } catch (failure: Throwable) { failure }
            }
            assertTrue(registered.await(2, TimeUnit.SECONDS))
            player.close()
            assertTrue(future.get(2, TimeUnit.SECONDS) is MediaException)
            assertEquals(Player.CLOSED, player.getState())
            expect<IllegalStateException> { player.realize() }
            expect<IllegalStateException> { player.getControls() }
            expect<IllegalStateException> { player.getControl("VideoControl") }
            expect<IllegalStateException> { player.getDuration() }
            expect<IllegalStateException> { player.getMediaTime() }
        } finally {
            player.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closePreservesCommittedEventsAndRejectsNewEvents() {
        val player = CameraCapturePlayer("capture://video")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val received = ConcurrentLinkedQueue<String>()
        try {
            player.addPlayerListener { _, event, _ ->
                received.add(event)
                if (event == PlayerListener.SIZE_CHANGED) {
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }
                if (event == PlayerListener.CLOSED) closed.countDown()
            }
            player.postEvent(PlayerListener.SIZE_CHANGED, player.display)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            player.postEvent(PlayerListener.STARTED, 0L)
            player.close()
            player.close()
            player.postEvent(PlayerListener.SIZE_CHANGED, player.display)
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(PlayerListener.SIZE_CHANGED, PlayerListener.STARTED, PlayerListener.CLOSED), received.toList())
        } finally {
            release.countDown()
            player.close()
        }
    }

    @Test
    fun shutdownRejectsNewPlayersUntilAnotherRuntimeBegins() {
        CameraRuntime.closeRuntime()
        expect<MediaException> { CameraCapturePlayer("capture://video") }
        CameraRuntime.beginRuntime()
        CameraCapturePlayer("capture://video").close()
    }

    @Test
    fun canceledPrepareCannotStartPlayerAfterStopReturned() {
        val player = CameraCapturePlayer("capture://video")
        try {
            player.realize()
            val canceled = CameraCall<Unit>(5_000)
            canceled.fail(MediaException("stopped"))
            player.stop()
            player.markPrepared(true, canceled)
            assertEquals(Player.REALIZED, player.getState())
        } finally {
            player.close()
        }
    }

    private inline fun <reified T : Throwable> expect(operation: () -> Unit): T {
        try {
            operation()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}, got $failure", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
