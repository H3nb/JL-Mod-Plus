/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2019-2023 Yury Kharchenko
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

package io.github.h3nb.jlmodplus.config

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.WindowInsetsPolicy
import io.github.h3nb.jlmodplus.util.Constants.ACTION_EDIT_PROFILE
import io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE
import java.io.File

class ProfilesActivity : AppCompatActivity() {
    private lateinit var uiState: ProfilesUiState
    private lateinit var preferences: android.content.SharedPreferences
    private var defaultProfile: Profile? = null
    private var nameDialog by mutableStateOf<ProfileNameDialogState?>(null)
    private var nameValue by mutableStateOf("")

    private val editProfileLauncher = registerForActivityResult(
        object : ActivityResultContract<String, String>() {
            override fun createIntent(context: Context, input: String): Intent = Intent(
                ACTION_EDIT_PROFILE,
                Uri.parse(input),
                applicationContext,
                ConfigActivity::class.java,
            )

            override fun parseResult(resultCode: Int, intent: Intent?): String =
                if (resultCode == Activity.RESULT_OK) intent?.dataString.orEmpty() else ""
        },
    ) { name ->
        if (name.isNotEmpty() && !isFinishing && !isDestroyed) {
            uiState.addProfile(Profile(name))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsPolicy.enableEdgeToEdge(window)
        uiState = ProfilesUiState()
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val profiles = ProfilesManager.getProfiles()
        val defaultName = preferences.getString(PREF_DEFAULT_PROFILE, null)
        defaultProfile = profiles.lastOrNull { it.name == defaultName }
        uiState.setProfiles(profiles, defaultProfile?.name)

        setContent {
            ProfilesScreen(
                state = uiState,
                nameDialog = nameDialog,
                nameValue = nameValue,
                onNameValueChange = { nameValue = it },
                onNameConfirm = ::submitProfileName,
                onNameDismiss = { nameDialog = null },
                onBack = ::finish,
                onAdd = { showProfileNameDialog(R.string.enter_name, "", -1) },
                onAction = ::onProfileAction,
            )
        }
    }

    private fun onProfileAction(profile: Profile, itemId: Int) {
        when (itemId) {
            R.id.action_context_default -> {
                preferences.edit().putString(PREF_DEFAULT_PROFILE, profile.name).apply()
                defaultProfile = profile
                uiState.setDefault(profile)
            }

            R.id.action_context_edit -> {
                startActivity(
                    Intent(
                        ACTION_EDIT_PROFILE,
                        Uri.parse(profile.name),
                        applicationContext,
                        ConfigActivity::class.java,
                    ),
                )
            }

            R.id.action_context_rename -> showProfileNameDialog(
                R.string.enter_new_name,
                profile.name,
                uiState.profilesState.indexOf(profile),
            )

            R.id.action_context_delete -> {
                profile.delete()
                uiState.removeProfile(profile)
            }
        }
    }

    private fun showProfileNameDialog(titleRes: Int, initialName: String, profileIndex: Int) {
        nameValue = initialName
        nameDialog = ProfileNameDialogState(titleRes, initialName, profileIndex)
    }

    private fun submitProfileName() {
        val dialog = nameDialog ?: return
        val newName = nameValue.trim()
        if (newName.isEmpty()) {
            Toast.makeText(this, R.string.error_name, Toast.LENGTH_SHORT).show()
            return
        }
        if (newName == dialog.initialName || File(Config.getProfilesDir(), newName).exists()) {
            val toast = Toast.makeText(this, R.string.not_saved_exists, Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER_HORIZONTAL or Gravity.TOP, 0, 50)
            toast.show()
            return
        }
        if (dialog.profileIndex == -1) {
            editProfileLauncher.launch(newName)
        } else {
            val profile = uiState.profilesState[dialog.profileIndex]
            profile.renameTo(newName)
            uiState.refresh()
            if (defaultProfile == profile) {
                preferences.edit().putString(PREF_DEFAULT_PROFILE, newName).apply()
                uiState.setDefault(profile)
            }
        }
        nameDialog = null
    }
}
