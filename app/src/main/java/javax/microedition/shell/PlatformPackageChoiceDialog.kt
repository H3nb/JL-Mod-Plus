// SPDX-License-Identifier: Apache-2.0
package javax.microedition.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog

/** The runtime owns only this choice; the Library still owns all installation work. */
@Composable
internal fun PlatformPackageChoiceDialog(
    onInstall: () -> Unit,
    onBrowser: () -> Unit,
    onCancel: () -> Unit,
) {
    AdaptiveAlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.platform_package_choice_title)) },
        // One scrollable body keeps every action reachable when the IME or landscape leaves
        // little vertical space; portrait presentation remains content-wrapped and compact.
        textScrollable = false,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.platform_package_choice_message))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.platform_package_install))
                    }
                    OutlinedButton(
                        onClick = onBrowser,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.platform_package_browser))
                    }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        },
    )
}
