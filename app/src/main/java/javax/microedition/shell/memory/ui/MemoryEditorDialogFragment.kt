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
import androidx.fragment.app.viewModels

class MemoryEditorDialogFragment : DialogFragment() {
    private val viewModel by viewModels<MemoryEditorViewModel>()

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
                    toggleAllLoaded = viewModel::toggleAllLoaded,
                    editSelected = viewModel::editSelected,
                    freezeSelected = viewModel::freezeSelected,
                    unfreezeSelected = viewModel::unfreezeSelected,
                    loadMore = viewModel::loadMore,
                    undo = viewModel::undo,
                    reset = viewModel::reset,
                    clearMessage = viewModel::clearMessage,
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
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.55f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
    }

    companion object {
        private const val TAG = "memory-editor"

        @JvmStatic
        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.findFragmentByTag(TAG) == null) {
                MemoryEditorDialogFragment().show(fragmentManager, TAG)
            }
        }
    }
}
