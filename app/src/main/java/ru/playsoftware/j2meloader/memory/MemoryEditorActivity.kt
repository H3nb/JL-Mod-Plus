/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ru.playsoftware.j2meloader.memory

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat

/** Translucent app Activity containing the production Memory Editor UI in :memory_engine. */
class MemoryEditorActivity : AppCompatActivity() {
    private var controller: MemoryEditorComposeController? = null
    private var listenerRegistered = false

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

        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        EdgeToEdgeCompat.enableIfSupported(this)
        hideSystemBars()

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
        hideSystemBars()
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

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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

        fun createIntent(context: Context, runtimeToken: Long): Intent =
            Intent(context, MemoryEditorActivity::class.java)
                .putExtra(EXTRA_RUNTIME_TOKEN, runtimeToken)
    }
}
