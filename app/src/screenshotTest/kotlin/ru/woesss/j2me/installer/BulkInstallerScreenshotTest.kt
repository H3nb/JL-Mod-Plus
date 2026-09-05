package ru.woesss.j2me.installer

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import java.io.File
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

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
