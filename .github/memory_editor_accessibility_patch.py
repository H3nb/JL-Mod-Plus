from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    p.write_text(text.replace(old, new, 1))


compose = 'app/src/main/java/io/github/h3nb/jlmodplus/memory/MemoryEditorRuntimeCompose.kt'
p = Path(compose)
text = p.read_text()
replace_once(compose,
    'import androidx.compose.ui.semantics.contentDescription\nimport androidx.compose.ui.semantics.onClick\n',
    'import androidx.compose.ui.semantics.LiveRegionMode\nimport androidx.compose.ui.semantics.contentDescription\nimport androidx.compose.ui.semantics.liveRegion\nimport androidx.compose.ui.semantics.onClick\n')
text = p.read_text()
old = '            modifier = Modifier.weight(1f),\n'
# The first three weighted modifiers in RuntimeMemoryTabs belong to the three FilterChips.
start = text.index('private fun RuntimeMemoryTabs(')
end = text.index('\n@Composable\nprivate fun RuntimeOperationStrip', start)
section = text[start:end]
if section.count(old) != 3:
    raise SystemExit(f'FilterChip modifier match count={section.count(old)}')
section = section.replace(old, '            modifier = Modifier.weight(1f).heightIn(min = 48.dp),\n')
text = text[:start] + section + text[end:]
p.write_text(text)
replace_once(compose,
    '''            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
''',
    '''            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
''')
replace_once(compose,
    '        modifier = Modifier.weight(weight).sizeIn(minHeight = if (landscape) 40.dp else 42.dp),\n',
    '        modifier = Modifier.weight(weight).sizeIn(minHeight = 48.dp),\n')
# Remove now-unused landscape local in RuntimeKeypadButton.
p = Path(compose)
text = p.read_text()
replace_once(compose,
    '''    val landscape = availableWindowWidthDp() > availableWindowHeightDp()
    OutlinedButton(
''',
    '''    OutlinedButton(
''')
# Pager navigation must also expose at least a 48dp touch target.
replace_once(compose,
    '''        TextButton(onClick = actions::previousPage, enabled = state.pageOffset > 0) {
            Text("‹")
        }
''',
    '''        TextButton(
            onClick = actions::previousPage,
            enabled = state.pageOffset > 0,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Text("‹")
        }
''')
replace_once(compose,
    '''        TextButton(
            onClick = actions::nextPage,
            enabled = state.pageOffset.toLong() + MemoryEditorComposeController.PAGE_SIZE < state.resultCount,
        ) {
''',
    '''        TextButton(
            onClick = actions::nextPage,
            enabled = state.pageOffset.toLong() + MemoryEditorComposeController.PAGE_SIZE < state.resultCount,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
''')

controller = 'app/src/main/java/io/github/h3nb/jlmodplus/memory/MemoryEditorController.kt'
replace_once(controller,
    '''import android.content.ClipData
import android.content.ClipboardManager
''',
    '''import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
''')
replace_once(controller,
    '''import android.os.IBinder
import android.os.RemoteException
''',
    '''import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.os.RemoteException
''')
replace_once(controller,
    '''        if (rows.isEmpty()) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText("Memory Editor", rows.joinToString("\\n")),
        )
''',
    '''        if (rows.isEmpty()) return
        val clip = ClipData.newPlainText("Memory Editor", rows.joinToString("\\n"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
''')
