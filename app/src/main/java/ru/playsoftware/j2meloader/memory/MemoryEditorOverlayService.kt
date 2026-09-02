/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ru.playsoftware.j2meloader.memory

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import kotlin.math.roundToInt

/**
 * Hosts Memory Editor presentation alongside MemoryEngineService in :memory_engine.
 *
 * The MIDlet process remains a protected target process: it owns neither the editor Compose tree
 * nor editor-side polling/state. In compact portrait the editor uses the full screen; on wide
 * landscape displays it becomes a touch-modal side panel so the game remains visible and usable.
 */
class MemoryEditorOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var editorView: ComposeView
    private lateinit var bubbleView: ComposeView
    private lateinit var editorParams: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var controller: MemoryEditorOverlayController? = null

    private var bubbleDownX = 0f
    private var bubbleDownY = 0f
    private var bubbleStartX = 0
    private var bubbleStartY = 0
    private var bubbleMoved = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        createOverlayViews()
        controller = MemoryEditorOverlayController(
            context = this,
            editorView = editorView,
            bubbleView = bubbleView,
            bubbleTouchHandler = ::handleBubbleTouch,
        )
        MemoryEditorOverlayState.markActive(this, true)
        controller?.enable()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN -> controller?.open()
            ACTION_HIDE -> controller?.close()
            ACTION_ENABLE, null -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateEditorWindowBounds()
        clampBubblePosition()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        MemoryEditorOverlayState.markVisible(this, false)
        MemoryEditorOverlayState.markActive(this, false)
        if (::windowManager.isInitialized) {
            if (::editorView.isInitialized) {
                runCatching { windowManager.removeViewImmediate(editorView) }
            }
            if (::bubbleView.isInitialized) {
                runCatching { windowManager.removeViewImmediate(bubbleView) }
            }
        }
        super.onDestroy()
    }

    private fun createOverlayViews() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        editorView = ComposeView(this).apply {
            visibility = View.GONE
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    controller?.close()
                    true
                } else {
                    false
                }
            }
        }
        editorParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        bubbleView = ComposeView(this).apply {
            visibility = View.GONE
        }
        val bubbleSize = dp(60)
        val margin = dp(10)
        val metrics = resources.displayMetrics
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedX = prefs.getInt(KEY_X, metrics.widthPixels - bubbleSize - margin)
        val savedY = prefs.getInt(KEY_Y, (metrics.heightPixels - bubbleSize) / 2)
        bubbleParams = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        windowManager.addView(editorView, editorParams)
        windowManager.addView(bubbleView, bubbleParams)
        updateEditorWindowBounds()
        clampBubblePosition()
    }

    /**
     * Wide landscape gets a right-side tool panel instead of a transparent full-screen window.
     * Because the window itself is narrower, touches outside the panel reach the running MIDlet.
     */
    private fun updateEditorWindowBounds() {
        if (!::editorParams.isInitialized) return
        val config = resources.configuration
        val screenWidthDp = config.screenWidthDp.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels / resources.displayMetrics.density).roundToInt()
        val sidePanel = config.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            screenWidthDp >= 600
        editorParams.width = if (sidePanel) {
            dp((screenWidthDp * 0.48f).roundToInt().coerceIn(360, 520))
        } else {
            WindowManager.LayoutParams.MATCH_PARENT
        }
        editorParams.height = WindowManager.LayoutParams.MATCH_PARENT
        editorParams.gravity = Gravity.TOP or Gravity.END
        if (::editorView.isInitialized && editorView.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(editorView, editorParams) }
        }
    }

    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bubbleDownX = event.rawX
                bubbleDownY = event.rawY
                bubbleStartX = bubbleParams.x
                bubbleStartY = bubbleParams.y
                bubbleMoved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - bubbleDownX
                val dy = event.rawY - bubbleDownY
                if (!bubbleMoved && dx * dx + dy * dy > touchSlop * touchSlop) {
                    bubbleMoved = true
                }
                if (bubbleMoved) {
                    bubbleParams.x = bubbleStartX + dx.roundToInt()
                    bubbleParams.y = bubbleStartY + dy.roundToInt()
                    clampBubblePosition(update = true)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (bubbleMoved) {
                    snapBubbleToEdge()
                    saveBubblePosition()
                } else {
                    controller?.open()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                snapBubbleToEdge()
                saveBubblePosition()
                true
            }
            else -> false
        }
    }

    private fun snapBubbleToEdge() {
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - bubbleParams.width).coerceAtLeast(0)
        bubbleParams.x = if (
            bubbleParams.x + bubbleParams.width / 2 < metrics.widthPixels / 2
        ) {
            0
        } else {
            maxX
        }
        clampBubblePosition(update = true)
    }

    private fun clampBubblePosition(update: Boolean = true) {
        if (!::bubbleParams.isInitialized) return
        val metrics = resources.displayMetrics
        bubbleParams.x = bubbleParams.x.coerceIn(
            0,
            (metrics.widthPixels - bubbleParams.width).coerceAtLeast(0),
        )
        bubbleParams.y = bubbleParams.y.coerceIn(
            0,
            (metrics.heightPixels - bubbleParams.height).coerceAtLeast(0),
        )
        if (update && ::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(bubbleView, bubbleParams) }
        }
    }

    private fun saveBubblePosition() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_X, bubbleParams.x)
            .putInt(KEY_Y, bubbleParams.y)
            .apply()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val ACTION_ENABLE =
            "ru.playsoftware.j2meloader.memory.ENABLE_OVERLAY"
        private const val ACTION_DISABLE =
            "ru.playsoftware.j2meloader.memory.DISABLE_OVERLAY"
        private const val ACTION_OPEN =
            "ru.playsoftware.j2meloader.memory.OPEN_OVERLAY"
        private const val ACTION_HIDE =
            "ru.playsoftware.j2meloader.memory.HIDE_OVERLAY"
        private const val PREFS = "memory_editor_overlay"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"

        fun setEnabled(context: Context, enabled: Boolean) {
            val action = if (enabled) ACTION_ENABLE else ACTION_DISABLE
            runCatching {
                context.startService(
                    Intent(context, MemoryEditorOverlayService::class.java).setAction(action),
                )
            }
        }

        fun open(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, MemoryEditorOverlayService::class.java).setAction(ACTION_OPEN),
                )
            }
        }

        fun hide(context: Context) {
            if (!MemoryEditorOverlayState.isActive(context)) return
            runCatching {
                context.startService(
                    Intent(context, MemoryEditorOverlayService::class.java).setAction(ACTION_HIDE),
                )
            }
        }
    }
}
