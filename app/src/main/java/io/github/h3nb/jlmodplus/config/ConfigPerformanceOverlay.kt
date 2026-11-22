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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.ui.rememberLazyListCanScrollForward
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog

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
            PerformanceOverlayPreferences(form, onFormChanged)
        }
    }
}

@Composable
internal fun PerformanceOverlayPreferences(
    form: ConfigFormState,
    onFormChanged: (ConfigFormState) -> Unit,
) {
    var parametersVisible by rememberSaveable { mutableStateOf(false) }
    val presetNames = listOf(
        stringResource(R.string.perf_overlay_minimal),
        stringResource(R.string.perf_overlay_standard),
        stringResource(R.string.perf_overlay_debug),
        stringResource(R.string.perf_overlay_custom),
    )
    val presetMasks = listOf(
        PerformanceOverlayOptions.MINIMAL,
        PerformanceOverlayOptions.STANDARD,
        PerformanceOverlayOptions.DEBUG,
    )
    val presetIndex = presetMasks.indexOf(form.performanceOverlayMetrics).let { if (it < 0) 3 else it }
    ConfigChoicePreference(
        title = stringResource(R.string.perf_overlay_preset),
        description = stringResource(R.string.perf_overlay_preset_help),
        selected = presetNames[presetIndex],
        options = presetNames,
        onSelected = { index ->
            if (index == 3) {
                parametersVisible = true
            } else {
                onFormChanged(form.toBuilder().performanceOverlayMetrics(presetMasks[index]).build())
            }
        },
    )
    val selectedCount = Integer.bitCount(form.performanceOverlayMetrics)
    ConfigValuePreference(
        title = stringResource(R.string.perf_overlay_parameters),
        description = stringResource(R.string.perf_overlay_parameters_help),
        value = pluralStringResource(R.plurals.perf_overlay_selected, selectedCount, selectedCount),
        onClick = { parametersVisible = true },
    )
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
    if (parametersVisible) {
        PerformanceOverlayParametersDialog(
            selectedMetrics = form.performanceOverlayMetrics,
            onMetricsChanged = { mask ->
                onFormChanged(form.toBuilder().performanceOverlayMetrics(mask).build())
            },
            onDismissRequest = { parametersVisible = false },
        )
    }
}

private data class OverlayParameter(
    val bit: Int,
    val title: Int,
)

private data class OverlayParameterGroup(val title: Int, val parameters: List<OverlayParameter>)

private val overlayParameterGroups = listOf(
    OverlayParameterGroup(R.string.perf_overlay_group_rate, listOf(
        OverlayParameter(PerformanceOverlayOptions.FPS, R.string.perf_overlay_fps),
        OverlayParameter(PerformanceOverlayOptions.CAP, R.string.perf_overlay_cap),
        OverlayParameter(PerformanceOverlayOptions.GUEST_FPS, R.string.perf_overlay_guest_fps),
        OverlayParameter(PerformanceOverlayOptions.SPEED, R.string.perf_overlay_speed),
    )),
    OverlayParameterGroup(R.string.perf_overlay_group_timing, listOf(
        OverlayParameter(PerformanceOverlayOptions.FRAME_INTERVAL, R.string.perf_overlay_interval),
        OverlayParameter(PerformanceOverlayOptions.P95_INTERVAL, R.string.perf_overlay_p95),
        OverlayParameter(PerformanceOverlayOptions.MAX_INTERVAL, R.string.perf_overlay_max),
        OverlayParameter(PerformanceOverlayOptions.RENDER_INTERVAL, R.string.perf_overlay_render_interval),
        OverlayParameter(PerformanceOverlayOptions.RENDER_P95_INTERVAL, R.string.perf_overlay_render_p95),
        OverlayParameter(PerformanceOverlayOptions.RENDER_MAX_INTERVAL, R.string.perf_overlay_render_max),
        OverlayParameter(PerformanceOverlayOptions.COALESCED, R.string.perf_overlay_coalesced),
    )),
    OverlayParameterGroup(R.string.perf_overlay_group_pipeline, listOf(
        OverlayParameter(PerformanceOverlayOptions.PAINT, R.string.perf_overlay_paint),
        OverlayParameter(PerformanceOverlayOptions.COPY, R.string.perf_overlay_copy),
        OverlayParameter(PerformanceOverlayOptions.SUBMIT, R.string.perf_overlay_submit),
    )),
    OverlayParameterGroup(R.string.perf_overlay_group_queue, listOf(
        OverlayParameter(PerformanceOverlayOptions.INPUT_QUEUE, R.string.perf_overlay_input_queue),
        OverlayParameter(PerformanceOverlayOptions.FRAME_QUEUE, R.string.perf_overlay_frame_queue),
    )),
    OverlayParameterGroup(R.string.perf_overlay_group_resources, listOf(
        OverlayParameter(PerformanceOverlayOptions.CPU, R.string.perf_overlay_cpu),
        OverlayParameter(PerformanceOverlayOptions.RAM, R.string.perf_overlay_ram),
        OverlayParameter(PerformanceOverlayOptions.JAVA_HEAP, R.string.perf_overlay_java),
        OverlayParameter(PerformanceOverlayOptions.NATIVE_HEAP, R.string.perf_overlay_native),
    )),
    OverlayParameterGroup(R.string.perf_overlay_group_runtime, listOf(
        OverlayParameter(PerformanceOverlayOptions.RENDERER, R.string.perf_overlay_renderer),
        OverlayParameter(PerformanceOverlayOptions.DISPLAY, R.string.perf_overlay_display),
        OverlayParameter(PerformanceOverlayOptions.THERMAL, R.string.perf_overlay_thermal),
    )),
)

@Composable
internal fun PerformanceOverlayParametersDialog(
    selectedMetrics: Int,
    onMetricsChanged: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val listState = rememberLazyListState()
    val canScrollForward = rememberLazyListCanScrollForward(listState)
    val maxHeight = adaptiveDialogLayout().maxHeight
    AlertDialog(
        onDismissRequest = onDismissRequest,
        textScrollable = false,
        title = { Text(stringResource(R.string.perf_overlay_parameters)) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    overlayParameterGroups.forEach { group ->
                        item(key = "group_${group.title}") {
                            Text(
                                stringResource(group.title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(group.parameters, key = { _, parameter -> parameter.bit }) { _, parameter ->
                            val checked = selectedMetrics and parameter.bit != 0
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .testTag("perf_metric_${parameter.bit}")
                                    .heightIn(min = 48.dp)
                                    .toggleable(value = checked, role = Role.Checkbox) { selected ->
                                        onMetricsChanged(
                                            if (selected) selectedMetrics or parameter.bit
                                            else selectedMetrics and parameter.bit.inv(),
                                        )
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(
                                    stringResource(parameter.title),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                    item(key = "bottom_space") { Spacer(Modifier.height(20.dp)) }
                }
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.perf_overlay_done)) }
        },
    )
}
