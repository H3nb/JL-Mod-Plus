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

package ru.playsoftware.j2meloader.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R

internal enum class MemoryInputKind {
    SIGNED_INTEGER,
    UNSIGNED_INTEGER,
    FLOATING,
    POSITIVE_INTEGER,
}

internal data class MemoryInputSpec(
    val kind: MemoryInputKind,
    val minLong: Long? = null,
    val maxLong: Long? = null,
    val floatingBits: Int = 64,
    val maxChars: Int = 48,
) {
    val signed: Boolean get() = kind == MemoryInputKind.SIGNED_INTEGER || kind == MemoryInputKind.FLOATING
    val decimal: Boolean get() = kind == MemoryInputKind.FLOATING
    val exponent: Boolean get() = kind == MemoryInputKind.FLOATING

    companion object {
        fun forType(type: Int): MemoryInputSpec = when (type) {
            MemoryEngineContract.TYPE_BYTE -> MemoryInputSpec(
                MemoryInputKind.SIGNED_INTEGER,
                Byte.MIN_VALUE.toLong(),
                Byte.MAX_VALUE.toLong(),
                maxChars = 4,
            )
            MemoryEngineContract.TYPE_SHORT -> MemoryInputSpec(
                MemoryInputKind.SIGNED_INTEGER,
                Short.MIN_VALUE.toLong(),
                Short.MAX_VALUE.toLong(),
                maxChars = 6,
            )
            MemoryEngineContract.TYPE_CHAR -> MemoryInputSpec(
                MemoryInputKind.UNSIGNED_INTEGER,
                0L,
                0xffffL,
                maxChars = 5,
            )
            MemoryEngineContract.TYPE_INT -> MemoryInputSpec(
                MemoryInputKind.SIGNED_INTEGER,
                Int.MIN_VALUE.toLong(),
                Int.MAX_VALUE.toLong(),
                maxChars = 11,
            )
            MemoryEngineContract.TYPE_LONG -> MemoryInputSpec(
                MemoryInputKind.SIGNED_INTEGER,
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                maxChars = 20,
            )
            MemoryEngineContract.TYPE_FLOAT -> MemoryInputSpec(
                MemoryInputKind.FLOATING,
                floatingBits = 32,
            )
            MemoryEngineContract.TYPE_DOUBLE,
            MemoryEngineContract.TYPE_AUTO -> MemoryInputSpec(
                MemoryInputKind.FLOATING,
                floatingBits = 64,
            )
            else -> MemoryInputSpec(MemoryInputKind.FLOATING)
        }

        fun positiveInteger(min: Long = 1, max: Long? = null): MemoryInputSpec = MemoryInputSpec(
            MemoryInputKind.POSITIVE_INTEGER,
            minLong = min,
            maxLong = max,
            maxChars = 12,
        )
    }
}

internal fun MemoryInputSpec.acceptsPartial(text: String): Boolean {
    if (text.length > maxChars) return false
    if (text.isEmpty()) return true
    return when (kind) {
        MemoryInputKind.SIGNED_INTEGER -> text.indices.all { index ->
            val char = text[index]
            char.isDigit() || (char == '-' && index == 0)
        }
        MemoryInputKind.UNSIGNED_INTEGER,
        MemoryInputKind.POSITIVE_INTEGER -> text.all(Char::isDigit)
        MemoryInputKind.FLOATING -> acceptsPartialFloating(text)
    }
}

private fun acceptsPartialFloating(text: String): Boolean {
    var seenDot = false
    var seenExponent = false
    var mantissaDigits = 0
    for (index in text.indices) {
        when (val char = text[index]) {
            in '0'..'9' -> if (!seenExponent) mantissaDigits++
            '-' -> if (index != 0 && text[index - 1] != 'e' && text[index - 1] != 'E') return false
            '+' -> if (index == 0 || (text[index - 1] != 'e' && text[index - 1] != 'E')) return false
            '.' -> {
                if (seenDot || seenExponent) return false
                seenDot = true
            }
            'e', 'E' -> {
                if (seenExponent || mantissaDigits == 0) return false
                seenExponent = true
            }
            else -> return false
        }
    }
    return true
}

internal fun MemoryInputSpec.isComplete(text: String): Boolean {
    if (text.isBlank() || !acceptsPartial(text)) return false
    return when (kind) {
        MemoryInputKind.SIGNED_INTEGER,
        MemoryInputKind.UNSIGNED_INTEGER,
        MemoryInputKind.POSITIVE_INTEGER -> {
            val value = text.toLongOrNull() ?: return false
            (minLong == null || value >= minLong) && (maxLong == null || value <= maxLong)
        }
        MemoryInputKind.FLOATING -> if (floatingBits == 32) {
            text.toFloatOrNull()?.isFinite() == true
        } else {
            text.toDoubleOrNull()?.isFinite() == true
        }
    }
}

internal fun memoryInputCompleteForTypes(value: String, types: Collection<Int>): Boolean =
    types.isNotEmpty() && types.all { MemoryInputSpec.forType(it).isComplete(value) }

internal object MemoryInputEditing {
    fun insert(value: TextFieldValue, token: String, spec: MemoryInputSpec): TextFieldValue {
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
        val candidate = value.text.replaceRange(start, end, token)
        if (!spec.acceptsPartial(candidate)) return value
        return TextFieldValue(candidate, TextRange(start + token.length))
    }

    fun backspace(value: TextFieldValue): TextFieldValue {
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
        if (start != end) return TextFieldValue(value.text.removeRange(start, end), TextRange(start))
        if (start == 0) return value
        return TextFieldValue(value.text.removeRange(start - 1, start), TextRange(start - 1))
    }

    fun move(value: TextFieldValue, delta: Int): TextFieldValue {
        val current = if (delta < 0) minOf(value.selection.start, value.selection.end)
        else maxOf(value.selection.start, value.selection.end)
        return value.copy(selection = TextRange((current + delta).coerceIn(0, value.text.length)))
    }

    fun toggleSign(value: TextFieldValue, spec: MemoryInputSpec): TextFieldValue {
        if (!spec.signed) return value
        val text = value.text
        val cursor = value.selection.end.coerceIn(0, text.length)
        val exponent = text.substring(0, cursor).lastIndexOfAny(charArrayOf('e', 'E'))
        val signIndex = if (exponent >= 0) exponent + 1 else 0
        val hasMinus = text.getOrNull(signIndex) == '-'
        val hasPlus = text.getOrNull(signIndex) == '+'
        val candidate = when {
            hasMinus || hasPlus -> text.removeRange(signIndex, signIndex + 1)
            else -> text.substring(0, signIndex) + "-" + text.substring(signIndex)
        }
        if (!spec.acceptsPartial(candidate)) return value
        val delta = if (hasMinus || hasPlus) -1 else 1
        return TextFieldValue(candidate, TextRange((cursor + delta).coerceIn(0, candidate.length)))
    }
}

private data class MemoryInputBinding(
    val id: Any,
    val value: TextFieldValue,
    val spec: MemoryInputSpec,
)

internal class MemoryInputSession {
    private var binding by mutableStateOf<MemoryInputBinding?>(null)
    private var onTextChange: ((String) -> Unit)? = null
    val active: Boolean get() = binding != null
    val activeSpec: MemoryInputSpec? get() = binding?.spec

    fun valueFor(id: Any, external: String): TextFieldValue =
        binding?.takeIf { it.id === id }?.value ?: TextFieldValue(external, TextRange(external.length))

    fun activate(id: Any, external: String, spec: MemoryInputSpec, callback: (String) -> Unit) {
        onTextChange = callback
        val current = binding
        if (current?.id === id) {
            if (current.spec != spec) binding = current.copy(spec = spec)
        } else {
            binding = MemoryInputBinding(id, TextFieldValue(external, TextRange(external.length)), spec)
        }
    }

    fun sync(id: Any, external: String, spec: MemoryInputSpec, callback: (String) -> Unit) {
        val current = binding ?: return
        if (current.id !== id) return
        onTextChange = callback
        if (current.value.text != external || current.spec != spec) {
            val syncedValue = if (current.value.text == external) current.value else {
                TextFieldValue(external, TextRange(external.length))
            }
            binding = current.copy(value = syncedValue, spec = spec)
        }
    }

    fun acceptFieldValue(id: Any, value: TextFieldValue) {
        val current = binding ?: return
        if (current.id !== id || !current.spec.acceptsPartial(value.text)) return
        publish(current.copy(value = value))
    }

    fun insert(token: String) = transform { MemoryInputEditing.insert(it.value, token, it.spec) }
    fun backspace() = transform { MemoryInputEditing.backspace(it.value) }
    fun move(delta: Int) = transform { MemoryInputEditing.move(it.value, delta) }
    fun toggleSign() = transform { MemoryInputEditing.toggleSign(it.value, it.spec) }
    fun clear() = transform { TextFieldValue("", TextRange(0)) }

    fun deactivate(id: Any) {
        if (binding?.id === id) hide()
    }

    fun hide() {
        binding = null
        onTextChange = null
    }

    private fun transform(change: (MemoryInputBinding) -> TextFieldValue) {
        val current = binding ?: return
        publish(current.copy(value = change(current)))
    }

    private fun publish(updated: MemoryInputBinding) {
        val previousText = binding?.value?.text
        binding = updated
        if (previousText != updated.value.text) {
            onTextChange?.invoke(updated.value.text)
        }
    }
}

private val LocalMemoryInputSession = compositionLocalOf<MemoryInputSession?> { null }

@Composable
internal fun MemoryInputArea(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    sideDockInLandscape: Boolean = true,
    content: @Composable () -> Unit,
) {
    val session = remember { MemoryInputSession() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(active) {
        if (!active) {
            session.hide()
            focusManager.clearFocus(force = true)
        }
    }
    CompositionLocalProvider(LocalMemoryInputSession provides session) {
        BoxWithConstraints(modifier = modifier) {
            val sideDock = sideDockInLandscape &&
                maxWidth >= 600.dp &&
                maxWidth > maxHeight
            if (sideDock) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) { content() }
                    if (session.active) {
                        MemoryKeypad(
                            session = session,
                            modifier = Modifier.width(252.dp),
                            onHide = {
                                session.hide()
                                focusManager.clearFocus(force = true)
                            },
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                    if (session.active) {
                        MemoryKeypad(
                            session = session,
                            modifier = Modifier.fillMaxWidth(),
                            onHide = {
                                session.hide()
                                focusManager.clearFocus(force = true)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MemoryValueInput(
    value: String,
    onValueChange: (String) -> Unit,
    spec: MemoryInputSpec,
    label: String,
    modifier: Modifier = Modifier,
) {
    val session = LocalMemoryInputSession.current
    if (session == null) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (spec.acceptsPartial(it)) onValueChange(it) },
            modifier = modifier,
            singleLine = true,
            label = { Text(label) },
        )
        return
    }
    val id = remember { Any() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val fieldState = rememberTextFieldState(value)
    val sessionValue = session.valueFor(id, value)
    val inputTransformation = remember(spec) {
        InputTransformation.byValue { current, proposed ->
            if (spec.acceptsPartial(proposed.toString())) proposed else current
        }
    }
    DisposableEffect(id) {
        onDispose { session.deactivate(id) }
    }
    LaunchedEffect(spec) {
        if (value.isNotEmpty() && !spec.acceptsPartial(value)) onValueChange("")
    }
    LaunchedEffect(sessionValue) {
        val fieldText = fieldState.text.toString()
        if (fieldText != sessionValue.text || fieldState.selection != sessionValue.selection) {
            fieldState.edit {
                if (fieldText != sessionValue.text) {
                    replace(0, length, sessionValue.text)
                }
                selection = sessionValue.selection
            }
        }
    }
    LaunchedEffect(fieldState, id) {
        snapshotFlow { TextFieldValue(fieldState.text.toString(), fieldState.selection) }
            .collect { session.acceptFieldValue(id, it) }
    }
    SideEffect { session.sync(id, value, spec, onValueChange) }
    OutlinedTextField(
        state = fieldState,
        inputTransformation = inputTransformation,
        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false),
        lineLimits = TextFieldLineLimits.SingleLine,
        modifier = modifier
            .pointerInput(keyboardController) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    waitForUpOrCancellation()
                    keyboardController?.hide()
                }
            }
            .onFocusChanged { focus ->
                if (focus.isFocused) {
                    session.activate(id, value, spec, onValueChange)
                    keyboardController?.hide()
                }
            },
        label = { Text(label) },
    )
}

@Composable
private fun MemoryKeypad(
    session: MemoryInputSession,
    modifier: Modifier,
    onHide: () -> Unit,
) {
    val spec = session.activeSpec ?: return
    Surface(
        modifier = modifier.padding(top = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeypadRow {
                KeypadButton("1") { session.insert("1") }
                KeypadButton("2") { session.insert("2") }
                KeypadButton("3") { session.insert("3") }
                KeypadButton("⌫") { session.backspace() }
            }
            KeypadRow {
                KeypadButton("4") { session.insert("4") }
                KeypadButton("5") { session.insert("5") }
                KeypadButton("6") { session.insert("6") }
                KeypadButton("←") { session.move(-1) }
            }
            KeypadRow {
                KeypadButton("7") { session.insert("7") }
                KeypadButton("8") { session.insert("8") }
                KeypadButton("9") { session.insert("9") }
                KeypadButton("→") { session.move(1) }
            }
            KeypadRow {
                KeypadButton(if (spec.signed) "±" else "C") {
                    if (spec.signed) session.toggleSign() else session.clear()
                }
                KeypadButton("0") { session.insert("0") }
                KeypadButton(if (spec.decimal) "." else "C") {
                    if (spec.decimal) session.insert(".") else session.clear()
                }
                KeypadButton(stringResource(R.string.memory_editor_keypad_hide), onHide)
            }
            if (spec.exponent) {
                KeypadRow {
                    KeypadButton("E") { session.insert("E") }
                    KeypadButton(stringResource(R.string.memory_editor_keypad_clear)) { session.clear() }
                }
            }
        }
    }
}

@Composable
private fun KeypadRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun RowScope.KeypadButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, maxLines = 1)
    }
}
