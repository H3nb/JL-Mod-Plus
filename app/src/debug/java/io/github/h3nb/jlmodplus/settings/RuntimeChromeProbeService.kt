/* Licensed under the Apache License, Version 2.0 (the "License"); */
package io.github.h3nb.jlmodplus.settings

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process

/** Exercises the production async client in a separate process without launching a guest. */
class RuntimeChromeProbeService : Service() {
    private var client: RuntimeChromeClient? = null
    private var replies: Messenger? = null
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            START -> {
                replies = message.replyTo
                client?.close()
                client = RuntimeChromeClient(this, object : RuntimeChromeClient.Listener {
                    override fun onChromeChanged(state: RuntimeChromeState) = send(STATE,
                        Bundle().apply {
                            putBoolean(FULLSCREEN, state.fullscreen)
                            putBoolean(SHOW_STATUS_BAR, state.showStatusBar)
                        })
                    override fun onChromeError(failure: Exception) = send(FAILED,
                        Bundle().apply { putString("failure", failure.toString()) })
                }).also { it.start() }
            }
            WRITE_FULLSCREEN -> client!!.setFullscreen(message.data.getBoolean(VALUE))
            WRITE_STATUS_BAR -> client!!.setShowStatusBar(message.data.getBoolean(VALUE))
            STOP -> {
                client?.close()
                client = null
                send(STOP, Bundle())
            }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = incoming.binder

    override fun onDestroy() {
        client?.close()
        super.onDestroy()
    }

    private fun send(what: Int, payload: Bundle) {
        payload.putInt(PID, Process.myPid())
        replies?.send(Message.obtain().apply { this.what = what; data = payload })
    }

    companion object {
        const val START = 1
        const val WRITE_FULLSCREEN = 2
        const val WRITE_STATUS_BAR = 6
        const val STOP = 3
        const val STATE = 4
        const val FAILED = 5
        const val VALUE = "value"
        const val FULLSCREEN = "fullscreen"
        const val SHOW_STATUS_BAR = "showStatusBar"
        const val PID = "pid"
    }
}
