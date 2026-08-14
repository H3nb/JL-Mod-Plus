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

package javax.microedition.lcdui.commands

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import javax.microedition.lcdui.Command
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private val PreviewCommands = listOf(
    Command("Options", Command.SCREEN, 1),
    Command("Confirm", Command.OK, 1),
    Command("Back", Command.BACK, 1),
    Command("Help", Command.ITEM, 1),
)
private val PreviewPresentation = ScreenSoftBarPolicy.present(PreviewCommands)

@PreviewTest
@Preview(name = "Screen soft keys", widthDp = 360, heightDp = 56, showBackground = true)
@Composable
fun ScreenSoftKeysScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        ScreenSoftBarContent(
            presentation = PreviewPresentation,
            menuVisible = false,
            onOpenMenu = {},
            onDismissMenu = {},
            onCommand = {},
        )
    }
}

@PreviewTest
@Preview(
    name = "Screen soft keys dark large font",
    widthDp = 360,
    heightDp = 72,
    fontScale = 1.5f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun ScreenSoftKeysDarkLargeFontScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        ScreenSoftBarContent(
            presentation = PreviewPresentation,
            menuVisible = false,
            onOpenMenu = {},
            onDismissMenu = {},
            onCommand = {},
        )
    }
}
