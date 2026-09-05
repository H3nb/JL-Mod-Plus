/* Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package ru.playsoftware.j2meloader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Popup teal portrait", widthDp = 360, heightDp = 640)
@Preview(name = "Popup teal short large text", widthDp = 480, heightDp = 240, fontScale = 2f)
@Preview(name = "Popup teal expanded", widthDp = 840, heightDp = 700)
@Composable
fun AdaptiveDialogTealScreenshot() {
    JLModPlusTheme(darkTheme = false, accent = AccentPalette.Teal) { RecoveryPreview() }
}

@PreviewTest
@Preview(name = "Popup violet dark portrait", widthDp = 360, heightDp = 640)
@Preview(name = "Popup violet dark short large text", widthDp = 480, heightDp = 240, fontScale = 2f)
@Composable
fun AdaptiveDialogVioletScreenshot() {
    JLModPlusTheme(darkTheme = true, accent = AccentPalette.Violet) { RecoveryPreview() }
}

@Composable private fun RecoveryPreview() {
    AdaptiveAlertDialog(onDismissRequest = {}, title = { Text("Recover Application") },
        text = { Column {
            Text("Application files were saved. Refresh the Library to finish updating the catalog.")
            Text("Your saved data and settings will be kept.")
        } },
        confirmButton = { TextButton(onClick = {}) { Text("Refresh Library") } },
        dismissButton = { TextButton(onClick = {}) { Text("Close") } })
}
