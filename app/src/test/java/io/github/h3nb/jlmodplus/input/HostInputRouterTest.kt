/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.input

import android.view.KeyEvent
import javax.microedition.lcdui.Canvas
import javax.microedition.lcdui.Displayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostInputRouterTest {
    @Test
    fun hostModalConsumesDirectionalKeyWithoutGuestFallthrough() {
        val host = RecordingHostSink(modalActive = true)
        val router = HostInputRouter(host)
        val down = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0)

        assertTrue(router.onKeyEvent(down))
        assertEquals(listOf(HostCommand.NavigateUp to true), host.commands)
    }

    private class RecordingHostSink(
        private val modalActive: Boolean,
    ) : ControllerHostSink {
        val commands = mutableListOf<Pair<HostCommand, Boolean>>()

        override fun currentCanvas(): Canvas? = null

        override fun currentDisplayable(): Displayable? = null

        override fun onHostCommand(command: HostCommand, pressed: Boolean): Boolean {
            commands += command to pressed
            return true
        }

        override fun onControllerInputAccepted() = Unit

        override fun onControllerNotice(message: String) = Unit

        override fun isControllerModalActive(): Boolean = modalActive
    }
}
