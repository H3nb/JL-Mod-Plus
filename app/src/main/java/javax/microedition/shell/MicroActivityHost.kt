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

package javax.microedition.shell

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import io.github.h3nb.jlmodplus.R
import javax.microedition.lcdui.overlay.OverlayView

@SuppressLint("ViewConstructor")
class MicroActivityHost(
    context: Context,
    actionCallback: MicroActivityToolbarView.ActionCallback,
) : FrameLayout(context) {
    @JvmField
    val toolbar: MicroActivityToolbarView = MicroActivityToolbarView(context, actionCallback)
    @JvmField
    val displayableContainer: FrameLayout = FrameLayout(context)
    @JvmField
    val overlay: OverlayView = OverlayView(context, null)
    @JvmField
    val virtualDisplay: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    init {
        overlay.id = R.id.overlay
        toolbar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        virtualDisplay.addView(toolbar)
        displayableContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
        virtualDisplay.addView(displayableContainer)
        addView(virtualDisplay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Applies safe insets to the native emulator surface and its toolbar. */
    fun setContentInsets(insets: Insets) {
        virtualDisplay.setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }
}
