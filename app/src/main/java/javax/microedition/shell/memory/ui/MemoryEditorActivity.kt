/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.shell.memory.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import javax.microedition.shell.MidletThread

class MemoryEditorActivity : AppCompatActivity() {
    private lateinit var viewModel: MemoryEditorViewModel
    private var pausedByEditor = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MemoryEditorViewModel::class.java]
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        viewModel.setPauseEnabled(preferences.getBoolean(PREF_PAUSE, false))
        viewModel.setLayoutTransparency(
            preferences.getInt(PREF_LAYOUT_TRANSPARENCY, DEFAULT_LAYOUT_TRANSPARENCY).toFloat() / 100f,
        )
        WindowCompat.enableEdgeToEdge(window)
        setContent {
            val state by viewModel.state.collectAsState()
            val actions = remember(viewModel) {
                MemoryEditorActions(
                    setKind = viewModel::setKind,
                    setInitialMode = viewModel::setInitialMode,
                    setRefineMode = viewModel::setRefineMode,
                    setFirstValue = viewModel::setFirstValue,
                    setSecondValue = viewModel::setSecondValue,
                    startSearch = { viewModel.startSearch { finish() } },
                    continueCollection = { viewModel.continueCollection { finish() } },
                    refine = viewModel::refine,
                    toggleSelection = viewModel::toggleSelection,
                    clearSelection = viewModel::clearSelection,
                    toggleAllLoaded = viewModel::toggleAllLoaded,
                    editSelected = viewModel::editSelected,
                    freezeSelected = viewModel::freezeSelected,
                    unfreezeSelected = viewModel::unfreezeSelected,
                    unfreezeSavedSelected = viewModel::unfreezeSavedSelected,
                    deleteSavedSelected = viewModel::deleteSavedSelected,
                    loadMore = viewModel::loadMore,
                    undo = viewModel::undo,
                    reset = viewModel::reset,
                    cancelOperation = viewModel::cancelOperation,
                    clearMessage = viewModel::clearMessage,
                    setPauseEnabled = ::setPauseEnabled,
                    setLayoutTransparency = ::setLayoutTransparency,
                    setPeeking = ::setPeeking,
                )
            }
            MemoryEditorTheme {
                Box(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MemoryEditorScreen(
                        state = state,
                        actions = actions,
                        onClose = ::finish,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        applyPause(viewModel.state.value.pauseEnabled)
        viewModel.loadExistingSession()
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        window.setDimAmount(dimAmount(viewModel.state.value.layoutTransparency))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    override fun onStop() {
        setPeeking(false)
        if (pausedByEditor) {
            MidletThread.getEmulationTimeController()?.resume()
            pausedByEditor = false
        }
        super.onStop()
    }

    private fun setPauseEnabled(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(this).edit { putBoolean(PREF_PAUSE, enabled) }
        viewModel.setPauseEnabled(enabled)
        applyPause(enabled)
    }

    private fun applyPause(enabled: Boolean) {
        val controller = MidletThread.getEmulationTimeController() ?: return
        if (enabled && !controller.snapshot().isPaused) {
            controller.pause()
            pausedByEditor = true
        } else if (!enabled && pausedByEditor) {
            controller.resume()
            pausedByEditor = false
        }
    }

    private fun setLayoutTransparency(transparency: Float) {
        val percent = (transparency.coerceIn(0f, MAX_LAYOUT_TRANSPARENCY) * 100).toInt()
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putInt(PREF_LAYOUT_TRANSPARENCY, percent)
        }
        viewModel.setLayoutTransparency(percent.toFloat() / 100f)
        window.setDimAmount(dimAmount(percent.toFloat() / 100f))
    }

    private fun setPeeking(enabled: Boolean) {
        window.setDimAmount(if (enabled) 0f else dimAmount(viewModel.state.value.layoutTransparency))
        if (enabled) {
            SoftwareKeyboardControllerCompat(window.decorView).hide()
        }
    }

    private fun dimAmount(layoutTransparency: Float): Float =
        DEFAULT_DIM_AMOUNT * (1f - layoutTransparency.coerceIn(0f, MAX_LAYOUT_TRANSPARENCY))

    companion object {
        private const val PREF_PAUSE = "memory_editor_pause"
        private const val PREF_LAYOUT_TRANSPARENCY = "memory_editor_layout_transparency"
        private const val DEFAULT_LAYOUT_TRANSPARENCY = 0
        private const val MAX_LAYOUT_TRANSPARENCY = 0.8f
        private const val DEFAULT_DIM_AMOUNT = 0.55f
    }
}
