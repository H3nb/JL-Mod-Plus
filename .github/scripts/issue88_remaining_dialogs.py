from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one marker, found {count}: {old[:120]!r}")
    return text.replace(old, new, 1)


# Main host recovery / storage dialogs ---------------------------------------
path = Path("app/src/main/java/ru/playsoftware/j2meloader/MainActivityComposeBridge.kt")
text = path.read_text()
text = replace_once(text,
    "package ru.playsoftware.j2meloader\n\nimport androidx.compose.material3.AlertDialog",
    "package ru.playsoftware.j2meloader\n\nimport android.content.res.Configuration\nimport androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.heightIn\nimport androidx.compose.foundation.layout.widthIn\nimport androidx.compose.foundation.verticalScroll\nimport androidx.compose.material3.AlertDialog",
    "main imports foundation")
text = replace_once(text,
    "import androidx.compose.ui.platform.ComposeView\nimport androidx.compose.ui.platform.ViewCompositionStrategy",
    "import androidx.compose.ui.platform.ComposeView\nimport androidx.compose.ui.platform.LocalConfiguration\nimport androidx.compose.ui.platform.ViewCompositionStrategy",
    "main LocalConfiguration import")
text = replace_once(text,
    "import androidx.compose.ui.res.stringResource\n",
    "import androidx.compose.ui.res.stringResource\nimport androidx.compose.ui.unit.dp\nimport androidx.compose.ui.window.DialogProperties\n",
    "main dialog imports")
text = replace_once(text,
    """@Composable
private fun MainHostDialogs(
""",
    """private data class MainHostDialogLayout(
    val modifier: androidx.compose.ui.Modifier,
    val properties: DialogProperties,
)

@Composable
private fun mainHostDialogLayout(): MainHostDialogLayout {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    return MainHostDialogLayout(
        modifier = if (landscape) {
            androidx.compose.ui.Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 760.dp)
        } else {
            androidx.compose.ui.Modifier.widthIn(max = 560.dp)
        },
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
    )
}

@Composable
private fun MainHostDialogText(message: String) {
    val maxHeight = LocalConfiguration.current.screenHeightDp
        .minus(220)
        .coerceAtLeast(120)
        .coerceAtMost(420)
        .dp
    Text(
        text = message,
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun MainHostDialogs(
""",
    "main dialog helpers")
# Add shared adaptive attrs to the three inline AlertDialogs.
text = text.replace(
    """        is MainHostDialog.DirectoryFailure -> AlertDialog(
            onDismissRequest = {},""",
    """        is MainHostDialog.DirectoryFailure -> mainHostDialogLayout().let { layout -> AlertDialog(
            modifier = layout.modifier,
            properties = layout.properties,
            onDismissRequest = {},""", 1)
text = text.replace(
    """            text = { Text(dialog.message) },""",
    """            text = { MainHostDialogText(dialog.message) },""", 1)
text = text.replace(
    """            },
        )
        is MainHostDialog.DirectoryMissing -> AlertDialog(""",
    """            },
        ) }
        is MainHostDialog.DirectoryMissing -> mainHostDialogLayout().let { layout -> AlertDialog(""", 1)
text = text.replace(
    """        is MainHostDialog.DirectoryMissing -> mainHostDialogLayout().let { layout -> AlertDialog(
            onDismissRequest = {},""",
    """        is MainHostDialog.DirectoryMissing -> mainHostDialogLayout().let { layout -> AlertDialog(
            modifier = layout.modifier,
            properties = layout.properties,
            onDismissRequest = {},""", 1)
text = text.replace("text = { Text(dialog.message) },", "text = { MainHostDialogText(dialog.message) },", 1)
text = text.replace(
    """            },
        )
        MainHostDialog.PermissionFailure -> AlertDialog(""",
    """            },
        ) }
        MainHostDialog.PermissionFailure -> mainHostDialogLayout().let { layout -> AlertDialog(""", 1)
text = text.replace(
    """        MainHostDialog.PermissionFailure -> mainHostDialogLayout().let { layout -> AlertDialog(
            onDismissRequest = {},""",
    """        MainHostDialog.PermissionFailure -> mainHostDialogLayout().let { layout -> AlertDialog(
            modifier = layout.modifier,
            properties = layout.properties,
            onDismissRequest = {},""", 1)
text = text.replace(
    """            text = { Text(stringResource(R.string.permission_request_failed)) },""",
    """            text = { MainHostDialogText(stringResource(R.string.permission_request_failed)) },""", 1)
text = text.replace(
    """            },
        )
        null -> Unit""",
    """            },
        ) }
        null -> Unit""", 1)
# Recovery dialog is a separate composable.
text = replace_once(text,
    """    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = { Text(message) },""",
    """    val layout = mainHostDialogLayout()
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = {},
        title = { Text(title) },
        text = { MainHostDialogText(message) },""",
    "recovery dialog adaptive")
path.write_text(text)


# File picker Create Folder ---------------------------------------------------
path = Path("app/src/main/java/ru/playsoftware/j2meloader/filepicker/FilePickerCompose.kt")
text = path.read_text()
text = replace_once(text,
    "package ru.playsoftware.j2meloader.filepicker\n\nimport androidx.compose.foundation.clickable",
    "package ru.playsoftware.j2meloader.filepicker\n\nimport android.content.res.Configuration\nimport androidx.compose.foundation.clickable",
    "picker Configuration import")
text = replace_once(text,
    "import androidx.compose.foundation.layout.size\n",
    "import androidx.compose.foundation.layout.size\nimport androidx.compose.foundation.layout.widthIn\n",
    "picker width import")
text = replace_once(text,
    "import androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier",
    "import androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.platform.LocalConfiguration",
    "picker LocalConfiguration import")
text = replace_once(text,
    "import androidx.compose.ui.unit.dp\n",
    "import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.window.DialogProperties\n",
    "picker DialogProperties import")
text = replace_once(text,
    """private fun CreateFolderDialog(
    state: FilePickerState,
    actions: FilePickerActions,
) {
    AlertDialog(
        onDismissRequest = actions::onDismissCreateFolder,""",
    """private fun CreateFolderDialog(
    state: FilePickerState,
    actions: FilePickerActions,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    AlertDialog(
        modifier = if (landscape) {
            Modifier.fillMaxWidth(0.94f).widthIn(max = 720.dp)
        } else {
            Modifier.widthIn(max = 560.dp)
        },
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
        onDismissRequest = actions::onDismissCreateFolder,""",
    "picker create folder adaptive")
path.write_text(text)


# Installer dialog host + Compose body --------------------------------------
path = Path("app/src/main/java/ru/woesss/j2me/installer/InstallerDialog.java")
text = path.read_text()
text = replace_once(text,
    "import android.content.Context;\nimport android.content.DialogInterface;",
    "import android.content.Context;\nimport android.content.DialogInterface;\nimport android.content.res.Configuration;",
    "installer Configuration import")
text = replace_once(text,
    """            int maxWidth = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 480, getResources().getDisplayMetrics());
            int width = Math.min(maxWidth, getResources().getDisplayMetrics().widthPixels - margin);""",
    """            int maxWidthDp = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE ? 760 : 480;
            int maxWidth = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, maxWidthDp, getResources().getDisplayMetrics());
            int width = Math.min(maxWidth, getResources().getDisplayMetrics().widthPixels - margin);""",
    "installer host adaptive width")
path.write_text(text)

path = Path("app/src/main/java/ru/woesss/j2me/installer/InstallerComposeBridge.kt")
text = path.read_text()
text = replace_once(text,
    "package ru.woesss.j2me.installer\n\nimport android.graphics.BitmapFactory",
    "package ru.woesss.j2me.installer\n\nimport android.content.res.Configuration\nimport android.graphics.BitmapFactory",
    "installer compose Configuration import")
text = replace_once(text,
    "import androidx.compose.ui.platform.ComposeView\nimport androidx.compose.ui.platform.ViewCompositionStrategy",
    "import androidx.compose.ui.platform.ComposeView\nimport androidx.compose.ui.platform.LocalConfiguration\nimport androidx.compose.ui.platform.ViewCompositionStrategy",
    "installer LocalConfiguration import")
text = replace_once(text,
    """    val icon = remember(iconPath) {
        iconPath?.let(BitmapFactory::decodeFile)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = 280.dp, max = 480.dp),""",
    """    val icon = remember(iconPath) {
        iconPath?.let(BitmapFactory::decodeFile)
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxDialogWidth = if (landscape) 720.dp else 480.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = 280.dp, max = maxDialogWidth),""",
    "installer compose adaptive width")
text = replace_once(text,
    """private fun InstallerMessage(message: String) {
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState()),""",
    """private fun InstallerMessage(message: String) {
    val maxMessageHeight = LocalConfiguration.current.screenHeightDp
        .minus(280)
        .coerceAtLeast(120)
        .coerceAtMost(360)
        .dp
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxMessageHeight)
            .verticalScroll(rememberScrollState()),""",
    "installer dynamic body height")
path.write_text(text)
