/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
// Modifications: the Compose memory-editor surface participates in the shared edge-to-edge policy.

package io.github.h3nb.jlmodplus.memory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toDrawable
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat

/** Translucent app Activity containing the production Memory Editor UI in :memory_engine. */
class MemoryEditorActivity : AppCompatActivity() {
    private var controller: MemoryEditorComposeController? = null
    private var listenerRegistered = false
    private val systemBars by lazy {
        MemoryEditorSystemBars(
            statusBarVisible = intent.getBooleanExtra(EXTRA_STATUS_BAR_VISIBLE, true),
            navigationBarVisible = intent.getBooleanExtra(EXTRA_NAVIGATION_BAR_VISIBLE, true),
        )
    }

    private val runtimeListener = MemoryEngineService.LocalRuntimeListener {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getLongExtra(EXTRA_RUNTIME_TOKEN, 0L)
        if (token == 0L) {
            finish()
            return
        }

        window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        EdgeToEdgeCompat.enableForComposeSurface(this)
        systemBars.applyTo(window)

        MemoryEngineService.addLocalRuntimeListener(runtimeListener)
        listenerRegistered = true

        val composeView = ComposeView(this)
        setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        systemBars.applyTo(window)
        controller = MemoryEditorComposeController(
            composeView = composeView,
            ownedRuntimeToken = token,
            systemBars = systemBars,
            closeHost = ::finish,
        ).also(MemoryEditorComposeController::open)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                controller?.close() ?: finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        systemBars.applyTo(window)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) systemBars.applyTo(window)
    }

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        if (listenerRegistered) {
            MemoryEngineService.removeLocalRuntimeListener(runtimeListener)
            listenerRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RUNTIME_TOKEN =
            "ru.playsoftware.j2meloader.memory.extra.RUNTIME_TOKEN"
        private const val EXTRA_STATUS_BAR_VISIBLE =
            "ru.playsoftware.j2meloader.memory.extra.STATUS_BAR_VISIBLE"
        private const val EXTRA_NAVIGATION_BAR_VISIBLE =
            "ru.playsoftware.j2meloader.memory.extra.NAVIGATION_BAR_VISIBLE"

        @JvmStatic
        fun createIntent(
            context: Context,
            runtimeToken: Long,
            statusBarVisible: Boolean,
            navigationBarVisible: Boolean,
        ): Intent = Intent(context, MemoryEditorActivity::class.java)
            .putExtra(EXTRA_RUNTIME_TOKEN, runtimeToken)
            .putExtra(EXTRA_STATUS_BAR_VISIBLE, statusBarVisible)
            .putExtra(EXTRA_NAVIGATION_BAR_VISIBLE, navigationBarVisible)
    }
}
