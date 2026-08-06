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

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import kotlin.math.roundToInt

/** Compose state for the configuration screen. Java keeps persistence and platform contracts. */
class ConfigUiState(
    context: Context,
    private val showExperimental: Boolean,
) {
    interface Callback {
        fun onScreenPresets()
        fun onSwapSizes()
        fun onAddResolution()
        fun onFontPresets()
        fun onColorPicker(field: String)
        fun onKeyMappings()
        fun onEncoding()
        fun onShaderTune()
        fun onGraphicsModeSelected(position: Int)
        fun onShaderSelected(position: Int)
        fun onSecureConnectionSelected(position: Int)
        fun onShowKeyboardChanged(visible: Boolean)
        fun onBack()
        fun onToolbarAction(actionId: Int)
    }

    private var toolbarTitleState by mutableStateOf(
        (context as? Activity)?.title?.toString().orEmpty(),
    )

    private var screenWidthState by mutableStateOf("")
    private var screenHeightState by mutableStateOf("")
    private var screenBackgroundState by mutableStateOf("")
    private var scaleRatioState by mutableStateOf("")
    private var screenPaddingState by mutableStateOf("")
    private var fpsLimitState by mutableStateOf("")
    private var fontSmallState by mutableStateOf("")
    private var fontMediumState by mutableStateOf("")
    private var fontLargeState by mutableStateOf("")
    private var vkHideDelayState by mutableStateOf("")
    private var vkBackState by mutableStateOf("")
    private var vkForeState by mutableStateOf("")
    private var vkSelectedBackState by mutableStateOf("")
    private var vkSelectedForeState by mutableStateOf("")
    private var vkOutlineState by mutableStateOf("")
    private var systemPropertiesState by mutableStateOf("")

    private var lockAspectState by mutableStateOf(false)
    private var filterState by mutableStateOf(false)
    private var immediateState by mutableStateOf(false)
    private var parallelState by mutableStateOf(false)
    private var forceFullscreenState by mutableStateOf(false)
    private var showFpsState by mutableStateOf(false)
    private var fontInSpState by mutableStateOf(false)
    private var fontAaState by mutableStateOf(false)
    private var touchInputState by mutableStateOf(false)
    private var showKeyboardState by mutableStateOf(true)
    private var vkFeedbackState by mutableStateOf(false)
    private var vkForceOpacityState by mutableStateOf(false)
    private var skipResumeState by mutableStateOf(false)

    private var skinOptionsState by mutableStateOf(emptyList<String>())
    private var soundBankOptionsState by mutableStateOf(emptyList<String>())
    private var shaderOptionsState by mutableStateOf(emptyList<ShaderInfo>())
    private var skinSelectionState by mutableIntStateOf(0)
    private var orientationSelectionState by mutableIntStateOf(0)
    private var scaleTypeSelectionState by mutableIntStateOf(0)
    private var gravitySelectionState by mutableIntStateOf(0)
    private var graphicsModeSelectionState by mutableIntStateOf(0)
    private var shaderSelectionState by mutableIntStateOf(0)
    private var layoutSelectionState by mutableIntStateOf(0)
    private var buttonShapeSelectionState by mutableIntStateOf(0)
    private var secureConnectionSelectionState by mutableIntStateOf(0)
    private var soundBankSelectionState by mutableIntStateOf(0)
    private var vkAlphaState by mutableIntStateOf(0)
    private var shaderTuningAvailableState by mutableStateOf(false)
    private var aspectRatioState by mutableFloatStateOf(0f)

    private val orientationOptions = context.resources.getStringArray(R.array.PREF_ORIENTATION_ENTRIES).toList()
    private val scaleTypeOptions = context.resources.getStringArray(R.array.pref_scale_type_entries).toList()
    private val gravityOptions = context.resources.getStringArray(R.array.pref_screen_gravity_entries).toList()
    private val graphicsModeOptions = context.resources.getStringArray(R.array.pref_graphics_mode_entries).toList()
    private val layoutOptions = context.resources.getStringArray(R.array.PREF_LAYOUT_ENTRIES).toList()
    private val buttonShapeOptions = context.resources.getStringArray(R.array.pref_button_shape_entries).toList()
    private val secureConnectionOptions = context.resources.getStringArray(R.array.secure_connection_mode_entries).toList()

    fun getScreenWidthText(): String = screenWidthState
    fun setScreenWidthText(value: String) { screenWidthState = value }
    fun getScreenHeightText(): String = screenHeightState
    fun setScreenHeightText(value: String) { screenHeightState = value }
    fun getScreenBackgroundText(): String = screenBackgroundState
    fun setScreenBackgroundText(value: String) { screenBackgroundState = normalizeHex(value) }
    fun getScaleRatioText(): String = scaleRatioState
    fun setScaleRatioText(value: String) { scaleRatioState = limitNumber(value, 1000) }
    fun getScreenPaddingText(): String = screenPaddingState
    fun setScreenPaddingText(value: String) { screenPaddingState = value }
    fun getFpsLimitText(): String = fpsLimitState
    fun setFpsLimitText(value: String) { fpsLimitState = value }
    fun getFontSmallText(): String = fontSmallState
    fun setFontSmallText(value: String) { fontSmallState = value }
    fun getFontMediumText(): String = fontMediumState
    fun setFontMediumText(value: String) { fontMediumState = value }
    fun getFontLargeText(): String = fontLargeState
    fun setFontLargeText(value: String) { fontLargeState = value }
    fun getVkHideDelayText(): String = vkHideDelayState
    fun setVkHideDelayText(value: String) { vkHideDelayState = value }
    fun getVkBackText(): String = vkBackState
    fun setVkBackText(value: String) { vkBackState = normalizeHex(value) }
    fun getVkForeText(): String = vkForeState
    fun setVkForeText(value: String) { vkForeState = normalizeHex(value) }
    fun getVkSelectedBackText(): String = vkSelectedBackState
    fun setVkSelectedBackText(value: String) { vkSelectedBackState = normalizeHex(value) }
    fun getVkSelectedForeText(): String = vkSelectedForeState
    fun setVkSelectedForeText(value: String) { vkSelectedForeState = normalizeHex(value) }
    fun getVkOutlineText(): String = vkOutlineState
    fun setVkOutlineText(value: String) { vkOutlineState = normalizeHex(value) }
    fun getSystemPropertiesText(): String = systemPropertiesState
    fun setSystemPropertiesText(value: String) { systemPropertiesState = value }
    fun setToolbarTitle(value: String) { toolbarTitleState = value }

    fun isFilterChecked(): Boolean = filterState
    fun setFilterChecked(value: Boolean) { filterState = value }
    fun isImmediateChecked(): Boolean = immediateState
    fun setImmediateChecked(value: Boolean) { immediateState = value }
    fun isParallelChecked(): Boolean = parallelState
    fun setParallelChecked(value: Boolean) { parallelState = value }
    fun isForceFullscreenChecked(): Boolean = forceFullscreenState
    fun setForceFullscreenChecked(value: Boolean) { forceFullscreenState = value }
    fun isShowFpsChecked(): Boolean = showFpsState
    fun setShowFpsChecked(value: Boolean) { showFpsState = value }
    fun isFontInSpChecked(): Boolean = fontInSpState
    fun setFontInSpChecked(value: Boolean) { fontInSpState = value }
    fun isFontAaChecked(): Boolean = fontAaState
    fun setFontAaChecked(value: Boolean) { fontAaState = value }
    fun isTouchInputChecked(): Boolean = touchInputState
    fun setTouchInputChecked(value: Boolean) { touchInputState = value }
    fun isShowKeyboardChecked(): Boolean = showKeyboardState
    fun setShowKeyboardChecked(value: Boolean) { showKeyboardState = value }
    fun isVkFeedbackChecked(): Boolean = vkFeedbackState
    fun setVkFeedbackChecked(value: Boolean) { vkFeedbackState = value }
    fun isVkForceOpacityChecked(): Boolean = vkForceOpacityState
    fun setVkForceOpacityChecked(value: Boolean) { vkForceOpacityState = value }
    fun isSkipResumeChecked(): Boolean = skipResumeState
    fun setSkipResumeChecked(value: Boolean) { skipResumeState = value }

    fun getSkinSelection(): Int = skinSelectionState
    fun setSkinSelection(value: Int) { skinSelectionState = value.coerceIn(0, (skinOptionsState.size - 1).coerceAtLeast(0)) }
    fun setSkinSelection(value: String?) { setSkinSelection(if (value == null) 0 else skinOptionsState.indexOf(value).coerceAtLeast(0)) }
    fun getSkinSelectedItem(): String? = skinOptionsState.getOrNull(skinSelectionState)?.takeUnless { skinSelectionState == 0 }
    fun setSkinOptions(value: List<String>) { skinOptionsState = value.toList(); setSkinSelection(skinSelectionState) }
    fun getSoundBankSelection(): Int = soundBankSelectionState
    fun setSoundBankSelection(value: Int) { soundBankSelectionState = value.coerceIn(0, (soundBankOptionsState.size - 1).coerceAtLeast(0)) }
    fun setSoundBankSelection(value: String?) { setSoundBankSelection(if (value == null) 0 else soundBankOptionsState.indexOf(value).coerceAtLeast(0)) }
    fun getSoundBankSelectedItem(): String? = soundBankOptionsState.getOrNull(soundBankSelectionState)?.takeUnless { soundBankSelectionState == 0 }
    fun setSoundBankOptions(value: List<String>) { soundBankOptionsState = value.toList(); setSoundBankSelection(soundBankSelectionState) }

    fun getOrientationSelection(): Int = orientationSelectionState
    fun setOrientationSelection(value: Int) { orientationSelectionState = value.coerceIn(0, orientationOptions.lastIndex.coerceAtLeast(0)) }
    fun getScaleTypeSelection(): Int = scaleTypeSelectionState
    fun setScaleTypeSelection(value: Int) { scaleTypeSelectionState = value.coerceIn(0, scaleTypeOptions.lastIndex.coerceAtLeast(0)) }
    fun getGravitySelection(): Int = gravitySelectionState
    fun setGravitySelection(value: Int) { gravitySelectionState = value.coerceIn(0, gravityOptions.lastIndex.coerceAtLeast(0)) }
    fun getGraphicsModeSelection(): Int = graphicsModeSelectionState
    fun setGraphicsModeSelection(value: Int) { graphicsModeSelectionState = value.coerceIn(0, graphicsModeOptions.lastIndex.coerceAtLeast(0)) }
    fun getSelectedShaderPosition(): Int = shaderSelectionState
    fun setSelectedShaderPosition(value: Int) { shaderSelectionState = value.coerceIn(0, (shaderOptionsState.size - 1).coerceAtLeast(0)) }
    fun getSelectedShader(): ShaderInfo? = shaderOptionsState.getOrNull(shaderSelectionState)
    fun setShaderOptions(value: List<ShaderInfo>) { shaderOptionsState = value.toList(); setSelectedShaderPosition(shaderSelectionState) }
    fun setShaderTuningAvailable(value: Boolean) { shaderTuningAvailableState = value }
    fun getLayoutSelection(): Int = layoutSelectionState
    fun setLayoutSelection(value: Int) { layoutSelectionState = value.coerceIn(0, layoutOptions.lastIndex.coerceAtLeast(0)) }
    fun getButtonShapeSelection(): Int = buttonShapeSelectionState
    fun setButtonShapeSelection(value: Int) { buttonShapeSelectionState = value.coerceIn(0, buttonShapeOptions.lastIndex.coerceAtLeast(0)) }
    fun getSecureConnectionSelection(): Int = secureConnectionSelectionState
    fun setSecureConnectionSelection(value: Int) { secureConnectionSelectionState = value.coerceIn(0, secureConnectionOptions.lastIndex.coerceAtLeast(0)) }
    fun getSoundBankSelectionIndex(): Int = soundBankSelectionState
    fun getVkAlphaProgress(): Int = vkAlphaState
    fun setVkAlphaProgress(value: Int) { vkAlphaState = value.coerceIn(0, 255) }

    fun swapSizes() {
        val width = screenWidthState
        screenWidthState = screenHeightState
        screenHeightState = width
    }

    fun setColorText(field: String, value: String) {
        val normalized = normalizeHex(value)
        when (field) {
            COLOR_SCREEN_BACKGROUND -> screenBackgroundState = normalized
            COLOR_VK_BACK -> vkBackState = normalized
            COLOR_VK_FORE -> vkForeState = normalized
            COLOR_VK_SELECTED_BACK -> vkSelectedBackState = normalized
            COLOR_VK_SELECTED_FORE -> vkSelectedForeState = normalized
            COLOR_VK_OUTLINE -> vkOutlineState = normalized
        }
    }

    fun onResolutionFocusLost(widthField: Boolean) {
        if (!lockAspectState || aspectRatioState <= 0f) return
        val source = (if (widthField) screenWidthState else screenHeightState).toIntOrNull() ?: return
        if (source <= 0) return
        if (widthField) {
            screenHeightState = (source * aspectRatioState).roundToInt().toString()
        } else {
            screenWidthState = (source / aspectRatioState).roundToInt().toString()
        }
    }

    fun setLockAspectChecked(value: Boolean) {
        if (value) {
            val width = screenWidthState.toFloatOrNull() ?: 0f
            val height = screenHeightState.toFloatOrNull() ?: 0f
            if (width <= 0f || height <= 0f) {
                lockAspectState = false
                return
            }
            aspectRatioState = height / width
        }
        lockAspectState = value
    }

    fun isLockAspectChecked(): Boolean = lockAspectState

    private fun updateText(field: TextFieldId, value: String) {
        when (field) {
            TextFieldId.SCREEN_WIDTH -> screenWidthState = value
            TextFieldId.SCREEN_HEIGHT -> screenHeightState = value
            TextFieldId.SCALE_RATIO -> scaleRatioState = limitNumber(value, 1000)
            TextFieldId.PADDING -> screenPaddingState = value
            TextFieldId.FPS -> fpsLimitState = value
            TextFieldId.FONT_SMALL -> fontSmallState = value
            TextFieldId.FONT_MEDIUM -> fontMediumState = value
            TextFieldId.FONT_LARGE -> fontLargeState = value
            TextFieldId.VK_HIDE -> vkHideDelayState = value
        }
    }

    private fun textValue(field: TextFieldId): String = when (field) {
        TextFieldId.SCREEN_WIDTH -> screenWidthState
        TextFieldId.SCREEN_HEIGHT -> screenHeightState
        TextFieldId.SCALE_RATIO -> scaleRatioState
        TextFieldId.PADDING -> screenPaddingState
        TextFieldId.FPS -> fpsLimitState
        TextFieldId.FONT_SMALL -> fontSmallState
        TextFieldId.FONT_MEDIUM -> fontMediumState
        TextFieldId.FONT_LARGE -> fontLargeState
        TextFieldId.VK_HIDE -> vkHideDelayState
    }

    private fun updateColor(field: String, value: String) = setColorText(field, value)
    private fun colorValue(field: String): String = when (field) {
        COLOR_SCREEN_BACKGROUND -> screenBackgroundState
        COLOR_VK_BACK -> vkBackState
        COLOR_VK_FORE -> vkForeState
        COLOR_VK_SELECTED_BACK -> vkSelectedBackState
        COLOR_VK_SELECTED_FORE -> vkSelectedForeState
        COLOR_VK_OUTLINE -> vkOutlineState
        else -> ""
    }

    private fun colorSwatch(value: String): Color {
        val rgb = value.toLongOrNull(16)?.toInt() ?: return Color.Transparent
        return Color(
            red = ((rgb shr 16) and 0xFF) / 255f,
            green = ((rgb shr 8) and 0xFF) / 255f,
            blue = (rgb and 0xFF) / 255f,
        )
    }

    private fun normalizeHex(value: String): String = value.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
    private fun limitNumber(value: String, max: Int): String {
        val filtered = value.filter(Char::isDigit).take(4)
        val number = filtered.toIntOrNull() ?: return filtered
        return minOf(number, max).toString()
    }

    private enum class TextFieldId { SCREEN_WIDTH, SCREEN_HEIGHT, SCALE_RATIO, PADDING, FPS, FONT_SMALL, FONT_MEDIUM, FONT_LARGE, VK_HIDE }

    companion object {
        const val COLOR_SCREEN_BACKGROUND = "screenBackground"
        const val COLOR_VK_BACK = "vkBack"
        const val COLOR_VK_FORE = "vkFore"
        const val COLOR_VK_SELECTED_BACK = "vkSelectedBack"
        const val COLOR_VK_SELECTED_FORE = "vkSelectedFore"
        const val COLOR_VK_OUTLINE = "vkOutline"
    }

    @Composable
    private fun ConfigContent(
        state: ConfigUiState,
        showExperimental: Boolean,
        callback: Callback,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            ConfigTopBar(state.toolbarTitleState, showExperimental, callback)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                ScreenSection(state, callback)
                FontSection(state, callback)
                InputSection(state, callback)
                EmulationSection(state)
                if (showExperimental) ExperimentalSection(state, callback)
                AudioSection(state)
                SystemSection(state, callback)
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun ConfigTopBar(title: String, showExperimental: Boolean, callback: Callback) {
        var menuExpanded by remember { mutableStateOf(false) }
        val backDescription = stringResource(R.string.back)
        val moreDescription = stringResource(R.string.more)
        Surface(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            color = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            shadowElevation = 4.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    modifier = Modifier.semantics {
                        contentDescription = backDescription
                    },
                    onClick = callback::onBack,
                ) {
                    ConfigBackGlyph(MaterialTheme.colorScheme.onSecondary)
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSecondary,
                    fontSize = 20.sp,
                    maxLines = 1,
                )
                if (showExperimental) {
                    TextButton(
                        onClick = { callback.onToolbarAction(R.id.action_start) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                    ) {
                        Text(stringResource(R.string.START_CMD))
                    }
                }
                Box {
                    IconButton(
                        modifier = Modifier.semantics {
                            contentDescription = moreDescription
                        },
                        onClick = { menuExpanded = true },
                    ) {
                        ConfigMoreGlyph(MaterialTheme.colorScheme.onSecondary)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        val actions = buildList {
                            if (showExperimental) {
                                add(R.id.action_clear_data to R.string.CLEAR_DATA_CMD)
                                add(R.id.action_rms_editor to R.string.rms_editor)
                            }
                            add(R.id.action_reset_settings to R.string.RESET_SETTINGS_CMD)
                            add(R.id.action_reset_layout to R.string.RESET_LAYOUT_CMD)
                            add(R.id.action_load_profile to R.string.load_profile)
                            add(R.id.action_save_profile to R.string.save_profile)
                        }
                        actions.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label), color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    menuExpanded = false
                                    callback.onToolbarAction(id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConfigBackGlyph(color: Color) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(color, Offset(19.dp.toPx(), size.height / 2), Offset(5.dp.toPx(), size.height / 2), stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(5.dp.toPx(), size.height / 2), Offset(11.dp.toPx(), 6.dp.toPx()), stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(5.dp.toPx(), size.height / 2), Offset(11.dp.toPx(), size.height - 6.dp.toPx()), stroke, cap = StrokeCap.Round)
        }
    }

    @Composable
    private fun ConfigMoreGlyph(color: Color) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val radius = 1.7.dp.toPx()
            listOf(0.25f, 0.5f, 0.75f).forEach { position ->
                drawCircle(color, radius, Offset(size.width / 2f, size.height * position))
            }
        }
    }

    @Composable
    private fun ScreenSection(state: ConfigUiState, callback: Callback) {
        ConfigCard(R.string.PREF_SCREEN_OPTIONS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = callback::onScreenPresets) {
                    Icon(painter = painterResource(R.drawable.ic_list), contentDescription = stringResource(R.string.SIZE_PRESETS), tint = MaterialTheme.colorScheme.primary)
                }
                ConfigTextField(state.textValue(TextFieldId.SCREEN_WIDTH), { state.updateText(TextFieldId.SCREEN_WIDTH, it) }, R.string.PREF_WIDTH, Modifier.weight(1f), onFocusLost = { state.onResolutionFocusLost(true) }, numeric = true)
                IconButton(onClick = callback::onSwapSizes) {
                    Icon(painter = painterResource(R.drawable.ic_swap), contentDescription = stringResource(R.string.SWAP_SIZES), tint = MaterialTheme.colorScheme.primary)
                }
                ConfigTextField(state.textValue(TextFieldId.SCREEN_HEIGHT), { state.updateText(TextFieldId.SCREEN_HEIGHT, it) }, R.string.PREF_HEIGHT, Modifier.weight(1f), onFocusLost = { state.onResolutionFocusLost(false) }, numeric = true)
                IconButton(onClick = callback::onAddResolution) {
                    Icon(painter = painterResource(R.drawable.ic_add_preset), contentDescription = stringResource(R.string.add_resolution_preset), tint = MaterialTheme.colorScheme.primary)
                }
            }
            ConfigSwitch(R.string.PREF_KEEP_ASPECT_RATIO, state.isLockAspectChecked()) { state.setLockAspectChecked(it) }
            ConfigColorRow(R.string.PREF_BACKGROUND, state.colorValue(COLOR_SCREEN_BACKGROUND), COLOR_SCREEN_BACKGROUND, state, callback)
            ConfigChoiceRow(R.string.pref_skin_title, state.skinOptionsState, state.skinSelectionState) { state.skinSelectionState = it }
            ConfigLabeledTextField(R.string.PREF_SCALE_RATIO, state.textValue(TextFieldId.SCALE_RATIO), { state.updateText(TextFieldId.SCALE_RATIO, it) }, R.string.PREF_SCALE_RATIO, state, numeric = true)
            ConfigChoiceRow(R.string.PREF_ORIENTATION, state.orientationOptions, state.orientationSelectionState) { state.orientationSelectionState = it }
            ConfigChoiceRow(R.string.pref_screen_gravity, state.gravityOptions, state.gravitySelectionState) { state.gravitySelectionState = it }
            ConfigLabeledTextField(R.string.pref_screen_padding_title, state.textValue(TextFieldId.PADDING), { state.updateText(TextFieldId.PADDING, it) }, R.string.pref_screen_padding_title, state, numeric = true)
            ConfigChoiceRow(R.string.pref_screen_scale_type, state.scaleTypeOptions, state.scaleTypeSelectionState) { state.scaleTypeSelectionState = it }
            ConfigSwitch(R.string.PREF_FILTER, state.filterState) { state.filterState = it }
            ConfigSwitch(R.string.PREF_IMMEDIATE, state.immediateState) { state.immediateState = it }
            ConfigChoiceRow(R.string.pref_graphics_mode_title, state.graphicsModeOptions, state.graphicsModeSelectionState) {
                state.graphicsModeSelectionState = it
                callback.onGraphicsModeSelected(it)
            }
            when (state.graphicsModeSelectionState) {
                0, 3 -> ConfigSwitch(R.string.parallel_screen_redrawing, state.parallelState) { state.parallelState = it }
                1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    ConfigChoiceRow(
                        R.string.PREF_SHADER_FILTER,
                        state.shaderOptionsState.map { it.toString() },
                        state.shaderSelectionState,
                        modifier = Modifier.weight(1f),
                    ) {
                        state.shaderSelectionState = it
                        callback.onShaderSelected(it)
                    }
                    if (state.shaderTuningAvailableState) {
                        IconButton(onClick = callback::onShaderTune) {
                            Icon(painter = painterResource(R.drawable.ic_baseline_tune_24), contentDescription = stringResource(R.string.shader_tuning), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            ConfigSwitch(R.string.PREF_FORCE_FULLSCREEN, state.forceFullscreenState) { state.forceFullscreenState = it }
            ConfigSwitch(R.string.PREF_SHOW_FPS, state.showFpsState) { state.showFpsState = it }
            ConfigLabeledTextField(R.string.PREF_LIMIT_FPS, state.textValue(TextFieldId.FPS), { state.updateText(TextFieldId.FPS, it) }, R.string.unlimited, state, numeric = true)
        }
    }

    @Composable
    private fun FontSection(state: ConfigUiState, callback: Callback) {
        ConfigCard(R.string.PREF_FONT_OPTIONS) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ConfigTextField(state.textValue(TextFieldId.FONT_SMALL), { state.updateText(TextFieldId.FONT_SMALL, it) }, R.string.PREF_FONT_SMALL, Modifier.weight(1f), numeric = true)
                Text("/", modifier = Modifier.align(Alignment.CenterVertically), fontSize = 18.sp)
                ConfigTextField(state.textValue(TextFieldId.FONT_MEDIUM), { state.updateText(TextFieldId.FONT_MEDIUM, it) }, R.string.PREF_FONT_MEDIUM, Modifier.weight(1f), numeric = true)
                Text("/", modifier = Modifier.align(Alignment.CenterVertically), fontSize = 18.sp)
                ConfigTextField(state.textValue(TextFieldId.FONT_LARGE), { state.updateText(TextFieldId.FONT_LARGE, it) }, R.string.PREF_FONT_LARGE, Modifier.weight(1f), numeric = true)
            }
            ConfigActionButton(onClick = callback::onFontPresets, modifier = Modifier.fillMaxWidth().padding(4.dp)) { Text(stringResource(R.string.SIZE_PRESETS)) }
            ConfigSwitch(R.string.PREF_FONT_SIZE_IN_SP, state.fontInSpState) { state.fontInSpState = it }
            ConfigSwitch(R.string.PREF_FONT_ANTI_ALIASING, state.fontAaState) { state.fontAaState = it }
        }
    }

    @Composable
    private fun InputSection(state: ConfigUiState, callback: Callback) {
        ConfigCard(R.string.pref_input_devices_title) {
            ConfigSwitch(R.string.PREF_TOUCH_INPUT, state.touchInputState) { state.touchInputState = it }
            ConfigChoiceRow(R.string.PREF_LAYOUT, state.layoutOptions, state.layoutSelectionState) { state.layoutSelectionState = it }
            ConfigActionButton(onClick = callback::onKeyMappings, modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 4.dp)) { Text(stringResource(R.string.pref_map_keys)) }
            ConfigSwitch(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS, state.showKeyboardState) {
                state.showKeyboardState = it
                callback.onShowKeyboardChanged(it)
            }
            if (state.showKeyboardState) {
                ConfigChoiceRow(R.string.pref_button_shape_title, state.buttonShapeOptions, state.buttonShapeSelectionState) { state.buttonShapeSelectionState = it }
                ConfigSwitch(R.string.PREF_VK_FEEDBACK, state.vkFeedbackState) { state.vkFeedbackState = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.PREF_VK_ALPHA), modifier = Modifier.weight(0.42f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    Slider(value = state.vkAlphaState.toFloat(), onValueChange = { state.vkAlphaState = it.roundToInt() }, valueRange = 0f..255f, steps = 254, modifier = Modifier.weight(0.58f))
                }
                ConfigSwitch(R.string.PREF_VK_FORCE_OPACITY, state.vkForceOpacityState) { state.vkForceOpacityState = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.PREF_VK_HIDE_DELAY), modifier = Modifier.weight(0.42f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    ConfigTextField(state.textValue(TextFieldId.VK_HIDE), { state.updateText(TextFieldId.VK_HIDE, it) }, R.string.pref_vk_hide_hint, Modifier.weight(0.48f), numeric = true)
                    Text(stringResource(R.string.PREF_UNIT_MS), modifier = Modifier.weight(0.1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                }
                ConfigColorRow(R.string.PREF_VK_FORE, state.colorValue(COLOR_VK_FORE), COLOR_VK_FORE, state, callback)
                ConfigColorRow(R.string.PREF_VK_BACK, state.colorValue(COLOR_VK_BACK), COLOR_VK_BACK, state, callback)
                ConfigColorRow(R.string.PREF_VK_SEL_FORE, state.colorValue(COLOR_VK_SELECTED_FORE), COLOR_VK_SELECTED_FORE, state, callback)
                ConfigColorRow(R.string.PREF_VK_SEL_BACK, state.colorValue(COLOR_VK_SELECTED_BACK), COLOR_VK_SELECTED_BACK, state, callback)
                ConfigColorRow(R.string.PREF_VK_OUTLINE, state.colorValue(COLOR_VK_OUTLINE), COLOR_VK_OUTLINE, state, callback)
            }
        }
    }

    @Composable
    private fun EmulationSection(state: ConfigUiState) {
        ConfigCard(R.string.pref_title_emulation) {
            ConfigSwitch(R.string.pref_skip_resume_call, state.skipResumeState) { state.skipResumeState = it }
        }
    }

    @Composable
    private fun ExperimentalSection(state: ConfigUiState, callback: Callback) {
        ConfigCard(R.string.pref_title_experimental) {
            ConfigChoiceRow(R.string.secure_connection_mode_title, state.secureConnectionOptions, state.secureConnectionSelectionState) {
                state.secureConnectionSelectionState = it
                callback.onSecureConnectionSelected(it)
            }
            Text(stringResource(R.string.secure_connection_mode_summary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 5.dp))
        }
    }

    @Composable
    private fun AudioSection(state: ConfigUiState) {
        ConfigCard(R.string.pref_audio_title) {
            ConfigChoiceRow(R.string.pref_soundbank_title, state.soundBankOptionsState, state.soundBankSelectionState) { state.soundBankSelectionState = it }
        }
    }

    @Composable
    private fun SystemSection(state: ConfigUiState, callback: Callback) {
        ConfigCard(R.string.PREF_SYS_PROPS) {
            ConfigActionButton(onClick = callback::onEncoding, modifier = Modifier.padding(4.dp)) { Text(stringResource(R.string.pref_encoding_title)) }
            ConfigTextField(
                state.systemPropertiesState,
                { state.systemPropertiesState = it },
                R.string.PREF_SYS_PROPS_HINT,
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .verticalScroll(rememberScrollState()),
                singleLine = false,
                textAlign = TextAlign.Start,
            )
        }
    }

    @Composable
    private fun ConfigCard(titleRes: Int, content: @Composable () -> Unit) {
        Surface(modifier = Modifier.fillMaxWidth().padding(5.dp), color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(0.dp), tonalElevation = 2.dp) {
            Column(modifier = Modifier.padding(5.dp)) {
                Text(stringResource(titleRes), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 18.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.height(5.dp))
                content()
            }
        }
    }

    @Composable
    private fun ConfigSwitch(labelRes: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(labelRes), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun ConfigChoiceRow(
        labelRes: Int,
        options: List<String>,
        selected: Int,
        modifier: Modifier = Modifier,
        onSelected: (Int) -> Unit,
    ) {
        Row(modifier = modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(labelRes), modifier = Modifier.weight(0.42f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            ConfigDropdown(options, selected, onSelected, Modifier.weight(0.58f))
        }
    }

    @Composable
    private fun ConfigLabeledTextField(labelRes: Int, value: String, onValueChange: (String) -> Unit, hintRes: Int, state: ConfigUiState, numeric: Boolean) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(labelRes), modifier = Modifier.weight(0.42f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            ConfigTextField(value, onValueChange, hintRes, Modifier.weight(0.58f), state = state, numeric = numeric)
        }
    }

    @Composable
    private fun ConfigColorRow(labelRes: Int, value: String, field: String, state: ConfigUiState, callback: Callback) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            ConfigActionButton(onClick = { callback.onColorPicker(field) }, modifier = Modifier.weight(0.42f).padding(horizontal = 4.dp, vertical = 2.dp)) { Text(stringResource(labelRes)) }
            Row(modifier = Modifier.weight(0.58f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(24.dp)
                        .background(state.colorSwatch(value)),
                )
                ConfigTextField(value, { state.updateColor(field, it) }, R.string.PREF_COLOR_HINT, Modifier.weight(1f), state = state, hex = true)
            }
        }
    }

    @Composable
    private fun ConfigDropdown(options: List<String>, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = modifier) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(options.getOrNull(selected).orEmpty(), maxLines = 1)
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more_24),
                        contentDescription = null,
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelected(index) })
                }
            }
        }
    }

    @Composable
    private fun ConfigActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
        Button(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(3.dp),
            colors = ConfigActionButtonDefaults.colors(),
            content = content,
        )
    }

    @Composable
    private fun ConfigTextField(
        value: String,
        onValueChange: (String) -> Unit,
        hintRes: Int,
        modifier: Modifier = Modifier,
        onFocusLost: (() -> Unit)? = null,
        state: ConfigUiState? = null,
        hex: Boolean = false,
        numeric: Boolean = false,
        singleLine: Boolean = true,
        textAlign: TextAlign = TextAlign.Center,
    ) {
        var hadFocus by remember { mutableStateOf(false) }
        TextField(
            value = value,
            onValueChange = {
                onValueChange(
                    when {
                        hex -> state?.normalizeHex(it).orEmpty()
                        numeric -> it.filter(Char::isDigit)
                        else -> it
                    },
                )
            },
            modifier = modifier.onFocusChanged {
                if (hadFocus && !it.isFocused) onFocusLost?.invoke()
                hadFocus = it.isFocused
            },
            placeholder = { Text(stringResource(hintRes)) },
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    hex -> KeyboardType.Ascii
                    numeric -> KeyboardType.Number
                    else -> KeyboardType.Text
                },
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = textAlign),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
            ),
        )
    }

    @Composable
    internal fun Render(callback: Callback, dialogState: ConfigDialogState? = null) {
        AppComposeTheme {
            ConfigContent(this@ConfigUiState, showExperimental, callback)
            dialogState?.Render()
        }
    }

    @Composable
    internal fun RenderPreview(callback: Callback) {
        ConfigContent(this, showExperimental, callback)
    }
}

/** Component-level color ownership for [ConfigActionButton]. */
private object ConfigActionButtonDefaults {
    @Composable
    fun colors(): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = colorResource(R.color.btn_bg_normal),
        contentColor = Color.White,
    )
}

@Preview(name = "Config screen", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun ConfigScreenPreview() {
    ConfigScreenPreviewContent(darkTheme = false)
}

@Preview(name = "Config screen dark", showBackground = true, widthDp = 420, heightDp = 760, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ConfigScreenDarkPreview() {
    ConfigScreenPreviewContent(darkTheme = true)
}

@Composable
private fun ConfigScreenPreviewContent(darkTheme: Boolean) {
    val context = LocalContext.current
    val callback = remember {
         object : ConfigUiState.Callback {
            override fun onScreenPresets() = Unit
            override fun onSwapSizes() = Unit
            override fun onAddResolution() = Unit
            override fun onFontPresets() = Unit
            override fun onColorPicker(field: String) = Unit
            override fun onKeyMappings() = Unit
            override fun onEncoding() = Unit
            override fun onShaderTune() = Unit
            override fun onGraphicsModeSelected(position: Int) = Unit
            override fun onShaderSelected(position: Int) = Unit
            override fun onSecureConnectionSelected(position: Int) = Unit
            override fun onShowKeyboardChanged(visible: Boolean) = Unit
            override fun onBack() = Unit
            override fun onToolbarAction(actionId: Int) = Unit
        }
    }
    val previewSkinNotSet = stringResource(R.string.pref_skin_not_set)
    val previewAndroidSoundBank = stringResource(R.string.default_label, "Android")
    val previewIdentityFilter = stringResource(R.string.identity_filter)
    val previewShader = remember {
        ShaderInfo("Cartoon", "H3NB").apply {
            set("SettingName1 = Curvature")
            set("SettingDefaultValue1 = 0.25")
            set("SettingMinValue1 = 0")
            set("SettingMaxValue1 = 1")
            set("SettingStep1 = 0.05")
        }
    }
        val view = remember(context) {
            ConfigUiState(context, true).apply {
            setToolbarTitle("Configuration")
            setScreenWidthText("240")
            setScreenHeightText("320")
            setScreenBackgroundText("D0D0D0")
            setScaleRatioText("100")
            setScreenPaddingText("0")
            setFpsLimitText("")
            setFontSmallText("18")
            setFontMediumText("22")
            setFontLargeText("26")
            setVkHideDelayText("")
            setVkBackText("D0D0D0")
            setVkForeText("000080")
            setVkSelectedBackText("000080")
            setVkSelectedForeText("FFFFFF")
            setVkOutlineText("FFFFFF")
            setSystemPropertiesText("microedition.encoding: UTF-8\n")
            setSkinOptions(listOf(previewSkinNotSet))
            setSoundBankOptions(listOf(previewAndroidSoundBank))
            setGraphicsModeSelection(1)
            setShaderOptions(listOf(ShaderInfo(previewIdentityFilter, "woesss"), previewShader))
            setSelectedShaderPosition(1)
            setShaderTuningAvailable(true)
            setShowKeyboardChecked(true)
            setTouchInputChecked(true)
            setFontAaChecked(true)
            setFontInSpChecked(false)
            setVkAlphaProgress(64)
            setSecureConnectionSelection(0)
        }
    }
    AppComposeTheme(darkTheme = darkTheme) {
        view.RenderPreview(callback)
    }
}
