/*
 * Copyright 2026 H3NB
 *
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

package javax.microedition.shell.memory.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MidnightGraphiteScheme = darkColorScheme(
    primary = Color(0xFF3A9BFF),
    onPrimary = Color(0xFF001B30),
    primaryContainer = Color(0xFF123B63),
    onPrimaryContainer = Color(0xFFD4EAFF),
    secondary = Color(0xFF70B9FF),
    onSecondary = Color(0xFF002A47),
    secondaryContainer = Color(0xFF17324B),
    onSecondaryContainer = Color(0xFFD2E8FF),
    tertiary = Color(0xFF22D3EE),
    onTertiary = Color(0xFF002E35),
    background = Color(0xFF080B10),
    onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF111720),
    onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF1A2330),
    onSurfaceVariant = Color(0xFFAFBAC9),
    outline = Color(0xFF44546A),
    outlineVariant = Color(0xFF263344),
    error = Color(0xFFFF6680),
    onError = Color(0xFF3D0010),
)

@Composable
internal fun MemoryEditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MidnightGraphiteScheme,
        content = content,
    )
}
