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
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.DialogFragment
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.ThemedToast

/** Compose presentation for saving a virtual keyboard layout with overwrite confirmation. */
class SaveProfileAlert : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val existingProfileNames = ProfilesManager.getProfiles()
            .mapTo(mutableSetOf()) { it.name }
        val composeView = ComposeView(requireContext())
        ConfigDialogComposeBridge.setSaveProfileContent(
            composeView,
            existingProfileNames,
            object : ConfigDialogComposeBridge.SaveProfileCallbacks {
                override fun onDismiss() {
                    dismiss()
                }

                override fun onConfirm(name: String) {
                    val activity = context as? ConfigActivity ?: return
                    if (activity.saveKeyboardLayout(name)) {
                        ThemedToast.show(activity, getString(R.string.saved, name), Toast.LENGTH_SHORT)
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
        fun newInstance(): SaveProfileAlert = SaveProfileAlert()
    }
}
