/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ru.playsoftware.j2meloader.memory

import android.app.Activity
import android.view.View

/**
 * MIDlet-process owner of the tiny in-game bubble only. The actual editor Activity and
 * Compose tree live in :memory_engine, so showing the editor cannot add Compose GC pressure
 * to the target MIDlet heap.
 */
class MemoryEditorBubbleController(
    private val activity: Activity,
    private val bubbleView: View,
) {
    private var enabled = false
    private var hostResumed = true
    private var destroyed = false

    private val runtimeListener = MemoryRuntimeSession.Listener {
        activity.runOnUiThread {
  enabled = false
  syncVisibility()
        }
    }

    init {
        bubbleView.visibility = View.GONE
        bubbleView.setOnClickListener { openEditor() }
        MemoryRuntimeSession.addListener(runtimeListener)
    }

    fun toggleBubble(): Boolean {
        if (destroyed || MemoryRuntimeSession.currentToken() == 0L) return false
        enabled = !enabled
        syncVisibility()
        return enabled
    }

    fun isBubbleEnabled(): Boolean = enabled

    fun onHostResumed() {
        hostResumed = true
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
        bubbleView.visibility = View.GONE
    }

    private fun openEditor() {
        if (destroyed) return
        val token = MemoryRuntimeSession.currentToken()
        if (token == 0L) {
  enabled = false
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

    private fun syncVisibility() {
        if (destroyed) return
        bubbleView.visibility = if (
  enabled && hostResumed && MemoryRuntimeSession.currentToken() != 0L
        ) View.VISIBLE else View.GONE
        if (bubbleView.visibility == View.VISIBLE) bubbleView.bringToFront()
    }
}
