/*
 * Copyright 2017 Nikita Shakarun
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

package io.github.h3nb.jlmodplus.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import io.github.h3nb.jlmodplus.config.ProfilesActivity
import io.github.h3nb.jlmodplus.crashes.dialog.AudioFailureReportActivity
import io.github.h3nb.jlmodplus.ui.WindowInsetsPolicy
import io.github.h3nb.jlmodplus.util.Constants.PREF_EMULATOR_DIR
import io.github.h3nb.jlmodplus.util.FileUtils
import io.github.h3nb.jlmodplus.util.PickDirResultContract
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var uiState: SettingsUiState

    private val openDirLauncher = registerForActivityResult(
        PickDirResultContract(),
    ) { uri -> onPickDirResult(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsPolicy.enableEdgeToEdge(window)
        uiState = SettingsUiState(this)
        setContent {
            SettingsScreen(
                state = uiState,
                onBack = ::finish,
                onProfiles = {
                    startActivity(Intent(this, ProfilesActivity::class.java))
                },
                onAudioDiagnostics = {
                    startActivity(Intent(this, AudioFailureReportActivity::class.java))
                },
                onChooseDirectory = { openDirLauncher.launch(null) },
            )
        }
    }

    private fun onPickDirResult(uri: Uri?) {
        if (isFinishing || isDestroyed || uri?.path == null) {
            return
        }
        val file = File(uri.path!!)
        val path = file.absolutePath
        if (!FileUtils.initWorkDir(file)) {
            uiState.showDirectoryError(path)
            return
        }
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putString(PREF_EMULATOR_DIR, path)
            .apply()
        uiState.setDirectory(path)
    }
}
