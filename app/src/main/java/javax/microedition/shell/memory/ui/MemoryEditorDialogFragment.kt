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

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.core.content.edit
import androidx.fragment.app.viewModels
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.preference.PreferenceManager
import javax.microedition.shell.MidletThread

class MemoryEditorDialogFragment : DialogFragment() {
    private val viewModel by viewModels<MemoryEditorViewModel>()
    private var pausedByEditor = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val enabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(PREF_PAUSE, false)
        viewModel.setPauseEnabled(enabled)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        id = View.generateViewId()
        setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        setContent {
            val state by viewModel.state.collectAsState()
            val actions = remember(viewModel) {
                MemoryEditorActions(
                    setKind = viewModel::setKind,
                    setInitialMode = viewModel::setInitialMode,
                    setRefineMode = viewModel::setRefineMode,
                    setFirstValue = viewModel::setFirstValue,
                    setSecondValue = viewModel::setSecondValue,
                    startSearch = {
                        viewModel.startSearch {
                            dismissAllowingStateLoss()
                        }
                    },
                    finishCollection = viewModel::finishCollection,
                    refresh = viewModel::refreshResults,
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
                    clearMessage = viewModel::clearMessage,
                    setPauseEnabled = ::setPauseEnabled,
                    setPeeking = ::setPeeking,
                )
            }
            MemoryEditorTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MemoryEditorScreen(
                        state = state,
                        actions = actions,
                        onClose = { dismissAllowingStateLoss() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        applyPause(viewModel.state.value.pauseEnabled)
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.55f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            )
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
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
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit { putBoolean(PREF_PAUSE, enabled) }
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

    private fun setPeeking(enabled: Boolean) {
        dialog?.window?.setDimAmount(if (enabled) 0.05f else 0.55f)
        if (enabled) {
            dialog?.window?.decorView?.let { SoftwareKeyboardControllerCompat(it).hide() }
        }
    }

    companion object {
        private const val TAG = "memory-editor"
        private const val PREF_PAUSE = "memory_editor_pause"

        @JvmStatic
        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.findFragmentByTag(TAG) == null) {
                MemoryEditorDialogFragment().show(fragmentManager, TAG)
            }
        }
    }
}
