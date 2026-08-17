from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one marker, found {count}: {old[:120]!r}")
    return text.replace(old, new, 1)


# Library/App Actions ---------------------------------------------------------
library_path = Path("app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt")
library = library_path.read_text()

for old, new, label in [
    ("icon = null,\n                        onDismiss = onDismiss,\n                        action = onRename,",
     "icon = R.drawable.ic_edit,\n                        onDismiss = onDismiss,\n                        action = onRename,",
     "rename icon"),
    ("icon = R.drawable.ic_options,\n                        onDismiss = onDismiss,\n                        action = onSettings,",
     "icon = R.drawable.ic_settings,\n                        onDismiss = onDismiss,\n                        action = onSettings,",
     "settings icon"),
    ("icon = R.drawable.ic_swap,\n                            onDismiss = onDismiss,\n                            action = onReinstall,",
     "icon = R.drawable.ic_restart_alt,\n                            onDismiss = onDismiss,\n                            action = onReinstall,",
     "reinstall icon"),
    ("icon = R.drawable.ic_delete_report,\n                        destructive = true,",
     "icon = R.drawable.ic_delete,\n                        destructive = true,",
     "delete icon"),
]:
    library = replace_once(library, old, new, label)

library = replace_once(
    library,
    """    val context = LocalContext.current
    val title: String
    val message: AnnotatedString
    val maxMessageHeight = if (dialog == LibraryInfoDialog.Licenses) 380.dp else 300.dp
""",
    """    val context = LocalContext.current
    val title: String
    val message: AnnotatedString
    val layout = libraryDialogLayout()
    val maxMessageHeight = libraryDialogListHeight(
        maxHeight = if (dialog == LibraryInfoDialog.Licenses) 520 else 420,
    )
""",
    "library info adaptive state",
)
library = replace_once(
    library,
    """    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
""",
    """    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = { Text(title) },
""",
    "library info adaptive dialog",
)
library_path.write_text(library)


# Runtime Back/menu ----------------------------------------------------------
runtime_path = Path("app/src/main/java/javax/microedition/shell/RuntimeMenuCompose.kt")
runtime = runtime_path.read_text()
runtime = replace_once(
    runtime,
    "import androidx.compose.ui.text.input.KeyboardType\n",
    "import androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.text.input.KeyboardType\n",
    "runtime FontWeight import",
)

runtime = replace_once(
    runtime,
    """@Composable
internal fun RuntimeLimitFpsDialog(
""",
    """private data class RuntimeMenuDialogLayout(
    val modifier: Modifier,
    val properties: DialogProperties,
)

@Composable
private fun runtimeMenuDialogLayout(): RuntimeMenuDialogLayout {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    return RuntimeMenuDialogLayout(
        modifier = if (landscape) {
            Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 760.dp)
        } else {
            Modifier.widthIn(max = 560.dp)
        },
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
    )
}

@Composable
private fun runtimeMenuDialogContentHeight(maxHeight: Int = 420) =
    LocalConfiguration.current.screenHeightDp
        .minus(220)
        .coerceAtLeast(120)
        .coerceAtMost(maxHeight)
        .dp

@Composable
internal fun RuntimeLimitFpsDialog(
""",
    "runtime dialog helper",
)

runtime = replace_once(
    runtime,
    """    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
""",
    """    var value by remember { mutableStateOf("") }
    val layout = runtimeMenuDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
""",
    "runtime fps adaptive dialog",
)

runtime = replace_once(
    runtime,
    """    var virtualKeyboardPage by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    AlertDialog(
        modifier = if (landscape) {
            Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 760.dp)
        } else {
            Modifier.widthIn(max = 560.dp)
        },
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
""",
    """    var virtualKeyboardPage by remember { mutableStateOf(false) }
    val layout = runtimeMenuDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
""",
    "runtime menu shared adaptive layout",
)
runtime = replace_once(
    runtime,
    "LazyColumn(modifier = Modifier.heightIn(max = if (landscape) 220.dp else 420.dp)) {",
    "LazyColumn(modifier = Modifier.heightIn(max = runtimeMenuDialogContentHeight())) {",
    "runtime menu dynamic height",
)

# Main runtime menu action icons.
runtime = replace_once(
    runtime,
    "RuntimeActionItem(R.string.exit, onDismiss, actions::onExit)",
    "RuntimeActionItem(R.string.exit, onDismiss, actions::onExit, leadingIcon = R.drawable.ic_logout)",
    "runtime exit icon",
)
runtime = replace_once(
    runtime,
    "RuntimeActionItem(R.string.save_log, onDismiss, actions::onSaveLog)",
    "RuntimeActionItem(R.string.save_log, onDismiss, actions::onSaveLog, leadingIcon = R.drawable.ic_save)",
    "runtime save icon",
)
runtime = replace_once(
    runtime,
    """        RuntimeToggleItem(
            label = R.string.action_lock_orientation,
            checked = state.orientationLocked,
""",
    """        RuntimeToggleItem(
            label = R.string.action_lock_orientation,
            checked = state.orientationLocked,
            leadingIcon = R.drawable.ic_screen_lock_rotation,
""",
    "runtime rotation icon",
)
runtime = replace_once(
    runtime,
    """                RuntimeMenuItem(
                    label = R.string.action_keyboard_ime,
                    onClick = {
""",
    """                RuntimeMenuItem(
                    label = R.string.action_keyboard_ime,
                    leadingIcon = R.drawable.ic_action_keyboard,
                    onClick = {
""",
    "runtime ime icon",
)
runtime = replace_once(
    runtime,
    """                RuntimeMenuItem(
                    label = R.string.take_screenshot,
                    onClick = {
""",
    """                RuntimeMenuItem(
                    label = R.string.take_screenshot,
                    leadingIcon = R.drawable.ic_action_screenshot,
                    onClick = {
""",
    "runtime screenshot icon",
)
runtime = replace_once(
    runtime,
    "RuntimeActionItem(R.string.PREF_LIMIT_FPS, onDismiss, actions::onLimitFps)",
    "RuntimeActionItem(R.string.PREF_LIMIT_FPS, onDismiss, actions::onLimitFps, leadingIcon = R.drawable.ic_speed)",
    "runtime fps icon",
)
runtime = replace_once(
    runtime,
    """                RuntimeMenuItem(
                    label = R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS,
                    onClick = onOpenVirtualKeyboardPage,
""",
    """                RuntimeMenuItem(
                    label = R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS,
                    leadingIcon = R.drawable.ic_action_keyboard,
                    onClick = onOpenVirtualKeyboardPage,
""",
    "runtime vk icon",
)

# A few submenu actions with clear, existing semantic symbols.
runtime = replace_once(
    runtime,
    "RuntimeActionItem(R.string.layout_edit_mode, onDismiss, actions::onEditVirtualKeyboardLayout)",
    "RuntimeActionItem(R.string.layout_edit_mode, onDismiss, actions::onEditVirtualKeyboardLayout, leadingIcon = R.drawable.ic_edit)",
    "runtime edit-layout icon",
)
runtime = replace_once(
    runtime,
    "RuntimeActionItem(R.string.layout_switch, onDismiss, actions::onSwitchVirtualKeyboardLayout)",
    "RuntimeActionItem(R.string.layout_switch, onDismiss, actions::onSwitchVirtualKeyboardLayout, leadingIcon = R.drawable.ic_restart_alt)",
    "runtime switch-layout icon",
)

runtime = replace_once(
    runtime,
    """private fun RuntimeActionItem(
    label: Int,
    onDismiss: () -> Unit,
    action: () -> Unit,
) {
    RuntimeMenuItem(label = label) {
""",
    """private fun RuntimeActionItem(
    label: Int,
    onDismiss: () -> Unit,
    action: () -> Unit,
    leadingIcon: Int? = null,
) {
    RuntimeMenuItem(label = label, leadingIcon = leadingIcon) {
""",
    "runtime action icon plumbing",
)
runtime = replace_once(
    runtime,
    """private fun RuntimeToggleItem(
    label: Int,
    checked: Boolean,
    onClick: () -> Unit,
) {
""",
    """private fun RuntimeToggleItem(
    label: Int,
    checked: Boolean,
    leadingIcon: Int? = null,
    onClick: () -> Unit,
) {
""",
    "runtime toggle icon parameter",
)
runtime = replace_once(
    runtime,
    """        headlineContent = { Text(stringResource(label)) },
        trailingContent = {
""",
    """        headlineContent = {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        leadingContent = leadingIcon?.let { icon ->
            {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
""",
    "runtime toggle hierarchy",
)
runtime = replace_once(
    runtime,
    """        headlineContent = { Text(stringResource(label)) },
        leadingContent = leadingIcon?.let { icon ->
""",
    """        headlineContent = {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        leadingContent = leadingIcon?.let { icon ->
""",
    "runtime item hierarchy",
)
runtime = replace_once(
    runtime,
    """                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                )
""",
    """                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
""",
    "runtime item icon tint",
)

runtime_path.write_text(runtime)
