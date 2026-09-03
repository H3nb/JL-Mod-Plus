/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ru.playsoftware.j2meloader.memory

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.view.View
import android.widget.TextView
import android.widget.Toast
import ru.playsoftware.j2meloader.R

/**
 * MIDlet-process owner of the tiny in-game bubble only. The editor Activity and Compose tree live
 * in :memory_engine. This small Binder client also keeps an active scan alive while the editor
 * Activity is closed and mirrors only search progress/completion back to the game.
 */
class MemoryEditorBubbleController(
    private val activity: Activity,
    private val bubbleView: View,
    private val iconView: View,
    private val progressView: TextView,
) {
    private var enabled = false
    private var hostResumed = true
    private var destroyed = false
    private var bound = false
    private var service: IMemoryEngineService? = null

    private val callback = object : IMemoryEngineCallback.Stub() {
        override fun onOperationProgress(
            operationId: Long,
            scannedBytes: Long,
            totalBytes: Long,
            searchOperation: Boolean,
        ) {
            if (!searchOperation || totalBytes <= 0L) return
            val percent = ((scannedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes)
                .toInt().coerceIn(0, 100)
            activity.runOnUiThread {
                if (!destroyed && enabled) setProgress(percent)
            }
        }

        override fun onOperationFinished(
            operationId: Long,
            resultCode: Int,
            resultCount: Long,
            message: String?,
            passiveRefresh: Boolean,
            searchOperation: Boolean,
        ) {
            if (!searchOperation) return
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                setProgress(null)
                if (enabled && hostResumed && resultCode == MemoryEngineContract.RESULT_OK) {
                    val count = resultCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    Toast.makeText(
                        activity,
                        activity.resources.getQuantityString(
                            R.plurals.memory_editor_search_complete,
                            count,
                            resultCount,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IMemoryEngineService.Stub.asInterface(binder)
            try {
                service?.registerCallback(callback)
            } catch (_: RemoteException) {
                disconnected()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    private val runtimeListener = MemoryRuntimeSession.Listener {
        activity.runOnUiThread {
            enabled = false
            setProgress(null)
            disconnectEngine()
            syncVisibility()
        }
    }

    init {
        bubbleView.visibility = View.GONE
        progressView.visibility = View.GONE
        bubbleView.setOnClickListener { openEditor() }
        MemoryRuntimeSession.addListener(runtimeListener)
    }

    fun toggleBubble(): Boolean {
        if (destroyed || MemoryRuntimeSession.currentToken() == 0L) return false
        enabled = !enabled
        if (enabled) connectEngine() else {
            setProgress(null)
            disconnectEngine()
        }
        syncVisibility()
        return enabled
    }

    fun isBubbleEnabled(): Boolean = enabled

    fun onHostResumed() {
        hostResumed = true
        if (enabled) connectEngine()
        syncVisibility()
    }

    fun onHostPaused() {
        hostResumed = false
        bubbleView.visibility = View.GONE
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        MemoryRuntimeSession.removeListener(runtimeListener)
        bubbleView.setOnClickListener(null)
        setProgress(null)
        disconnectEngine()
        bubbleView.visibility = View.GONE
    }

    private fun openEditor() {
        if (destroyed) return
        val token = MemoryRuntimeSession.currentToken()
        if (token == 0L) {
            enabled = false
            disconnectEngine()
            syncVisibility()
            return
        }
        bubbleView.visibility = View.GONE
        try {
            activity.startActivity(MemoryEditorActivity.createIntent(activity, token))
        } catch (_: RuntimeException) {
            syncVisibility()
        }
    }

    private fun connectEngine() {
        if (destroyed || !enabled || bound || MemoryRuntimeSession.currentToken() == 0L) return
        bound = activity.bindService(
            Intent(activity, MemoryEngineService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun disconnectEngine() {
        try {
            service?.unregisterCallback(callback)
        } catch (_: RemoteException) {
            // The engine may already be shutting down with the MIDlet.
        }
        service = null
        if (bound) {
            runCatching { activity.unbindService(connection) }
            bound = false
        }
    }

    private fun disconnected() {
        activity.runOnUiThread {
            service = null
            bound = false
            setProgress(null)
            if (enabled && !destroyed && MemoryRuntimeSession.currentToken() != 0L) {
                bubbleView.postDelayed({ connectEngine() }, 250L)
            }
        }
    }

    private fun setProgress(percent: Int?) {
        if (percent == null) {
            iconView.visibility = View.VISIBLE
            progressView.visibility = View.GONE
            progressView.text = ""
            bubbleView.contentDescription = activity.getString(R.string.memory_editor_bubble)
        } else {
            iconView.visibility = View.GONE
            progressView.visibility = View.VISIBLE
            progressView.text = "$percent%"
            bubbleView.contentDescription =
                "${activity.getString(R.string.memory_editor_bubble)} · $percent%"
        }
    }

    private fun syncVisibility() {
        if (destroyed) return
        bubbleView.visibility = if (
            enabled && hostResumed && MemoryRuntimeSession.currentToken() != 0L
        ) View.VISIBLE else View.GONE
        if (bubbleView.visibility == View.VISIBLE) bubbleView.bringToFront()
    }
}
