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

import android.content.Context
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import io.github.h3nb.jlmodplus.R
import javax.microedition.lcdui.overlay.OverlayView

class MicroActivityHost(
    context: Context,
    actionCallback: MicroActivityToolbarView.ActionCallback,
) : FrameLayout(context) {
    @JvmField
    val toolbar: MicroActivityToolbarView = MicroActivityToolbarView(actionCallback)
    @JvmField
    val displayableContainer: FrameLayout = FrameLayout(context)
    @JvmField
    val overlay: OverlayView = OverlayView(context, null)

    init {
        overlay.id = R.id.overlay
        displayableContainer.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        addView(displayableContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Applies safe insets only to the non-overlay host surface. */
    fun setContentInsets(insets: Insets) {
        displayableContainer.setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }
}
