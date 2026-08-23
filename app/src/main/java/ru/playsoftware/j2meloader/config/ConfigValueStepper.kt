/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R

/**
 * Precise, keyboard-friendly value control shared by numeric config dialogs.
 *
 * The field remains the source of truth for typed values while the step buttons provide a
 * comfortable touch target. Callers own validation and snapping so integer and fractional
 * settings can use their native ranges without duplicating layout code.
 */
@Composable
internal fun ConfigValueStepper(
    valueText: String,
    onValueTextChange: (String) -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = onDecrease,
            enabled = decreaseEnabled,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_remove_circle),
                contentDescription = stringResource(R.string.config_decrease_value),
            )
        }
        OutlinedTextField(
            value = valueText,
            onValueChange = onValueTextChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = keyboardOptions,
        )
        IconButton(
            onClick = onIncrease,
            enabled = increaseEnabled,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.config_increase_value),
            )
        }
    }
}
