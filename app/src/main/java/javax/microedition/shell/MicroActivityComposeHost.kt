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

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.h3nb.jlmodplus.ui.AppComposeTheme

/** Compose root for the emulator chrome plus the allowed Android render boundary. */
object MicroActivityComposeHost {
    @JvmStatic
    fun install(
        activity: ComponentActivity,
        host: MicroActivityHost,
        dialogState: MicroActivityDialogState,
    ) {
        activity.setContent {
            AppComposeTheme {
                MicroActivityContent(host, dialogState)
            }
        }
    }
}

@Composable
private fun MicroActivityContent(
    host: MicroActivityHost,
    dialogState: MicroActivityDialogState,
) {
    val density = LocalDensity.current
    val toolbarHeight = with(density) {
        host.toolbar.getToolbarHeight().coerceAtLeast(1).toDp()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        host.toolbar.Render(
            modifier = if (host.toolbar.getToolbarHeight() > 0) {
                Modifier.fillMaxWidth().height(toolbarHeight)
            } else {
                Modifier.size(1.dp)
            },
        )
        AndroidView(
            factory = { host },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        dialogState.Render()
    }
}
