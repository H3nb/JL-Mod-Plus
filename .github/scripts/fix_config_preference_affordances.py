from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


components = 'app/src/main/java/ru/playsoftware/j2meloader/config/ConfigPreferenceComponents.kt'

replace_once(
    components,
    'import androidx.compose.foundation.background\n',
    'import androidx.compose.foundation.background\nimport androidx.compose.foundation.border\n',
)
replace_once(
    components,
    'import androidx.compose.material3.MaterialTheme\n',
    'import androidx.compose.material3.Icon\nimport androidx.compose.material3.MaterialTheme\n',
)
replace_once(
    components,
    'import androidx.compose.ui.semantics.Role\n',
    'import androidx.compose.ui.res.painterResource\nimport androidx.compose.ui.semantics.Role\n',
)
replace_once(
    components,
    'import androidx.compose.ui.unit.dp\n',
    'import androidx.compose.ui.unit.dp\nimport ru.playsoftware.j2meloader.R\n',
)

replace_once(
    components,
    '''    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
''',
    '''    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 1.dp),
        )
''',
)

replace_once(
    components,
    '''            if (enabled) {
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
''',
    '',
)

replace_once(
    components,
    '''            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(configColor(value)),
            )
''',
    '''            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(configColor(value))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            )
''',
)
replace_once(
    components,
    '''            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
''',
    '',
)

old_disclosure = '''    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandedChange(!expanded) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = if (expanded) "⌃" else "⌄",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
'''
new_disclosure = '''    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button) { onExpandedChange(!expanded) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(
                if (expanded) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down,
            ),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
'''
replace_once(components, old_disclosure, new_disclosure)

replace_once(
    components,
    '''private fun configColor(value: String): Color {
    return try {
        Color((0xFF000000L or value.trim().removePrefix("#").toLong(16)).toULong())
    } catch (_: Throwable) {
        Color.Black
    }
}
''',
    '''private fun configColor(value: String): Color {
    val rgb = value.trim().removePrefix("#").toLongOrNull(16)?.and(0xFFFFFF) ?: return Color.Black
    return Color((0xFF000000L or rgb).toInt())
}
''',
)

drawable_dir = Path('app/src/main/res/drawable')
drawable_dir.mkdir(parents=True, exist_ok=True)
(drawable_dir / 'ic_keyboard_arrow_down.xml').write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- Material Symbols: keyboard_arrow_down (Google, Apache-2.0). -->
    <path
        android:fillColor="#FF000000"
        android:pathData="M12,15.4 L6,9.4 L7.4,8 L12,12.6 L16.6,8 L18,9.4 L12,15.4 Z" />
</vector>
''')
(drawable_dir / 'ic_keyboard_arrow_up.xml').write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- Material Symbols: keyboard_arrow_up (Google, Apache-2.0). -->
    <path
        android:fillColor="#FF000000"
        android:pathData="M12,10.8 L7.4,15.4 L6,14 L12,8 L18,14 L16.6,15.4 L12,10.8 Z" />
</vector>
''')

test = 'app/src/androidTest/java/ru/playsoftware/j2meloader/config/ConfigComposeTest.kt'
marker = '''    @Test
    fun sliderValuesCommitFromTheirDialogs() {
'''
regression = '''    @Test
    fun advancedControlsRenderColorPreferencesWithoutCrashing() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents(), initialDestination = ConfigDestination.Controls)
            }
        }

        composeRule.onNodeWithText("Advanced settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Foreground").assertExists()
        composeRule.onNodeWithText("#000080").assertExists()
        composeRule.onNodeWithText("Background").assertExists()
        composeRule.onNodeWithText("Outline").assertExists()
    }

'''
p = Path(test)
text = p.read_text()
if marker not in text:
    raise SystemExit('android test insertion marker missing')
p.write_text(text.replace(marker, regression + marker, 1))

screenshot = 'app/src/screenshotTest/kotlin/ru/playsoftware/j2meloader/config/ConfigScreenshotTest.kt'
replace_once(
    screenshot,
    'import androidx.compose.runtime.Composable\n',
    'import androidx.compose.foundation.layout.Column\nimport androidx.compose.runtime.Composable\n',
)
screenshot_marker = '''@PreviewTest
@Preview(name = "Config media empty", widthDp = 360, heightDp = 800, showBackground = true)
'''
screenshot_preview = '''@PreviewTest
@Preview(name = "Config preference components", widthDp = 360, heightDp = 520, showBackground = true)
@Composable
fun ConfigPreferenceComponentsScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        Column {
            ConfigSection(title = "Virtual keyboard") {
                ConfigColorPreference(
                    title = "Foreground",
                    description = "Color used for virtual-key labels.",
                    value = "000080",
                    onClick = {},
                )
                ConfigDisclosurePreference(
                    title = "Advanced settings",
                    description = "Less common controls for compatibility and appearance.",
                    expanded = false,
                    onExpandedChange = {},
                )
                ConfigDisclosurePreference(
                    title = "Advanced settings",
                    description = "Less common controls for compatibility and appearance.",
                    expanded = true,
                    onExpandedChange = {},
                )
            }
        }
    }
}

'''
p = Path(screenshot)
text = p.read_text()
if screenshot_marker not in text:
    raise SystemExit('screenshot insertion marker missing')
p.write_text(text.replace(screenshot_marker, screenshot_preview + screenshot_marker, 1))
