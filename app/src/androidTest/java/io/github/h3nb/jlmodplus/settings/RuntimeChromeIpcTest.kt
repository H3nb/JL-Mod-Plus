/* Licensed under the Apache License, Version 2.0 (the "License"); */
package io.github.h3nb.jlmodplus.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.util.Constants.PREF_TOOLBAR
import io.github.h3nb.jlmodplus.util.Constants.PREF_STATUSBAR
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeChromeIpcTest {
    @Test
    fun independentChromePreferencesSyncAcrossProcessesAndObserverLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadToolbar = preferences.contains(PREF_TOOLBAR)
        val oldToolbar = preferences.getBoolean(PREF_TOOLBAR, false)
        val hadStatus = preferences.contains(PREF_STATUSBAR)
        val oldStatus = preferences.getBoolean(PREF_STATUSBAR, false)
        val uri = Uri.parse("content://${context.packageName}.runtime-chrome")
        val connected = CountDownLatch(1)
        val events = LinkedBlockingQueue<Message>()
        val replies = Messenger(Handler(Looper.getMainLooper()) {
            events.offer(Message.obtain(it))
            true
        })
        var remote: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote = Messenger(service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        fun write(method: String, field: String, value: Boolean) {
            context.contentResolver.call(uri, method, null,
                Bundle().apply { putBoolean(field, value) })
        }
        fun command(what: Int, fullscreen: Boolean = false) {
            remote!!.send(Message.obtain().apply {
                this.what = what
                replyTo = replies
                data = Bundle().apply { putBoolean(RuntimeChromeProbeService.VALUE, fullscreen) }
            })
        }
        fun awaitState(fullscreen: Boolean, showStatusBar: Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("No remote chrome update: $fullscreen/$showStatusBar")
                assertNotEquals(Process.myPid(), event.data.getInt(RuntimeChromeProbeService.PID))
                assertNotEquals(event.data.toString(), RuntimeChromeProbeService.FAILED, event.what)
                if (event.what == RuntimeChromeProbeService.STATE &&
                    event.data.getBoolean(RuntimeChromeProbeService.FULLSCREEN) == fullscreen &&
                    event.data.getBoolean(RuntimeChromeProbeService.SHOW_STATUS_BAR) == showStatusBar) return
            }
        }
        val bound = context.bindService(Intent(context, RuntimeChromeProbeService::class.java),
            connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            write("writeFullscreen", "fullscreen", false)
            write("writeStatusBar", "showStatusBar", false)
            command(RuntimeChromeProbeService.START)
            awaitState(false, false)

            // Remote updates the toolbar preference without touching the status-bar preference.
            command(RuntimeChromeProbeService.WRITE_FULLSCREEN, true)
            awaitState(true, false)
            assertFalse(preferences.getBoolean(PREF_TOOLBAR, true))
            assertFalse(preferences.getBoolean(PREF_STATUSBAR, false))

            // Main-process settings changes reach a running remote client.
            write("writeStatusBar", "showStatusBar", true)
            awaitState(true, true)
            assertTrue(preferences.getBoolean(PREF_STATUSBAR, false))
            assertFalse(preferences.getBoolean(PREF_TOOLBAR, true))

            write("writeFullscreen", "fullscreen", false)
            awaitState(false, true)
            assertTrue(preferences.getBoolean(PREF_TOOLBAR, false))
            assertTrue(preferences.getBoolean(PREF_STATUSBAR, false))

            command(RuntimeChromeProbeService.WRITE_STATUS_BAR, false)
            awaitState(false, false)
            assertFalse(preferences.getBoolean(PREF_STATUSBAR, true))

            command(RuntimeChromeProbeService.STOP)
            val stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val event = events.poll(stopDeadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("Missing stop acknowledgement")
                if (event.what == RuntimeChromeProbeService.STOP) break
            }
            write("writeStatusBar", "showStatusBar", true)
            command(RuntimeChromeProbeService.START)
            awaitState(false, true)
            val read = context.contentResolver.call(uri, "read", null, null)
            assertEquals(false, read?.getBoolean("fullscreen"))
            assertEquals(true, read?.getBoolean("showStatusBar"))
        } finally {
            context.unbindService(connection)
            val editor = preferences.edit()
            if (hadToolbar) editor.putBoolean(PREF_TOOLBAR, oldToolbar) else editor.remove(PREF_TOOLBAR)
            if (hadStatus) editor.putBoolean(PREF_STATUSBAR, oldStatus) else editor.remove(PREF_STATUSBAR)
            assertTrue(editor.commit())
            context.contentResolver.notifyChange(uri, null)
        }
    }
}
