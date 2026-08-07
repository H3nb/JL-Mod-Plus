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

import android.os.Looper
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Process-local synchronous MIDlet permission prompt rendered by the emulator Compose host. */
object MidletPermissionDialogState {
    private const val REQUEST_TIMEOUT_SECONDS = 60L

    enum class Result {
        ALLOW_ONCE,
        ALWAYS_ALLOW,
        DENY,
    }

    private data class Request(
        val title: String,
        val message: String,
        val allowOnce: String,
        val alwaysAllow: String,
        val deny: String,
        val result: AtomicReference<Result>,
        val latch: CountDownLatch,
    )

    private var request by mutableStateOf<Request?>(null)

    val isDialogVisible: Boolean
        get() = request != null

    @JvmStatic
    fun request(
        activity: MicroActivity,
        title: String,
        message: String,
        allowOnce: String,
        alwaysAllow: String,
        deny: String,
    ): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "MIDlet permission requests must not block the Android main thread"
        }
        val result = AtomicReference(Result.DENY)
        val latch = CountDownLatch(1)
        val newRequest = Request(title, message, allowOnce, alwaysAllow, deny, result, latch)
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                latch.countDown()
            } else {
                request = newRequest
            }
        }
        return try {
            if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                activity.runOnUiThread {
                    if (request === newRequest) {
                        finish(Result.DENY)
                    }
                }
                Result.DENY
            } else {
                result.get()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            activity.runOnUiThread {
                if (request === newRequest) {
                    finish(Result.DENY)
                }
            }
            Result.DENY
        }
    }

    private fun finish(value: Result) {
        val current = request ?: return
        current.result.set(value)
        request = null
        current.latch.countDown()
    }

    @Composable
    fun Render() {
        val current = request ?: return
        AlertDialog(
            onDismissRequest = { finish(Result.DENY) },
            title = { Text(current.title) },
            text = { Text(current.message) },
            confirmButton = {
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { finish(Result.DENY) }) {
                        Text(current.deny)
                    }
                    TextButton(onClick = { finish(Result.ALLOW_ONCE) }) {
                        Text(current.allowOnce)
                    }
                    TextButton(onClick = { finish(Result.ALWAYS_ALLOW) }) {
                        Text(current.alwaysAllow)
                    }
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        )
    }
}
