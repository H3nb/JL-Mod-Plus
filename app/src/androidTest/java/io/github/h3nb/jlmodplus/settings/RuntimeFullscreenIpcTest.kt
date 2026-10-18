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
class RuntimeFullscreenIpcTest {
    @Test
    fun remoteWritesAndMainWritesShareOnePreferenceAndObserverLifecycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadToolbar = preferences.contains(PREF_TOOLBAR)
        val oldToolbar = preferences.getBoolean(PREF_TOOLBAR, false)
        val uri = Uri.parse("content://${context.packageName}.runtime-fullscreen")
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
        fun write(fullscreen: Boolean) {
            context.contentResolver.call(uri, "write", null,
                Bundle().apply { putBoolean("fullscreen", fullscreen) })
        }
        fun command(what: Int, fullscreen: Boolean = false) {
            remote!!.send(Message.obtain().apply {
                this.what = what
                replyTo = replies
                data = Bundle().apply { putBoolean(FullscreenProbeService.VALUE, fullscreen) }
            })
        }
        fun awaitState(expected: Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("No remote fullscreen update: $expected")
                assertNotEquals(Process.myPid(), event.data.getInt(FullscreenProbeService.PID))
                assertNotEquals(event.data.toString(), FullscreenProbeService.FAILED, event.what)
                if (event.what == FullscreenProbeService.STATE &&
                    event.data.getBoolean(FullscreenProbeService.VALUE) == expected) return
            }
        }
        val bound = context.bindService(Intent(context, FullscreenProbeService::class.java),
            connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            write(false)
            command(FullscreenProbeService.START)
            awaitState(false)

            // Runtime -> main: writes the inverse legacy key and confirms the persisted value.
            command(FullscreenProbeService.WRITE, true)
            awaitState(true)
            assertFalse(preferences.getBoolean(PREF_TOOLBAR, true))

            // Main -> already active runtime: no recreation or stale SharedPreferences read.
            write(false)
            awaitState(false)
            assertTrue(preferences.getBoolean(PREF_TOOLBAR, false))

            command(FullscreenProbeService.STOP)
            val stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val event = events.poll(stopDeadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("Missing stop acknowledgement")
                if (event.what == FullscreenProbeService.STOP) break
            }
            write(true)
            command(FullscreenProbeService.START)
            awaitState(true)
            assertEquals(true, context.contentResolver.call(uri, "read", null, null)
                ?.getBoolean("fullscreen"))
        } finally {
            context.unbindService(connection)
            val editor = preferences.edit()
            if (hadToolbar) editor.putBoolean(PREF_TOOLBAR, oldToolbar) else editor.remove(PREF_TOOLBAR)
            assertTrue(editor.commit())
            context.contentResolver.notifyChange(uri, null)
        }
    }
}
