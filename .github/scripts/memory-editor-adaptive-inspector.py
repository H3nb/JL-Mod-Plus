from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one marker, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return updated


# 1) Make keypad docking depend on actual measured space, not Configuration.orientation.
p = Path("app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryInputCompose.kt")
text = p.read_text()
text = text.replace("import android.content.res.Configuration\n", "")
text = replace_once(
    text,
    "import androidx.compose.foundation.layout.Box\n",
    "import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.BoxWithConstraints\n",
    "BoxWithConstraints import",
)
text = replace_once(
    text,
    "import androidx.compose.foundation.layout.fillMaxWidth\n",
    "import androidx.compose.foundation.layout.fillMaxSize\nimport androidx.compose.foundation.layout.fillMaxWidth\n",
    "fillMaxSize import",
)
text = text.replace("import androidx.compose.ui.platform.LocalConfiguration\n", "")
old = '''    val landscape = sideDockInLandscape &&
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
'''
new = '''    CompositionLocalProvider(LocalMemoryInputSession provides session) {
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
'''
text = replace_once(text, old, new, "adaptive MemoryInputArea")
p.write_text(text)


# 2) Stabilize Material3 Adaptive at 1.3.0 and add canonical layout/navigation artifacts.
p = Path("gradle/libs.versions.toml")
text = p.read_text()
text = replace_once(
    text,
    'material3-adaptive = "1.3.0-alpha01"',
    'material3-adaptive = "1.3.0"',
    "stable adaptive version",
)
text = replace_once(
    text,
    'androidx-material3-adaptive = { module = "androidx.compose.material3.adaptive:adaptive", version.ref = "material3-adaptive" }\n',
    'androidx-material3-adaptive = { module = "androidx.compose.material3.adaptive:adaptive", version.ref = "material3-adaptive" }\n'
    'androidx-material3-adaptive-layout = { module = "androidx.compose.material3.adaptive:adaptive-layout", version.ref = "material3-adaptive" }\n'
    'androidx-material3-adaptive-navigation = { module = "androidx.compose.material3.adaptive:adaptive-navigation", version.ref = "material3-adaptive" }\n',
    "adaptive artifact aliases",
)
p.write_text(text)

p = Path("app/build.gradle.kts")
text = p.read_text()
text = replace_once(
    text,
    "    implementation(libs.androidx.material3.adaptive)\n",
    "    implementation(libs.androidx.material3.adaptive)\n"
    "    implementation(libs.androidx.material3.adaptive.layout)\n"
    "    implementation(libs.androidx.material3.adaptive.navigation)\n",
    "adaptive dependencies",
)
p.write_text(text)


# 3) Add small localized label for the contextual Inspector controls pane.
for path, marker, value in [
    (
        "app/src/main/res/values/memory_editor_strings.xml",
        '    <string name="memory_editor_inspector">Memory Inspector</string>\n',
        "Inspector controls",
    ),
    (
        "app/src/main/res/values-in/memory_editor_strings.xml",
        '    <string name="memory_editor_inspector">Inspektor Memori</string>\n',
        "Kontrol inspektor",
    ),
]:
    p = Path(path)
    text = p.read_text()
    text = replace_once(
        text,
        marker,
        marker + f'    <string name="memory_editor_inspector_controls">{value}</string>\n',
        f"inspector controls string {path}",
    )
    p.write_text(text)


# 4) Convert the full-screen-ish Inspector dialog into a canonical adaptive supporting-pane workspace.
p = Path("app/src/main/java/ru/playsoftware/j2meloader/memory/MemoryInspectorCompose.kt")
text = p.read_text()
text = replace_once(
    text,
    "import androidx.compose.material3.AlertDialog\n",
    "import androidx.compose.material3.AlertDialog\n"
    "import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi\n"
    "import androidx.compose.material3.adaptive.layout.AnimatedPane\n"
    "import androidx.compose.material3.adaptive.layout.PaneAdaptedValue\n"
    "import androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldRole\n"
    "import androidx.compose.material3.adaptive.navigation.NavigableSupportingPaneScaffold\n"
    "import androidx.compose.material3.adaptive.navigation.rememberSupportingPaneScaffoldNavigator\n",
    "adaptive Inspector imports",
)
text = replace_once(
    text,
    "import androidx.compose.runtime.remember\n",
    "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\n",
    "coroutine scope import",
)
text = replace_once(
    text,
    "import ru.playsoftware.j2meloader.R\n",
    "import kotlinx.coroutines.launch\nimport ru.playsoftware.j2meloader.R\n",
    "coroutines launch import",
)

pattern = r'''@Composable
private fun MemoryInspectorDialog\(
    snapshot: MemoryInspectorSnapshot,
    onDismiss: \(\) -> Unit,
    onRefresh: \(Int\) -> Unit,
    onNearby: \(\) -> Unit,
\) \{.*?
\}


@Composable
private fun InspectorCellRow'''
replacement = '''@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun MemoryInspectorDialog(
    snapshot: MemoryInspectorSnapshot,
    onDismiss: () -> Unit,
    onRefresh: (Int) -> Unit,
    onNearby: () -> Unit,
) {
    var viewType by remember(snapshot.candidateId, snapshot.anchorAddress) {
        mutableIntStateOf(
            snapshot.type.takeIf(MemoryEngineContract::isCandidateType)
                ?: MemoryEngineContract.TYPE_INT,
        )
    }
    var radius by remember(snapshot.candidateId) {
        mutableIntStateOf(MemoryEngineContract.DEFAULT_INSPECT_RADIUS)
    }
    val cells = remember(snapshot, viewType) { buildInspectorCells(snapshot, viewType) }
    val navigator = rememberSupportingPaneScaffoldNavigator<Any>()
    val scope = rememberCoroutineScope()
    val supportingHidden =
        navigator.scaffoldValue[SupportingPaneScaffoldRole.Supporting] == PaneAdaptedValue.Hidden

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            NavigableSupportingPaneScaffold(
                navigator = navigator,
                modifier = Modifier.fillMaxSize(),
                mainPane = {
                    AnimatedPane {
                        InspectorMainPane(
                            snapshot = snapshot,
                            cells = cells,
                            supportingHidden = supportingHidden,
                            onOpenControls = {
                                scope.launch {
                                    navigator.navigateTo(SupportingPaneScaffoldRole.Supporting)
                                }
                            },
                            onDismiss = onDismiss,
                        )
                    }
                },
                supportingPane = {
                    AnimatedPane {
                        InspectorControlsPane(
                            snapshot = snapshot,
                            viewType = viewType,
                            onViewType = { viewType = it },
                            radius = radius,
                            onRadius = { radius = it },
                            onRefresh = { onRefresh(radius) },
                            onNearby = onNearby,
                            onDismiss = onDismiss,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun InspectorMainPane(
    snapshot: MemoryInspectorSnapshot,
    cells: List<MemoryInspectorCell>,
    supportingHidden: Boolean,
    onOpenControls: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    snapshot.label.ifBlank { stringResource(R.string.memory_editor_inspector) },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "0x${snapshot.anchorAddress.toULong().toString(16).uppercase()}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (supportingHidden) {
                TextButton(onClick = onOpenControls) {
                    Text(stringResource(R.string.memory_editor_inspector_controls))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.memory_editor_done))
            }
        }
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.memory_editor_relative_offset),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(0.20f),
            )
            Text(
                stringResource(R.string.memory_editor_address),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(0.42f),
            )
            Text(
                stringResource(R.string.memory_editor_current_value),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(0.38f),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(cells, key = { it.address }) { cell ->
                InspectorCellRow(cell = cell)
            }
        }
    }
}

@Composable
private fun InspectorControlsPane(
    snapshot: MemoryInspectorSnapshot,
    viewType: Int,
    onViewType: (Int) -> Unit,
    radius: Int,
    onRadius: (Int) -> Unit,
    onRefresh: () -> Unit,
    onNearby: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.memory_editor_inspector_controls),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "0x${snapshot.anchorAddress.toULong().toString(16).uppercase()}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Stage3ChoiceMenu(
                    value = viewType,
                    values = STAGE3_VIEW_TYPES,
                    label = ::stage3TypeName,
                    onChange = onViewType,
                )
                Stage3ChoiceMenu(
                    value = radius,
                    values = INSPECT_RADIUS_PRESETS,
                    label = { "±$it B" },
                    onChange = onRadius,
                )
            }
            Text(
                stringResource(R.string.memory_editor_inspector_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.memory_editor_refresh_snapshot))
            }
            TextButton(onClick = onNearby) {
                Text(stringResource(R.string.memory_editor_search_nearby))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.memory_editor_done))
            }
        }
    }
}


@Composable
private fun InspectorCellRow'''
text = regex_once(text, pattern, replacement, "adaptive Inspector dialog")
p.write_text(text)


# 5) Screenshot coverage: retain short-landscape search, remove the false keypad-focus preview,
# and add compact + wide Inspector regressions.
p = Path("app/src/screenshotTest/kotlin/ru/playsoftware/j2meloader/memory/MemoryEditorScreenshotTest.kt")
text = p.read_text()
for line in [
    "import androidx.compose.foundation.layout.Column\n",
    "import androidx.compose.foundation.layout.fillMaxSize\n",
    "import androidx.compose.foundation.layout.fillMaxWidth\n",
    "import androidx.compose.foundation.layout.padding\n",
    "import androidx.compose.runtime.LaunchedEffect\n",
    "import androidx.compose.runtime.remember\n",
    "import androidx.compose.ui.Modifier\n",
    "import androidx.compose.ui.focus.FocusRequester\n",
    "import androidx.compose.ui.focus.focusRequester\n",
    "import androidx.compose.ui.res.stringResource\n",
    "import androidx.compose.ui.unit.dp\n",
    "import ru.playsoftware.j2meloader.R\n",
]:
    text = text.replace(line, "")
text = regex_once(
    text,
    r'''\n@PreviewTest
@Preview\(name = "Memory keypad landscape".*?\nfun MemoryKeypadLandscapeScreenshot\(\) \{.*?\n\}
''',
    "\n",
    "remove misleading keypad screenshot",
)
append = r'''

private val previewInspectorSnapshot = MemoryInspectorSnapshot(
    candidateId = 1,
    type = MemoryEngineContract.TYPE_INT,
    label = "HP",
    startAddress = 0x21B9980,
    anchorAddress = 0x21B99C0,
    bytes = ByteArray(128) { index -> (index * 3 + 7).toByte() },
)

@PreviewTest
@Preview(name = "Memory Inspector compact", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun MemoryInspectorCompactScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        MemoryInspectorDialog(
            snapshot = previewInspectorSnapshot,
            onDismiss = {},
            onRefresh = {},
            onNearby = {},
        )
    }
}

@PreviewTest
@Preview(name = "Memory Inspector wide", widthDp = 840, heightDp = 480, showBackground = true)
@Composable
fun MemoryInspectorWideScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        MemoryInspectorDialog(
            snapshot = previewInspectorSnapshot,
            onDismiss = {},
            onRefresh = {},
            onNearby = {},
        )
    }
}
'''
if "fun MemoryInspectorCompactScreenshot()" in text:
    raise SystemExit("Inspector screenshot coverage already present")
text = text.rstrip() + append + "\n"
p.write_text(text)

print("Applied adaptive keypad and Inspector workspace pass")
