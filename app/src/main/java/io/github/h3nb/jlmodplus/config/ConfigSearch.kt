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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import io.github.h3nb.jlmodplus.R

private data class ConfigSearchItem(
    @StringRes val title: Int,
    @StringRes val section: Int,
    val destination: ConfigDestination,
    val detail: ConfigDetail? = null,
    val keywords: String = "",
)

// A small index of existing options, not a second configuration model.
private val configSearchIndex = listOf(
    ConfigSearchItem(R.string.preset_use, R.string.presets, ConfigDestination.Basic, keywords = "profile template"),
    ConfigSearchItem(R.string.preset_save_as, R.string.presets, ConfigDestination.Basic, keywords = "save profile"),
    ConfigSearchItem(R.string.config_screen_size, R.string.config_basic_display, ConfigDestination.Display, keywords = "resolution dimensions"),
    ConfigSearchItem(R.string.PREF_ORIENTATION, R.string.config_basic_display, ConfigDestination.Display, keywords = "rotation portrait landscape"),
    ConfigSearchItem(R.string.pref_screen_scale_type, R.string.config_basic_display, ConfigDestination.Display, keywords = "scale zoom"),
    ConfigSearchItem(R.string.PREF_SCALE_RATIO, R.string.config_basic_display, ConfigDestination.Display, keywords = "manual scale"),
    ConfigSearchItem(R.string.pref_screen_gravity, R.string.config_basic_display, ConfigDestination.Display, keywords = "position"),
    ConfigSearchItem(R.string.pref_screen_padding_title, R.string.config_basic_display, ConfigDestination.Display),
    ConfigSearchItem(R.string.config_avoid_camera_cutout_title, R.string.config_basic_display, ConfigDestination.Display, keywords = "notch punch hole camera"),
    ConfigSearchItem(R.string.PREF_FORCE_FULLSCREEN, R.string.config_basic_display, ConfigDestination.Display),
    ConfigSearchItem(R.string.config_background_mode, R.string.config_display_appearance, ConfigDestination.Display),
    ConfigSearchItem(R.string.config_background_custom_color, R.string.config_display_appearance, ConfigDestination.Display, keywords = "background color"),
    ConfigSearchItem(R.string.pref_skin_title, R.string.config_display_appearance, ConfigDestination.Display, keywords = "skin frame bezel"),
    ConfigSearchItem(R.string.pref_graphics_mode_title, R.string.config_display_rendering, ConfigDestination.Graphics, keywords = "renderer OpenGL ES"),
    ConfigSearchItem(R.string.internal_resolution_title, R.string.config_display_rendering, ConfigDestination.Graphics, keywords = "resolution scaling"),
    ConfigSearchItem(R.string.bitmap_upscaling_title, R.string.config_display_rendering, ConfigDestination.Graphics, keywords = "MMPX nearest bilinear edge aware sprites"),
    ConfigSearchItem(R.string.render_shader_effects, R.string.config_display_rendering, ConfigDestination.Graphics, keywords = "shader GLSL effects"),
    ConfigSearchItem(R.string.render_texture_filtering, R.string.config_display_rendering, ConfigDestination.Graphics, keywords = "texture filtering"),
    ConfigSearchItem(R.string.parallel_screen_redrawing, R.string.config_display_rendering, ConfigDestination.Graphics),
    ConfigSearchItem(R.string.config_font_sizes, R.string.config_display_text, ConfigDestination.Display, ConfigDetail.Font, "font text"),
    ConfigSearchItem(R.string.SIZE_PRESETS, R.string.config_display_text, ConfigDestination.Display, ConfigDetail.Font),
    ConfigSearchItem(R.string.PREF_FONT_SIZE_IN_SP, R.string.config_display_text, ConfigDestination.Display, ConfigDetail.Font),
    ConfigSearchItem(R.string.PREF_FONT_ANTI_ALIASING, R.string.config_display_text, ConfigDestination.Display, ConfigDetail.Font),
    ConfigSearchItem(R.string.PREF_LIMIT_FPS, R.string.config_display_performance, ConfigDestination.Graphics, ConfigDetail.Performance, "framerate fps"),
    ConfigSearchItem(R.string.perf_overlay_title, R.string.config_display_performance, ConfigDestination.Graphics, ConfigDetail.Performance, "stats performance"),
    ConfigSearchItem(R.string.perf_overlay_preset, R.string.config_display_performance, ConfigDestination.Graphics, ConfigDetail.Performance),
    ConfigSearchItem(R.string.perf_overlay_parameters, R.string.config_display_performance, ConfigDestination.Graphics, ConfigDetail.Performance),
    ConfigSearchItem(R.string.perf_overlay_position, R.string.config_display_performance, ConfigDestination.Graphics, ConfigDetail.Performance),
    ConfigSearchItem(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS, R.string.config_basic_input, ConfigDestination.Controls, keywords = "virtual keys controls"),
    ConfigSearchItem(R.string.PREF_TOUCH_INPUT, R.string.config_basic_input, ConfigDestination.Controls),
    ConfigSearchItem(R.string.PREF_LAYOUT, R.string.config_controls_key_input, ConfigDestination.Controls),
    ConfigSearchItem(R.string.pref_map_keys, R.string.config_controls_key_input, ConfigDestination.Controls, keywords = "key mapping keyboard"),
    ConfigSearchItem(R.string.config_analog_stick, R.string.config_controls_key_input, ConfigDestination.Controls, keywords = "gamepad joystick"),
    ConfigSearchItem(R.string.config_gamepad_calibrate, R.string.config_controls_key_input, ConfigDestination.Controls, keywords = "gamepad calibration"),
    ConfigSearchItem(R.string.PREF_VK_FEEDBACK, R.string.config_controls_virtual_keyboard, ConfigDestination.Controls),
    ConfigSearchItem(R.string.PREF_VK_HIDE_DELAY, R.string.config_controls_virtual_keyboard, ConfigDestination.Controls),
    ConfigSearchItem(R.string.pref_button_shape_title, R.string.config_controls_customization, ConfigDestination.Controls),
    ConfigSearchItem(R.string.control_skin_title, R.string.config_controls_customization, ConfigDestination.Controls),
    ConfigSearchItem(R.string.PREF_VK_ALPHA, R.string.config_controls_customization, ConfigDestination.Controls, keywords = "transparency opacity"),
    ConfigSearchItem(R.string.config_controls_opaque_outside, R.string.config_controls_customization, ConfigDestination.Controls),
    ConfigSearchItem(R.string.config_controls_virtual_keyboard_colors, R.string.config_controls_customization, ConfigDestination.Controls, keywords = "button colors palette"),
    ConfigSearchItem(R.string.RESET_LAYOUT_CMD, R.string.config_controls_maintenance, ConfigDestination.Controls),
    ConfigSearchItem(R.string.pref_soundbank_title, R.string.pref_audio_title, ConfigDestination.Media, keywords = "MIDI audio"),
    ConfigSearchItem(R.string.config_camera_default_title, R.string.config_camera_title, ConfigDestination.Media),
    ConfigSearchItem(R.string.config_camera_resolution_title, R.string.config_camera_title, ConfigDestination.Media),
    ConfigSearchItem(R.string.midlet_storage_mode_title, R.string.midlet_permissions_settings_title, ConfigDestination.System, keywords = "files storage"),
    ConfigSearchItem(R.string.midlet_permissions_read, R.string.midlet_permissions_settings_title, ConfigDestination.System),
    ConfigSearchItem(R.string.midlet_permissions_write, R.string.midlet_permissions_settings_title, ConfigDestination.System),
    ConfigSearchItem(R.string.pref_skip_resume_call, R.string.config_system_compatibility, ConfigDestination.System),
    ConfigSearchItem(R.string.PREF_IMMEDIATE, R.string.config_system_compatibility, ConfigDestination.System),
    ConfigSearchItem(R.string.PREF_TIMING_MODE, R.string.config_system_timing, ConfigDestination.System),
    ConfigSearchItem(R.string.pref_encoding_title, R.string.PREF_SYS_PROPS, ConfigDestination.System),
    ConfigSearchItem(R.string.config_edit_system_properties, R.string.PREF_SYS_PROPS, ConfigDestination.System, keywords = "properties CLDC Java ME"),
    ConfigSearchItem(R.string.config_reset_all_settings, R.string.config_midlet_actions, ConfigDestination.Basic),
    ConfigSearchItem(R.string.config_reset_profile_settings, R.string.config_profile_maintenance, ConfigDestination.Basic),
    ConfigSearchItem(R.string.config_delete_app_data, R.string.config_midlet_actions, ConfigDestination.Basic, keywords = "clear data"),
)

@Composable
internal fun ConfigSearchResults(
    query: String,
    state: ConfigUiState,
    isProfile: Boolean,
    cutoutAvailable: Boolean = false,
    onSelected: (ConfigDestination, String, ConfigDetail?, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val suggestions = setOf(
        R.string.config_screen_size, R.string.PREF_ORIENTATION,
        R.string.pref_graphics_mode_title, R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS,
        R.string.pref_map_keys, R.string.PREF_TIMING_MODE,
    )
    val filtered = configSearchIndex.filter { entry ->
        if (isProfile && entry.destination == ConfigDestination.Basic &&
                entry.title != R.string.config_reset_profile_settings) return@filter false
        if (!isProfile && entry.title == R.string.config_reset_profile_settings) return@filter false
        if (isProfile && entry.title in setOf(
                R.string.midlet_storage_mode_title,
                R.string.midlet_permissions_read,
                R.string.midlet_permissions_write,
            )) return@filter false
        when (entry.title) {
            R.string.PREF_SCALE_RATIO ->
                if (state.form.screenScaleType != 0) return@filter false
            R.string.config_avoid_camera_cutout_title ->
                if (!cutoutAvailable) return@filter false
            R.string.config_background_custom_color -> {
                val mode = BackgroundMode.sanitize(state.form.screenBackgroundMode)
                if (mode != BackgroundMode.CUSTOM &&
                    mode != BackgroundMode.DYNAMIC_SOLID
                ) return@filter false
            }
            R.string.bitmap_upscaling_title ->
                if (state.form.internalResolutionScale == 1) return@filter false
            R.string.render_shader_effects ->
                if (state.form.graphicsMode != 1) return@filter false
            R.string.parallel_screen_redrawing ->
                if (state.form.graphicsMode != 0 && state.form.graphicsMode != 3) return@filter false
            R.string.perf_overlay_preset,
            R.string.perf_overlay_parameters,
            R.string.perf_overlay_position,
            -> if (!state.form.showFps) return@filter false
        }
        if (query.isBlank()) entry.title in suggestions
        else resources.getString(entry.title).contains(query.trim(), ignoreCase = true) ||
            resources.getString(entry.section).contains(query.trim(), ignoreCase = true) ||
            entry.keywords.contains(query.trim(), ignoreCase = true)
    }
    LazyColumn(modifier = modifier.imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (query.isBlank()) {
            item {
                Text(stringResource(R.string.config_search_suggestions),
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(stringResource(R.string.config_search_no_results),
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(filtered, key = { _, entry -> entry.title }) { _, entry ->
            ListItem(
                headlineContent = { Text(stringResource(entry.title)) },
                supportingContent = {
                    Text(stringResource(entry.destination.label) + " · " +
                        stringResource(entry.section))
                },
                modifier = Modifier.clickable {
                    onSelected(
                        entry.destination,
                        resources.getString(entry.section),
                        entry.detail,
                        resources.getString(entry.title),
                    )
                },
            )
        }
    }
}
