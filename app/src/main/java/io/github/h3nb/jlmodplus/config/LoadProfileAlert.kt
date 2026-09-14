/*
 * Copyright 2018-2019 Nikita Shakarun
 * Copyright 2019-2023 Yury Kharchenko
 * Modified for JL-Mod Plus.
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

package io.github.h3nb.jlmodplus.config

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.DialogFragment

/** Compose presentation for selecting a saved virtual keyboard layout. */
class LoadProfileAlert : DialogFragment() {
    private var profiles: List<Profile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allProfiles = ProfilesManager.getProfiles()
        allProfiles.sort()
        profiles = ProfilesManager.inspectProfiles(allProfiles)
            .filter { it.keyboardLayout.isReady() }
            .map { it.profile }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val composeView = ComposeView(requireContext())
        ConfigDialogComposeBridge.setLoadProfileContent(
            composeView,
            profiles,
            object : ConfigDialogComposeBridge.LoadProfileCallbacks {
                override fun onDismiss() {
                    dismiss()
                }

                override fun onConfirm(name: String) {
                    val activity = context as? ConfigActivity ?: return
                    if (activity.applyKeyboardLayout(name)) {
                        dismiss()
                    }
                }
            },
        )

        return Dialog(requireContext()).also { dialog ->
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(composeView)
            dialog.setCanceledOnTouchOutside(true)
        }
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            dimAmount = 0.32f
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
    }

    companion object {
        @JvmStatic
        fun newInstance(): LoadProfileAlert = LoadProfileAlert()
    }
}
