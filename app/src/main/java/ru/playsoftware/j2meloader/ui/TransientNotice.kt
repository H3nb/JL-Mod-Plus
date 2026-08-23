/*
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

package ru.playsoftware.j2meloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Snackbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal data class TransientNotice(
    val id: Long,
    val message: String,
)

internal class TransientNoticeState {
    private var nextId = 0L
    var notice by mutableStateOf<TransientNotice?>(null)
        private set

    fun show(message: String) {
        notice = TransientNotice(++nextId, message)
    }

    fun dismiss(id: Long) {
        if (notice?.id == id) notice = null
    }
}

@Composable
internal fun TransientNoticeHost(
    state: TransientNoticeState,
    modifier: Modifier = Modifier,
) {
    val current = state.notice ?: return
    LaunchedEffect(current.id) {
        delay(3_500)
        state.dismiss(current.id)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Snackbar(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Text(current.message)
        }
    }
}

/** Java-callable host used where the runtime still owns a View hierarchy. */
class TransientNoticeComposeController(composeView: ComposeView) {
    private val state = TransientNoticeState()

    init {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                TransientNoticeHost(state = state)
            }
        }
    }

    fun show(message: String) {
        state.show(message)
    }
}
