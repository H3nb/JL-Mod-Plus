/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2019-2023 Yury Kharchenko
 * Modified for JL-Mod Plus.
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
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.preference.PreferenceManager
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppPageTransitions
import io.github.h3nb.jlmodplus.util.Constants.ACTION_EDIT_PROFILE
import io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class ProfilesActivity : AppCompatActivity() {
    private lateinit var preferences: SharedPreferences
    private lateinit var profilesRoot: File
    private lateinit var composeController: ProfilesComposeController
    private val profilesByName = HashMap<String, Profile>()
    private val profileExecutor = Executors.newSingleThreadExecutor()
    private var refreshGeneration = 0

    private val editProfileLauncher = registerForActivityResult(
        object : ActivityResultContract<String, String?>() {
            override fun createIntent(context: Context, input: String): Intent =
                Intent(ACTION_EDIT_PROFILE, Uri.parse(input), applicationContext, ConfigActivity::class.java)

            override fun parseResult(resultCode: Int, intent: Intent?): String? =
                if (resultCode == Activity.RESULT_OK) intent?.dataString else null
        },
    ) { name ->
        if (name != null) refreshProfiles()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPageTransitions.install(this)
        EdgeToEdgeCompat.enableForComposeSurface(this)
        val composeView = ComposeView(this)
        setContentView(composeView)
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        profilesRoot = File(
            savedInstanceState?.getString(STATE_PROFILES_ROOT) ?: Config.getProfilesDir(),
        ).absoluteFile
        composeController = ProfilesComposeController(composeView, createActions())
        refreshProfiles()
    }

    override fun onDestroy() {
        profileExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun createActions(): ProfilesActions = object : ProfilesActions {
        override fun onBack() {
            finish()
        }

        override fun onCreate(name: String) {
            editProfileLauncher.launch(File(profilesRoot, name).absolutePath)
        }

        override fun onSetBuiltInDefault() {
            if (setBuiltInDefault(preferences)) refreshProfiles()
        }

        override fun onSetDefault(name: String) {
            if (setNamedDefault(preferences, profilesRoot, name)) refreshProfiles()
        }

        override fun onEdit(name: String) {
            val profile = profilesByName[name] ?: return
            if (ProfilesManager.inspectProfile(profile, File(profilesRoot, name)).settings.isReady()) {
                editProfileLauncher.launch(File(profilesRoot, name).absolutePath)
            }
        }

        override fun onRename(oldName: String, newName: String) {
            val normalizedName = newName.trim()
            if (profilesByName[oldName] == null || !Profile.isValidName(normalizedName) ||
                profileNameExists(profilesRoot, normalizedName)
            ) return

            profileExecutor.execute {
                val result = PresetLifecycle.rename(preferences, profilesRoot, oldName, normalizedName)
                runOnUiThread { finishLifecycleOperation(result) }
            }
        }

        override fun onDelete(name: String) {
            if (profilesByName[name] == null) return
            profileExecutor.execute {
                val result = PresetLifecycle.delete(preferences, profilesRoot, name)
                runOnUiThread { finishLifecycleOperation(result) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROFILES_ROOT, profilesRoot.absolutePath)
        super.onSaveInstanceState(outState)
    }

    private fun finishLifecycleOperation(result: PresetLifecycle.Result) {
        if (isFinishing || isDestroyed) return
        if (result != PresetLifecycle.Result.SUCCESS) {
            composeController.showNotice(getString(R.string.profile_template_operation_failed))
        }
        refreshProfiles()
    }

    private fun refreshProfiles() {
        val generation = ++refreshGeneration
        val defaultName = preferences.getString(PREF_DEFAULT_PROFILE, null)
        profileExecutor.execute {
            val profiles = ProfilesManager.getList(profilesRoot).apply { sort() }
            val inspected = ArrayList<ProfilesManager.ProfileInfo>(profiles.size)
            for (profile in profiles) {
                inspected.add(ProfilesManager.inspectProfile(profile, File(profilesRoot, profile.name)))
            }
            val hasValidDefault = inspected.any { info ->
                info.completeSnapshotReady && defaultName != null && defaultName == info.profile.name
            }

            var defaultStateChanged: Boolean
            var defaultFallbackApplied = false
            var defaultFallbackFailed = false
            synchronized(ProfilesManager.presetSourceLock()) {
                val currentDefault = preferences.getString(PREF_DEFAULT_PROFILE, null)
                val currentDefaultReady = defaultName == null ||
                    (Profile.isValidName(defaultName) &&
                        ProfilesManager.isCompleteSnapshotReady(File(profilesRoot, defaultName)))
                val inspectedDefaultReady = defaultName == null || hasValidDefault
                defaultStateChanged = defaultName != currentDefault ||
                    currentDefaultReady != inspectedDefaultReady
                if (!defaultStateChanged && defaultName != null && !currentDefaultReady) {
                    defaultFallbackApplied = setBuiltInDefault(preferences)
                    defaultFallbackFailed = !defaultFallbackApplied
                }
            }

            if (defaultStateChanged) {
                runOnUiThread {
                    if (generation == refreshGeneration && !isFinishing && !isDestroyed) {
                        refreshProfiles()
                    }
                }
                return@execute
            }
            if (defaultFallbackFailed) {
                runOnUiThread {
                    if (generation == refreshGeneration && !isFinishing && !isDestroyed) {
                        composeController.showNotice(getString(R.string.profile_template_operation_failed))
                    }
                }
                return@execute
            }

            val builtInDefault = defaultName == null || defaultFallbackApplied
            val items = ArrayList<ProfileUiItem>(inspected.size + 1)
            items.add(
                ProfileUiItem(
                    name = "",
                    isDefault = builtInDefault,
                    canEdit = false,
                    canSetDefault = true,
                    isBuiltIn = true,
                ),
            )
            for (info in inspected) {
                val valid = info.settings.isReady()
                val keyboardReady = info.keyboardLayout.isReady()
                val keyboardOnly = info.settings.status == ProfilesManager.CapabilityStatus.ABSENT &&
                    keyboardReady
                items.add(
                    ProfileUiItem(
                        name = info.profile.name,
                        isDefault = hasValidDefault && info.profile.name == defaultName,
                        canEdit = valid,
                        canSetDefault = info.completeSnapshotReady,
                        isKeyboardOnly = keyboardOnly,
                        hasKeyboardLayout = keyboardReady,
                        screenWidth = info.config?.screenWidth ?: 0,
                        screenHeight = info.config?.screenHeight ?: 0,
                        orientation = info.config?.orientation ?: 0,
                        isUnavailable = !valid && !keyboardOnly,
                        isKeyboardLayoutUnavailable =
                            info.keyboardLayout.status == ProfilesManager.CapabilityStatus.UNAVAILABLE,
                    ),
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (defaultFallbackApplied) {
                    composeController.showNotice(
                        getString(R.string.profile_default_fallback_notice, defaultName),
                        6_000L,
                    )
                }
                if (generation != refreshGeneration) return@runOnUiThread
                profilesByName.clear()
                for (profile in profiles) profilesByName[profile.name] = profile
                composeController.updateProfileItems(items)
            }
        }
    }

    companion object {
        private const val STATE_PROFILES_ROOT = "profiles_root"

        @JvmStatic
        fun setBuiltInDefault(preferences: SharedPreferences): Boolean =
            synchronized(ProfilesManager.presetSourceLock()) {
                preferences.edit().remove(PREF_DEFAULT_PROFILE).commit()
            }

        private fun profileNameExists(root: File, name: String): Boolean =
            synchronized(ProfilesManager.presetSourceLock()) {
                try {
                    ProfilesManager.profileNameExistsLocked(root, name)
                } catch (_: IOException) {
                    true
                } catch (_: RuntimeException) {
                    true
                }
            }

        @JvmStatic
        fun setNamedDefault(preferences: SharedPreferences, profilesRoot: File, name: String): Boolean =
            synchronized(ProfilesManager.presetSourceLock()) {
                if (!Profile.isValidName(name)) return@synchronized false
                val sourceDir = File(profilesRoot, name)
                if (!sourceDir.isDirectory || !ProfilesManager.isCompleteSnapshotReady(sourceDir)) {
                    return@synchronized false
                }
                preferences.edit().putString(PREF_DEFAULT_PROFILE, name).commit()
            }
    }
}
