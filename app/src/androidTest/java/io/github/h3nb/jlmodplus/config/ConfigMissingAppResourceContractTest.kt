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

package io.github.h3nb.jlmodplus.config

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigMissingAppResourceContractTest {
    @Test
    fun namedStorageMessageOwnsQuotesAndSpacing() {
        val context = englishContext()

        assertEquals(
            "Application not found.\n" +
                "Check whether the storage device “Primary storage” is available, " +
                "or recreate the shortcut.",
            context.getString(R.string.config_missing_app_storage_named, "Primary storage"),
        )
    }

    @Test
    fun genericStorageMessageNeedsNoFormattingArgument() {
        val context = englishContext()

        assertEquals(
            "Application not found.\n" +
                "Check whether the storage device is available, or recreate the shortcut.",
            context.getString(R.string.config_missing_app_storage_generic),
        )
    }

    private fun englishContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        return base.createConfigurationContext(configuration)
    }
}
