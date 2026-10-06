package io.github.h3nb.jlmodplus.installer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import java.io.File
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

@PreviewTest
@Preview(name = "Batch partial results", widthDp = 360, heightDp = 640, showBackground = true)
@Preview(name = "Batch partial results short", widthDp = 480, heightDp = 240, fontScale = 2f, showBackground = true)
@Composable
fun BulkInstallerResultsScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        BulkInstallSurface(
            state = BulkInstallViewModel.State.Finished(
                BulkInstallPlan(1, File("/workdir"), emptyList()),
                listOf(
                    BulkInstallResult("one", "Game one", BulkInstallResultKind.Installed),
                    BulkInstallResult("two", "Game two", BulkInstallResultKind.PartiallyInstalled),
                    BulkInstallResult("three", "Game three", BulkInstallResultKind.NotProcessed),
                ), cancelled = true,
            ),
            onToggle = {}, onRecommended = {}, onClear = {}, onInstall = {}, onRetry = {},
            onCancel = {}, onClose = {},
        )
    }
}

@PreviewTest
@Preview(name = "Batch success warning", widthDp = 360, heightDp = 640, showBackground = true)
@Preview(name = "Batch success warning short", widthDp = 480, heightDp = 240, fontScale = 2f, showBackground = true)
@Composable
fun BulkInstallerSuccessWarningScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        BulkInstallSurface(
            state = BulkInstallViewModel.State.Finished(
                BulkInstallPlan(1, File("/workdir"), emptyList()),
                listOf(
                    BulkInstallResult(
                        "one",
                        "Heroes Lore: Wind of Soltia by EditorKamar",
                        BulkInstallResultKind.Installed,
                        pluralStringResource(
                            R.plurals.installer_conversion_warning_summary,
                            1,
                            1,
                        ),
                    ),
                    BulkInstallResult(
                        "two",
                        "Silent Hill Mobile DX 1",
                        BulkInstallResultKind.Installed,
                    ),
                ),
                cancelled = false,
            ),
            onToggle = {},
            onRecommended = {},
            onClear = {},
            onInstall = {},
            onRetry = {},
            onCancel = {},
            onClose = {},
        )
    }
}

