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

package io.github.h3nb.jlmodplus.input

import android.os.Handler
import android.os.SystemClock

/** Main-handler repeat scheduler shared by Canvas and native host output ledgers. */
internal class AndroidRepeatScheduler(
    private val handler: Handler,
) : RepeatScheduler {
    override fun nowMillis(): Long = SystemClock.elapsedRealtime()

    override fun schedule(
        initialDelayMillis: Long,
        intervalMillis: Long,
        task: () -> Unit,
    ): RepeatHandle {
        val state = State()
        val runnable = object : Runnable {
            override fun run() {
                if (state.cancelled) return
                task()
                if (!state.cancelled) handler.postDelayed(this, intervalMillis)
            }
        }
        handler.postDelayed(runnable, initialDelayMillis)
        return RepeatHandle {
            state.cancelled = true
            handler.removeCallbacks(runnable)
        }
    }

    private class State {
        @Volatile var cancelled: Boolean = false
    }
}
