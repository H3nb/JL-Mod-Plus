from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 marker, found {count}")
    return text.replace(old, new, 1)


p = Path("app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryInputCompose.kt")
text = p.read_text()
text = text.replace("import android.content.res.Configuration\n", "")
text = replace_once(
    text,
    "import androidx.compose.foundation.layout.RowScope\nimport androidx.compose.foundation.layout.fillMaxWidth\n",
    "import androidx.compose.foundation.layout.RowScope\nimport androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.defaultMinSize\nimport androidx.compose.foundation.layout.fillMaxWidth\n",
    "input layout imports",
)
text = replace_once(
    text,
    "import androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.OutlinedTextField\n",
    "import androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.OutlinedTextField\nimport androidx.compose.material3.adaptive.currentWindowAdaptiveInfo\n",
    "adaptive import",
)
text = text.replace("import androidx.compose.ui.platform.LocalConfiguration\n", "")
text = replace_once(text, "import androidx.compose.ui.Modifier\n", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.graphics.Color\n", "color import")
text = replace_once(
    text,
    "import androidx.compose.ui.text.TextRange\nimport androidx.compose.ui.text.input.TextFieldValue\n",
    "import androidx.compose.ui.text.AnnotatedString\nimport androidx.compose.ui.text.SpanStyle\nimport androidx.compose.ui.text.TextRange\nimport androidx.compose.ui.text.buildAnnotatedString\nimport androidx.compose.ui.text.input.OffsetMapping\nimport androidx.compose.ui.text.input.TextFieldValue\nimport androidx.compose.ui.text.input.TransformedText\nimport androidx.compose.ui.text.input.VisualTransformation\nimport androidx.compose.ui.text.withStyle\n",
    "caret imports",
)
text = replace_once(
    text,
    "import androidx.compose.ui.unit.dp\nimport ru.playsoftware.j2meloader.R\n",
    "import androidx.compose.ui.unit.dp\nimport androidx.window.core.layout.WindowSizeClass\nimport ru.playsoftware.j2meloader.R\n",
    "window size import",
)

kind = """internal enum class MemoryInputKind {
    SIGNED_INTEGER,
    UNSIGNED_INTEGER,
    FLOATING,
    POSITIVE_INTEGER,
}
"""
kind_new = kind + """
internal enum class MemoryKeypadDock {
    BOTTOM,
    SIDE,
}

internal fun memoryKeypadDock(
    widthAtLeastMedium: Boolean,
    widthAtLeastExpanded: Boolean,
    heightAtLeastMedium: Boolean,
    allowSideDock: Boolean = true,
): MemoryKeypadDock = if (
    allowSideDock && widthAtLeastMedium && (!heightAtLeastMedium || widthAtLeastExpanded)
) MemoryKeypadDock.SIDE else MemoryKeypadDock.BOTTOM
"""
text = replace_once(text, kind, kind_new, "keypad dock helper")

marker = """private data class MemoryInputBinding(
    val id: Any,
    val value: TextFieldValue,
    val spec: MemoryInputSpec,
)
"""
caret = """internal class MemoryCaretVisualTransformation(
    private val cursor: Int,
    private val caretColor: Color = Color.Unspecified,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val caret = cursor.coerceIn(0, text.length)
        val transformed = buildAnnotatedString {
            append(text.subSequence(0, caret))
            withStyle(SpanStyle(color = caretColor)) { append(CARET) }
            append(text.subSequence(caret, text.length))
        }
        return TransformedText(transformed, MemoryCaretOffsetMapping(caret))
    }

    private companion object {
        const val CARET = "▏"
    }
}

internal class MemoryCaretOffsetMapping(private val caret: Int) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int =
        if (offset <= caret) offset else offset + 1

    override fun transformedToOriginal(offset: Int): Int = when {
        offset <= caret -> offset
        offset == caret + 1 -> caret
        else -> offset - 1
    }
}

"""
text = replace_once(text, marker, caret + marker, "caret transformation")
text = replace_once(
    text,
    "    val active: Boolean get() = binding != null\n    val activeSpec: MemoryInputSpec? get() = binding?.spec\n\n    fun valueFor",
    "    val active: Boolean get() = binding != null\n    val activeSpec: MemoryInputSpec? get() = binding?.spec\n\n    fun isActive(id: Any): Boolean = binding?.id === id\n\n    fun valueFor",
    "session active helper",
)

old_area = """    val landscape = sideDockInLandscape &&
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    CompositionLocalProvider(LocalMemoryInputSession provides session) {
        if (landscape) {
            Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) { content() }
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
            Column(modifier = modifier.fillMaxWidth()) {
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
"""
new_area = """    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val widthAtLeastMedium = windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
    )
    val widthAtLeastExpanded = windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    )
    val heightAtLeastMedium = windowSizeClass.isHeightAtLeastBreakpoint(
        WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
    )
    val dock = memoryKeypadDock(
        widthAtLeastMedium = widthAtLeastMedium,
        widthAtLeastExpanded = widthAtLeastExpanded,
        heightAtLeastMedium = heightAtLeastMedium,
        allowSideDock = sideDockInLandscape,
    )
    CompositionLocalProvider(LocalMemoryInputSession provides session) {
        if (dock == MemoryKeypadDock.SIDE) {
            Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) { content() }
                if (session.active) {
                    MemoryKeypad(
                        session = session,
                        modifier = Modifier.width(288.dp),
                        compact = true,
                        onHide = {
                            session.hide()
                            focusManager.clearFocus(force = true)
                        },
                    )
                }
            }
        } else {
            Column(modifier = modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                if (session.active) {
                    MemoryKeypad(
                        session = session,
                        modifier = Modifier.fillMaxWidth(),
                        compact = false,
                        onHide = {
                            session.hide()
                            focusManager.clearFocus(force = true)
                        },
                    )
                }
            }
        }
    }
"""
text = replace_once(text, old_area, new_area, "adaptive input area")

old_field = """    SideEffect { session.sync(id, value, spec, onValueChange) }
    OutlinedTextField(
        value = session.valueFor(id, value),
        onValueChange = { session.acceptFieldValue(id, it) },
        modifier = modifier.onFocusChanged { focus ->
            if (focus.isFocused) session.activate(id, value, spec, onValueChange)
        },
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
    )
"""
new_field = """    SideEffect { session.sync(id, value, spec, onValueChange) }
    val fieldValue = session.valueFor(id, value)
    val visualTransformation = if (session.isActive(id)) {
        MemoryCaretVisualTransformation(fieldValue.selection.end, MaterialTheme.colorScheme.primary)
    } else {
        VisualTransformation.None
    }
    OutlinedTextField(
        value = fieldValue,
        onValueChange = { session.acceptFieldValue(id, it) },
        modifier = modifier.onFocusChanged { focus ->
            if (focus.isFocused) session.activate(id, value, spec, onValueChange)
        },
        readOnly = true,
        singleLine = true,
        visualTransformation = visualTransformation,
        label = { Text(label) },
    )
"""
text = replace_once(text, old_field, new_field, "visible caret field")
text = replace_once(
    text,
    """private fun MemoryKeypad(
    session: MemoryInputSession,
    modifier: Modifier,
    onHide: () -> Unit,
) {""",
    """internal fun MemoryKeypad(
    session: MemoryInputSession,
    modifier: Modifier,
    compact: Boolean = false,
    onHide: () -> Unit,
) {""",
    "keypad signature",
)

old_keys = """        Column(
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
"""
new_keys = """        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (compact) {
                KeypadRow {
                    KeypadButton("1") { session.insert("1") }
                    KeypadButton("2") { session.insert("2") }
                    KeypadButton("3") { session.insert("3") }
                    KeypadButton("⌫") { session.backspace() }
                    KeypadButton(stringResource(R.string.memory_editor_keypad_clear)) { session.clear() }
                }
                KeypadRow {
                    KeypadButton("4") { session.insert("4") }
                    KeypadButton("5") { session.insert("5") }
                    KeypadButton("6") { session.insert("6") }
                    KeypadButton("←") { session.move(-1) }
                    KeypadButton("→") { session.move(1) }
                }
                KeypadRow {
                    KeypadButton("7") { session.insert("7") }
                    KeypadButton("8") { session.insert("8") }
                    KeypadButton("9") { session.insert("9") }
                    if (spec.signed) KeypadButton("±") { session.toggleSign() } else KeypadSpacer()
                    if (spec.decimal) KeypadButton(".") { session.insert(".") } else KeypadSpacer()
                }
                KeypadRow {
                    KeypadButton("0") { session.insert("0") }
                    if (spec.exponent) KeypadButton("E") { session.insert("E") } else KeypadSpacer()
                    KeypadButton(
                        stringResource(R.string.memory_editor_keypad_hide),
                        onClick = onHide,
                        weight = 3f,
                    )
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
"""
text = replace_once(text, old_keys, new_keys, "compact keypad rows")
old_button = """@Composable
private fun RowScope.KeypadButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, maxLines = 1)
    }
}
"""
new_button = """@Composable
private fun RowScope.KeypadButton(
    label: String,
    onClick: () -> Unit,
    weight: Float = 1f,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .weight(weight)
            .defaultMinSize(minWidth = 0.dp)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun RowScope.KeypadSpacer() {
    Spacer(modifier = Modifier.weight(1f).sizeIn(minWidth = 48.dp, minHeight = 48.dp))
}
"""
text = replace_once(text, old_button, new_button, "keypad touch targets")
p.write_text(text)

p = Path("app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryEditorCompose.kt")
text = p.read_text()
text = replace_once(
    text,
    "import androidx.compose.foundation.clickable\n",
    "import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.verticalScroll\n",
    "workspace scroll imports",
)
old_call = """                    advanced = advanced,
                    onAdvanced = { advanced = !advanced },
                    actions = actions,
                )
"""
new_call = """                    advanced = advanced,
                    onAdvanced = { advanced = !advanced },
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
"""
text = replace_once(text, old_call, new_call, "weighted search workspace call")
text = replace_once(
    text,
    """        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth())
        }

        if (state.selected.isNotEmpty()) {""",
    """        }

        if (state.selected.isNotEmpty()) {""",
    "remove duplicate empty weight slot",
)
text = replace_once(
    text,
    """    actions: MemoryEditorActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {""",
    """    actions: MemoryEditorActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {""",
    "scrollable search workspace",
)
text = text.replace("MemoryInputArea(sideDockInLandscape = false)", "MemoryInputArea()")
p.write_text(text)

p = Path("app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryInspectorCompose.kt")
text = p.read_text().replace("MemoryInputArea(sideDockInLandscape = false)", "MemoryInputArea()")
p.write_text(text)

p = Path("app/src/test/java/ru/playsoftware/j2meloader/memory/MemoryInputComposeTest.kt")
text = p.read_text()
text = replace_once(
    text,
    "import androidx.compose.ui.text.TextRange\n",
    "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.text.AnnotatedString\nimport androidx.compose.ui.text.TextRange\n",
    "input test imports",
)
extra_tests = """
    @Test
    fun caretTransformationMakesArrowMovementVisibleWithoutChangingRawText() {
        val transformed = MemoryCaretVisualTransformation(2, Color.Red)
            .filter(AnnotatedString("123"))
        assertEquals("12▏3", transformed.text.text)
        assertEquals(2, transformed.offsetMapping.originalToTransformed(2))
        assertEquals(4, transformed.offsetMapping.originalToTransformed(3))
        assertEquals(2, transformed.offsetMapping.transformedToOriginal(3))
    }

    @Test
    fun keypadDockUsesWideCompactWindowInsteadOfOrientation() {
        assertEquals(
            MemoryKeypadDock.SIDE,
            memoryKeypadDock(true, false, false),
        )
        assertEquals(
            MemoryKeypadDock.BOTTOM,
            memoryKeypadDock(true, false, true),
        )
        assertEquals(
            MemoryKeypadDock.SIDE,
            memoryKeypadDock(true, true, true),
        )
        assertEquals(
            MemoryKeypadDock.BOTTOM,
            memoryKeypadDock(true, true, false, allowSideDock = false),
        )
    }
"""
text = replace_once(text, "\n}", extra_tests + "\n}", "append adaptive input tests")
p.write_text(text)

p = Path("app/src/screenshotTest/kotlin/ru/playsoftware/j2meloader/memory/MemoryEditorScreenshotTest.kt")
text = p.read_text()
text = replace_once(
    text,
    "import androidx.compose.runtime.Composable\n",
    "import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.Row\nimport androidx.compose.foundation.layout.fillMaxSize\nimport androidx.compose.foundation.layout.padding\nimport androidx.compose.runtime.Composable\nimport androidx.compose.runtime.remember\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.OutlinedTextField\nimport androidx.compose.material3.Text\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.unit.dp\n",
    "screenshot imports",
)
preview = """

@PreviewTest
@Preview(name = "Memory Editor landscape search", widthDp = 720, heightDp = 360, showBackground = true)
@Composable
fun MemoryEditorLandscapeSearchScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        MemoryEditorScreen(
            state = MemoryEditorUiState(
                bubbleEnabled = true,
                visible = true,
                connected = true,
                supported = true,
                writeSupported = true,
                runtimeToken = 1,
            ),
            actions = NoOpMemoryEditorActions,
        )
    }
}

@PreviewTest
@Preview(name = "Memory Editor landscape keypad", widthDp = 720, heightDp = 360, showBackground = true)
@Composable
fun MemoryEditorLandscapeKeypadScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        val inputId = remember { Any() }
        val spec = remember { MemoryInputSpec.forType(MemoryEngineContract.TYPE_DOUBLE) }
        val session = remember(inputId) {
            MemoryInputSession().apply {
                activate(inputId, "125.75", spec) { }
                move(-2)
            }
        }
        val field = session.valueFor(inputId, "125.75")
        Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Memory input", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = field,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    visualTransformation = MemoryCaretVisualTransformation(
                        field.selection.end,
                        MaterialTheme.colorScheme.primary,
                    ),
                    label = { Text("Value") },
                )
            }
            MemoryKeypad(
                session = session,
                modifier = Modifier.weight(1f),
                compact = true,
                onHide = {},
            )
        }
    }
}
"""
text += preview
p.write_text(text)
