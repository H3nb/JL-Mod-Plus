from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one marker, found {count}")
    return text.replace(old, new, 1)


# Finish the two fail-closed native guards left from the regression audit.
p = Path("app/src/main/cpp/memory/memory_engine.cpp")
text = p.read_text()
text = replace_once(
    text,
    """bool snapshotIdentity(std::span<const uint8_t> bytes, size_t offset,
                      size_t width, uint64_t &hash) noexcept {
    if (offset < kIdentityRadius || offset > bytes.size() ||
        width > bytes.size() - offset) {
""",
    """bool snapshotIdentity(std::span<const uint8_t> bytes, size_t offset,
                      size_t width, uint64_t &hash) noexcept {
    if (width == 0 || offset < kIdentityRadius || offset > bytes.size() ||
        width > bytes.size() - offset) {
""",
    "snapshot zero-width guard",
)
text = replace_once(
    text,
    """    const size_t width = widthOf(type);
    uintptr_t valueEnd = 0;
    uintptr_t contextEnd = 0;
    if (address < kIdentityRadius ||
        !checkedAddressAdd(address, width, valueEnd) ||
""",
    """    const size_t width = widthOf(type);
    uintptr_t valueEnd = 0;
    uintptr_t contextEnd = 0;
    if (width == 0 || address < kIdentityRadius ||
        !checkedAddressAdd(address, width, valueEnd) ||
""",
    "readIdentity zero-width guard",
)
p.write_text(text)


# Move numeric inputs to the state-based TextField API so the native cursor/selection can remain
# visible while the dedicated Memory Editor keypad owns text entry.
p = Path("app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryInputCompose.kt")
text = p.read_text()
text = replace_once(
    text,
    """import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
""",
    """import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
""",
    "state-based input imports",
)
text = replace_once(
    text,
    """import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
""",
    """import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
""",
    "input runtime imports",
)
text = replace_once(
    text,
    """    private fun publish(updated: MemoryInputBinding) {
        binding = updated
        onTextChange?.invoke(updated.value.text)
    }
""",
    """    private fun publish(updated: MemoryInputBinding) {
        val previousText = binding?.value?.text
        binding = updated
        if (previousText != updated.value.text) {
            onTextChange?.invoke(updated.value.text)
        }
    }
""",
    "selection-only callback suppression",
)
old_field = """    val id = remember { Any() }
    DisposableEffect(id) {
        onDispose { session.deactivate(id) }
    }
    LaunchedEffect(spec) {
        if (value.isNotEmpty() && !spec.acceptsPartial(value)) onValueChange(\"\")
    }
    SideEffect { session.sync(id, value, spec, onValueChange) }
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
new_field = """    val id = remember { Any() }
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
        if (value.isNotEmpty() && !spec.acceptsPartial(value)) onValueChange(\"\")
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
"""
text = replace_once(text, old_field, new_field, "state-based MemoryValueInput")
p.write_text(text)


# Make pre-result search/baseline controls use the available viewport instead of expanding past
# short landscape height. Results remain data-first and keep their existing compact strip.
p = Path("app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryEditorCompose.kt")
text = p.read_text()
text = replace_once(
    text,
    """import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
""",
    """import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
""",
    "search scrolling imports",
)
text = replace_once(
    text,
    """    var detailRow by remember { mutableStateOf<MemoryCandidateRow?>(null) }
    var detailAliases by remember { mutableStateOf<List<MemoryCandidateRow>>(emptyList()) }

    LaunchedEffect(state.sessionStage, state.searchMode, state.requestedType, state.searchScope) {
""",
    """    var detailRow by remember { mutableStateOf<MemoryCandidateRow?>(null) }
    var detailAliases by remember { mutableStateOf<List<MemoryCandidateRow>>(emptyList()) }
    val searchScrollState = rememberScrollState()

    LaunchedEffect(state.sessionStage, state.searchMode, state.requestedType, state.searchScope) {
""",
    "search scroll state",
)
text = replace_once(
    text,
    """                SearchWorkspace(
                    state = state,
""",
    """                SearchWorkspace(
                    modifier = Modifier.weight(1f).verticalScroll(searchScrollState),
                    state = state,
""",
    "weighted scrollable search workspace",
)
text = replace_once(
    text,
    """        } else if (state.sessionStage == MemorySessionStage.CANDIDATES) {
            ResultsWorkspace(
                state = state,
                actions = actions,
                onOpen = { group -> detailRow = group.primary; detailAliases = group.aliases },
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth())
        }
""",
    """        } else if (state.sessionStage == MemorySessionStage.CANDIDATES) {
            ResultsWorkspace(
                state = state,
                actions = actions,
                onOpen = { group -> detailRow = group.primary; detailAliases = group.aliases },
                modifier = Modifier.weight(1f),
            )
        }
""",
    "remove competing empty workspace weight",
)
text = replace_once(
    text,
    """private fun SearchWorkspace(
    state: MemoryEditorUiState,
""",
    """private fun SearchWorkspace(
    modifier: Modifier = Modifier,
    state: MemoryEditorUiState,
""",
    "search workspace modifier",
)
text = replace_once(
    text,
    """    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
""",
    """    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
""",
    "apply search workspace modifier",
)
p.write_text(text)


# Add visual regression states for short landscape search and an actually focused keypad field.
p = Path("app/src/screenshotTest/kotlin/ru/playsoftware/j2meloader/memory/MemoryEditorScreenshotTest.kt")
text = p.read_text()
text = replace_once(
    text,
    """package ru.playsoftware.j2meloader.memory

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
""",
    """package ru.playsoftware.j2meloader.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
""",
    "screenshot focus imports",
)
append = """

@PreviewTest
@Preview(name = \"Memory Editor short landscape search\", widthDp = 640, heightDp = 320, showBackground = true)
@Composable
fun MemoryEditorShortLandscapeSearchScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        MemoryEditorScreen(
            state = MemoryEditorUiState(
                bubbleEnabled = true,
                visible = true,
                connected = true,
                supported = true,
                writeSupported = true,
                runtimeToken = 1,
                sessionStage = MemorySessionStage.EMPTY,
            ),
            actions = NoOpMemoryEditorActions,
        )
    }
}

@PreviewTest
@Preview(name = \"Memory keypad landscape\", widthDp = 640, heightDp = 320, showBackground = true)
@Composable
fun MemoryKeypadLandscapeScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        MemoryInputArea(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                MemoryValueInput(
                    value = \"12345\",
                    onValueChange = {},
                    spec = MemoryInputSpec.forType(MemoryEngineContract.TYPE_INT),
                    label = stringResource(R.string.memory_editor_search_hint),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
        }
    }
}
"""
if "fun MemoryKeypadLandscapeScreenshot()" in text:
    raise SystemExit("screenshot coverage already present")
text = text.rstrip() + append + "\n"
p.write_text(text)


# The project keeps instrumentation sources available, but PR CI no longer needs to assemble the
# AndroidTest APK on every change.
p = Path(".github/workflows/android.yml")
text = p.read_text()
text = replace_once(
    text,
    """          :app:validateEmulatorDebugScreenshotTest
          :app:assembleEmulatorDebug
          :app:assembleEmulatorDebugAndroidTest
""",
    """          :app:validateEmulatorDebugScreenshotTest
          :app:assembleEmulatorDebug
""",
    "remove unnecessary AndroidTest assembly",
)
p.write_text(text)

print("Applied adaptive Memory Editor input polish")
