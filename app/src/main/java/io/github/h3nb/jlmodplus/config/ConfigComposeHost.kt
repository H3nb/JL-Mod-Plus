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

package io.github.h3nb.jlmodplus.config

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Direct Activity host for Config; it does not create an intermediate Android View. */
object ConfigComposeHost {
    @JvmStatic
    fun install(
        activity: ComponentActivity,
        state: ConfigUiState,
        callback: ConfigUiState.Callback,
        dialogState: ConfigDialogState,
    ) {
        activity.setContent {
            state.Render(callback, dialogState)
        }
    }
}
