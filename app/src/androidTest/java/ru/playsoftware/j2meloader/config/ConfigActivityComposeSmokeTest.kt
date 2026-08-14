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

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.util.Constants
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConfigActivityComposeSmokeTest {
    private var fixtureRoot: File? = null

    @After
    fun tearDown() {
        fixtureRoot?.let(::deleteRecursively)
    }

    @Test
    fun configActivityHostsComposeRootForRealProfileFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "config-compose-fixture")
        fixtureRoot = root
        val appDir = File(File(root, "converted"), "fixture")
        assertTrue(appDir.mkdirs() || appDir.isDirectory)
        val intent = Intent(
            Constants.ACTION_EDIT,
            Uri.parse(appDir.absolutePath),
            context,
            ConfigActivity::class.java,
        ).putExtra(Constants.KEY_MIDLET_NAME, "Config Compose Fixture")

        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val composeView = activity.findViewById<ComposeView>(R.id.config_compose_root)
                assertNotNull("ConfigActivity must host the Compose form", composeView)
                assertNotNull("ConfigActivity must retain a native window", activity.window)
            }
        }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        file.listFiles()?.forEach(::deleteRecursively)
        check(file.delete() || !file.exists()) { "Unable to delete $file" }
    }
}
