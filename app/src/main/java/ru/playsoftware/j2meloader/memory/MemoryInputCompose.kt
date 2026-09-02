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

import android.content.res.Configuration
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
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

    fun isActive(id: Any): Boolean = binding?.id === id

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
    alwaysShowKeypad: Boolean = false,
    keypadSpec: MemoryInputSpec? = null,
    content: @Composable () -> Unit,
) {
    val session = remember { MemoryInputSession() }
    val focusManager = LocalFocusManager.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(active) {
        if (!active) {
            session.hide()
            focusManager.clearFocus(force = true)
        }
    }
    CompositionLocalProvider(LocalMemoryInputSession provides session) {
        BoxWithConstraints(modifier = modifier) {
            // Use the actual device orientation here. AlertDialog bodies may be measured inside a
            // vertical scroll container with an unbounded height, where maxWidth > maxHeight is
            // not a reliable landscape test.
            val sideDock = sideDockInLandscape && landscape && maxWidth >= 480.dp
            if (sideDock) {
                val rowModifier = if (alwaysShowKeypad) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
                val contentModifier = if (alwaysShowKeypad) {
                    Modifier.weight(1f).fillMaxWidth()
                } else {
                    Modifier.weight(1f).fillMaxSize()
                }
                Row(
                    modifier = rowModifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = contentModifier) { content() }
                    if (alwaysShowKeypad || session.active) {
                        val displayedSpec = session.activeSpec ?: keypadSpec
                        if (displayedSpec != null) {
                            MemoryKeypad(
                                session = session,
                                spec = displayedSpec,
                                allowHide = !alwaysShowKeypad,
                                modifier = Modifier.width(280.dp),
                                onHide = {
                                    session.hide()
                                    focusManager.clearFocus(force = true)
                                },
                            )
                        }
                    }
                }
            } else if (alwaysShowKeypad) {
                // Dialogs have bounded height. Do not give the field area weight(1f): the keypad's
                // intrinsic height can otherwise squeeze the actual value field down to zero.
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxWidth()) { content() }
                    val displayedSpec = session.activeSpec ?: keypadSpec
                    if (displayedSpec != null) {
                        MemoryKeypad(
                            session = session,
                            spec = displayedSpec,
                            allowHide = false,
                            modifier = Modifier.fillMaxWidth(),
                            onHide = {},
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                    if (session.active) {
                        val displayedSpec = session.activeSpec ?: keypadSpec
                        if (displayedSpec != null) {
                            MemoryKeypad(
                                session = session,
                                spec = displayedSpec,
                                allowHide = true,
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
}

@Composable
internal fun MemoryValueInput(
    value: String,
    onValueChange: (String) -> Unit,
    spec: MemoryInputSpec,
    label: String,
    modifier: Modifier = Modifier,
    activateOnStart: Boolean = false,
) {
    val session = requireNotNull(LocalMemoryInputSession.current) {
        "MemoryValueInput must be hosted by MemoryInputArea"
    }
    val id = remember { Any() }
    val focusRequester = remember { FocusRequester() }
    val sessionValue = session.valueFor(id, value)
    DisposableEffect(id) {
        onDispose { session.deactivate(id) }
    }
    LaunchedEffect(spec) {
        if (value.isNotEmpty() && !spec.acceptsPartial(value)) onValueChange("")
    }
    LaunchedEffect(activateOnStart, spec) {
        if (activateOnStart) {
            session.activate(id, value, spec, onValueChange)
            focusRequester.requestFocus()
        }
    }
    SideEffect { session.sync(id, value, spec, onValueChange) }
    val cursorTransformation = if (session.isActive(id)) {
        MemoryCursorVisualTransformation(
            cursor = sessionValue.selection.end,
            cursorColor = MaterialTheme.colorScheme.primary,
        )
    } else {
        VisualTransformation.None
    }
    Box(
        modifier = modifier
            .semantics { contentDescription = label }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val nativeEvent = event.nativeKeyEvent
                if (nativeEvent.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                val keyCode = nativeEvent.keyCode
                val token = memoryHardwareInputToken(keyCode)
                when {
                    token != null -> {
                        session.activate(id, sessionValue.text, spec, onValueChange)
                        session.insert(token)
                        true
                    }
                    keyCode == AndroidKeyEvent.KEYCODE_DEL -> {
                        session.activate(id, sessionValue.text, spec, onValueChange)
                        session.backspace()
                        true
                    }
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        session.activate(id, sessionValue.text, spec, onValueChange)
                        session.move(-1)
                        true
                    }
                    keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        session.activate(id, sessionValue.text, spec, onValueChange)
                        session.move(1)
                        true
                    }
                    else -> false
                }
            }
            .clickable(role = Role.Button) {
                focusRequester.requestFocus()
                session.activate(id, sessionValue.text, spec, onValueChange)
            },
    ) {
        // A disabled text field has no focus or input connection, so it cannot summon Android's IME.
        // The enclosing click target activates the Memory Editor's own keypad instead.
        OutlinedTextField(
            value = sessionValue.text,
            onValueChange = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = cursorTransformation,
            label = { Text(label) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

internal class MemoryCursorVisualTransformation(
    private val cursor: Int,
    private val cursorColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val position = cursor.coerceIn(0, text.length)
        val transformed = buildAnnotatedString {
            append(text.text.substring(0, position))
            withStyle(
                SpanStyle(
                    color = cursorColor,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append("▏")
            }
            append(text.text.substring(position))
        }
        return TransformedText(
            text = transformed,
            offsetMapping = object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    if (offset > position) offset + 1 else offset

                override fun transformedToOriginal(offset: Int): Int =
                    if (offset > position) (offset - 1).coerceAtLeast(0) else offset
            },
        )
    }
}

internal fun memoryHardwareInputToken(keyCode: Int): String? = when (keyCode) {
    AndroidKeyEvent.KEYCODE_0,
    AndroidKeyEvent.KEYCODE_NUMPAD_0 -> "0"
    AndroidKeyEvent.KEYCODE_1,
    AndroidKeyEvent.KEYCODE_NUMPAD_1 -> "1"
    AndroidKeyEvent.KEYCODE_2,
    AndroidKeyEvent.KEYCODE_NUMPAD_2 -> "2"
    AndroidKeyEvent.KEYCODE_3,
    AndroidKeyEvent.KEYCODE_NUMPAD_3 -> "3"
    AndroidKeyEvent.KEYCODE_4,
    AndroidKeyEvent.KEYCODE_NUMPAD_4 -> "4"
    AndroidKeyEvent.KEYCODE_5,
    AndroidKeyEvent.KEYCODE_NUMPAD_5 -> "5"
    AndroidKeyEvent.KEYCODE_6,
    AndroidKeyEvent.KEYCODE_NUMPAD_6 -> "6"
    AndroidKeyEvent.KEYCODE_7,
    AndroidKeyEvent.KEYCODE_NUMPAD_7 -> "7"
    AndroidKeyEvent.KEYCODE_8,
    AndroidKeyEvent.KEYCODE_NUMPAD_8 -> "8"
    AndroidKeyEvent.KEYCODE_9,
    AndroidKeyEvent.KEYCODE_NUMPAD_9 -> "9"
    AndroidKeyEvent.KEYCODE_MINUS,
    AndroidKeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "-"
    AndroidKeyEvent.KEYCODE_PLUS,
    AndroidKeyEvent.KEYCODE_NUMPAD_ADD -> "+"
    AndroidKeyEvent.KEYCODE_PERIOD,
    AndroidKeyEvent.KEYCODE_NUMPAD_DOT -> "."
    AndroidKeyEvent.KEYCODE_E -> "e"
    else -> null
}

@Composable
private fun MemoryKeypad(
    session: MemoryInputSession,
    spec: MemoryInputSpec,
    allowHide: Boolean,
    modifier: Modifier,
    onHide: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
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
            if (landscape) {
                KeypadRow {
                    KeypadButton("1") { session.insert("1") }
                    KeypadButton("2") { session.insert("2") }
                    KeypadButton("3") { session.insert("3") }
                    KeypadButton("4") { session.insert("4") }
                    KeypadButton("5") { session.insert("5") }
                }
                KeypadRow {
                    KeypadButton("6") { session.insert("6") }
                    KeypadButton("7") { session.insert("7") }
                    KeypadButton("8") { session.insert("8") }
                    KeypadButton("9") { session.insert("9") }
                    KeypadButton("0") { session.insert("0") }
                }
                KeypadRow {
                    KeypadButton("±", enabled = spec.signed) { session.toggleSign() }
                    KeypadButton(".", enabled = spec.decimal) { session.insert(".") }
                    KeypadButton("E", enabled = spec.exponent) { session.insert("E") }
                    KeypadButton(";", enabled = false) {}
                    KeypadButton(":", enabled = false) {}
                }
                KeypadRow {
                    KeypadButton("←") { session.move(-1) }
                    KeypadButton("→") { session.move(1) }
                    KeypadButton("⌫") { session.backspace() }
                    KeypadButton(
                        stringResource(R.string.memory_editor_keypad_clear),
                        weight = if (allowHide) 1f else 2f,
                    ) { session.clear() }
                    if (allowHide) {
                        KeypadButton(stringResource(R.string.memory_editor_keypad_hide), onClick = onHide)
                    }
                }
            } else {
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
                    KeypadButton("±", enabled = spec.signed) { session.toggleSign() }
                    KeypadButton("0") { session.insert("0") }
                    KeypadButton(".", enabled = spec.decimal) { session.insert(".") }
                    KeypadButton("E", enabled = spec.exponent) { session.insert("E") }
                }
                KeypadRow {
                    // These positions stay aligned with the search keypad, but edit/inspector writes
                    // intentionally accept one replacement value, not a group-search expression.
                    KeypadButton(";", enabled = false) {}
                    KeypadButton(":", enabled = false) {}
                    KeypadButton(
                        stringResource(R.string.memory_editor_keypad_clear),
                        weight = if (allowHide) 1f else 2f,
                    ) { session.clear() }
                    if (allowHide) {
                        KeypadButton(stringResource(R.string.memory_editor_keypad_hide), onClick = onHide)
                    }
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
private fun RowScope.KeypadButton(
    label: String,
    enabled: Boolean = true,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(weight).sizeIn(minHeight = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, maxLines = 1)
    }
}
