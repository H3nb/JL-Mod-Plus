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

package io.github.h3nb.jlmodplus.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.h3nb.jlmodplus.R

@Composable
internal fun PerformanceSection(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    ConfigSection(title = stringResource(R.string.config_display_performance)) {
        ConfigNumberPreference(
            title = stringResource(R.string.PREF_LIMIT_FPS),
            description = stringResource(R.string.config_help_fps_limit),
            value = form.fpsLimit,
            fallbackLabel = stringResource(R.string.fps_limit_maximum),
            keyboardType = KeyboardType.Number,
            onValueChange = { value -> onFormChanged(form.toBuilder().fpsLimit(value).build()) },
        )
        ConfigSwitchPreference(
            title = stringResource(R.string.perf_overlay_title),
            description = stringResource(R.string.perf_overlay_help),
            checked = form.showFps,
            onCheckedChange = { checked -> onFormChanged(form.toBuilder().showFps(checked).build()) },
        )
        if (form.showFps) {
            val positions = listOf(
                stringResource(R.string.perf_overlay_top_left),
                stringResource(R.string.perf_overlay_top_right),
                stringResource(R.string.perf_overlay_bottom_left),
                stringResource(R.string.perf_overlay_bottom_right),
            )
            ConfigChoicePreference(
                title = stringResource(R.string.perf_overlay_position),
                description = stringResource(R.string.perf_overlay_position_help),
                selected = positions[form.performanceOverlayPosition],
                options = positions,
                onSelected = { index ->
                    onFormChanged(form.toBuilder().performanceOverlayPosition(index).build())
                },
            )
        }
    }
}
