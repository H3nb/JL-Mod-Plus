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
import android.graphics.RectF
import android.os.IBinder
import android.os.RemoteException
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ru.playsoftware.j2meloader.R
import kotlin.math.abs

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

    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
    private val edgeMarginPx = 12f * activity.resources.displayMetrics.density
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downRawX = 0f
    private var downRawY = 0f
    private var downViewX = 0f
    private var downViewY = 0f
    private var dragging = false

    private val bubbleLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (!destroyed && !dragging && bubbleView.visibility == View.VISIBLE) {
            settleToNearestEdge(animated = false)
        }
    }

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
        bubbleView.isFocusable = true
        progressView.visibility = View.GONE
        bubbleView.setOnClickListener { openEditor() }
        bubbleView.setOnTouchListener(::onBubbleTouch)
        bubbleView.addOnLayoutChangeListener(bubbleLayoutListener)
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
        cancelDrag()
        bubbleView.visibility = View.GONE
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        MemoryRuntimeSession.removeListener(runtimeListener)
        cancelDrag()
        bubbleView.animate().cancel()
        bubbleView.removeOnLayoutChangeListener(bubbleLayoutListener)
        bubbleView.setOnTouchListener(null)
        bubbleView.setOnClickListener(null)
        setProgress(null)
        disconnectEngine()
        bubbleView.visibility = View.GONE
    }

    private fun onBubbleTouch(view: View, event: MotionEvent): Boolean {
        if (destroyed || !enabled || view.visibility != View.VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bubbleView.animate().cancel()
                pointerId = event.getPointerId(0)
                downRawX = event.rawX
                downRawY = event.rawY
                downViewX = bubbleView.x
                downViewY = bubbleView.y
                dragging = false
                bubbleView.isPressed = true
                bubbleView.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                // rawX/rawY track the active pointer in screen coordinates and are not affected by
                // moving the View itself, which prevents the drag from feeding back into its delta.
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
                if (!dragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                    dragging = true
                    bubbleView.isPressed = false
                }
                if (dragging) {
                    val bounds = movementBounds() ?: return true
                    bubbleView.x = (downViewX + deltaX).coerceIn(bounds.left, bounds.right)
                    bubbleView.y = (downViewY + deltaY).coerceIn(bounds.top, bounds.bottom)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                bubbleView.isPressed = false
                bubbleView.parent?.requestDisallowInterceptTouchEvent(false)
                val wasDragging = dragging
                pointerId = MotionEvent.INVALID_POINTER_ID
                dragging = false
                if (wasDragging) {
                    settleToNearestEdge(animated = true)
                } else {
                    bubbleView.performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                bubbleView.isPressed = false
                bubbleView.parent?.requestDisallowInterceptTouchEvent(false)
                val shouldSettle = dragging
                pointerId = MotionEvent.INVALID_POINTER_ID
                dragging = false
                if (shouldSettle) settleToNearestEdge(animated = true)
                return true
            }
        }
        return true
    }

    private fun movementBounds(): RectF? {
        val parent = bubbleView.parent as? View ?: return null
        if (parent.width <= 0 || parent.height <= 0 ||
            bubbleView.width <= 0 || bubbleView.height <= 0) {
            return null
        }
        val insets = ViewCompat.getRootWindowInsets(parent)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        ) ?: Insets.NONE

        val minX = insets.left + edgeMarginPx
        val maxX = (parent.width - insets.right - bubbleView.width - edgeMarginPx)
            .coerceAtLeast(minX)
        val minY = insets.top + edgeMarginPx
        val maxY = (parent.height - insets.bottom - bubbleView.height - edgeMarginPx)
            .coerceAtLeast(minY)
        return RectF(minX, minY, maxX, maxY)
    }

    private fun settleToNearestEdge(animated: Boolean) {
        val bounds = movementBounds() ?: return
        val clampedY = bubbleView.y.coerceIn(bounds.top, bounds.bottom)
        val center = (bounds.left + bounds.right) * 0.5f
        val targetX = if (bubbleView.x <= center) bounds.left else bounds.right
        if (!animated) {
            bubbleView.animate().cancel()
            bubbleView.x = targetX
            bubbleView.y = clampedY
            return
        }
        bubbleView.animate()
            .x(targetX)
            .y(clampedY)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()
    }

    private fun cancelDrag() {
        pointerId = MotionEvent.INVALID_POINTER_ID
        dragging = false
        bubbleView.isPressed = false
        bubbleView.parent?.requestDisallowInterceptTouchEvent(false)
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
        if (bubbleView.visibility == View.VISIBLE) {
            bubbleView.bringToFront()
            // Re-clamp after rotation, split-screen resizing, or inset changes. Posting waits until
            // the host has a valid size and preserves the last vertical position within the session.
            bubbleView.post { if (!destroyed && !dragging) settleToNearestEdge(animated = false) }
        }
    }
}
