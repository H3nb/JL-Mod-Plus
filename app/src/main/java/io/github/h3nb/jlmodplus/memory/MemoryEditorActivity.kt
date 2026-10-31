/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
// Modifications: the Compose memory-editor surface participates in the shared edge-to-edge policy.

package io.github.h3nb.jlmodplus.memory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat
import io.github.h3nb.jlmodplus.settings.RuntimeChromeClient
import io.github.h3nb.jlmodplus.settings.RuntimeChromeState

/** Translucent app Activity containing the production Memory Editor UI in :memory_engine. */
class MemoryEditorActivity : AppCompatActivity() {
    private var controller: MemoryEditorComposeController? = null
    private var listenerRegistered = false
    private var chromeClient: RuntimeChromeClient? = null
    private var showStatusBar = false

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
        updateSystemBars()
        chromeClient = RuntimeChromeClient(this, object : RuntimeChromeClient.Listener {
            override fun onChromeChanged(state: RuntimeChromeState) {
                showStatusBar = state.showStatusBar
                updateSystemBars()
            }

            override fun onChromeError(failure: Exception) {
                Log.w("MemoryEditorActivity", "Could not read runtime chrome preferences", failure)
            }
        })

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
        updateSystemBars()
        controller = MemoryEditorComposeController(
            composeView = composeView,
            ownedRuntimeToken = token,
            closeHost = ::finish,
        ).also(MemoryEditorComposeController::open)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                controller?.close() ?: finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        chromeClient?.start()
    }

    override fun onResume() {
        super.onResume()
        updateSystemBars()
    }

    override fun onStop() {
        chromeClient?.close()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
    }

    private fun updateSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
            if (showStatusBar) show(WindowInsetsCompat.Type.statusBars())
            else hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onDestroy() {
        controller?.destroy()
        controller = null
        chromeClient?.close()
        chromeClient = null
        if (listenerRegistered) {
            MemoryEngineService.removeLocalRuntimeListener(runtimeListener)
            listenerRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RUNTIME_TOKEN =
            "ru.playsoftware.j2meloader.memory.extra.RUNTIME_TOKEN"

        fun createIntent(context: Context, runtimeToken: Long): Intent =
            Intent(context, MemoryEditorActivity::class.java)
                .putExtra(EXTRA_RUNTIME_TOKEN, runtimeToken)
    }
}
