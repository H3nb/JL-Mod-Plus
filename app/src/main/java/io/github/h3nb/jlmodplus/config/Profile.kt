/*
 * Copyright 2018 Nikita Shakarun
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

import androidx.annotation.Nullable
import io.github.h3nb.jlmodplus.util.FileUtils
import java.io.File
import java.util.Locale

class Profile(name: String) : Comparable<Profile> {
    private var profileName = name

    fun create() {
        dir.mkdirs()
    }

    fun renameTo(newName: String): Boolean {
        val oldDir = dir
        val newDir = File(Config.getProfilesDir(), newName)
        if (!oldDir.renameTo(newDir)) {
            return false
        }
        profileName = newName
        return true
    }

    fun delete(): Boolean {
        val profileDir = dir
        if (!profileDir.exists()) return true
        FileUtils.deleteDirectory(profileDir)
        return !profileDir.exists()
    }

    val name: String
        get() = profileName

    val dir: File
        get() = File(Config.getProfilesDir(), profileName)

    val config: File
        get() = File(Config.getProfilesDir(), profileName + Config.MIDLET_CONFIG_FILE)

    val keyLayout: File
        get() = File(Config.getProfilesDir(), profileName + Config.MIDLET_KEY_LAYOUT_FILE)

    override fun toString(): String = profileName

    override fun compareTo(other: Profile): Int {
        val locale = Locale.getDefault()
        return profileName.lowercase(locale).compareTo(other.profileName.lowercase(locale))
    }

    fun hasConfig(): Boolean = config.exists()

    fun hasKeyLayout(): Boolean = keyLayout.exists()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Profile) return false
        return profileName == other.profileName
    }

    override fun hashCode(): Int = profileName.hashCode()

    fun hasOldConfig(): Boolean = File(Config.getProfilesDir(), "$profileName/config.xml").exists()

    companion object {
        /** Validates a user-facing name before it is used as a profile directory name. */
        @JvmStatic
        fun isValidName(@Nullable rawName: String?): Boolean {
            if (rawName == null) return false
            val value = rawName.trim()
            if (value.isEmpty() || value == "." || value == "..") return false
            for (character in value) {
                if (character == '/' || character == '\\' || character == ':' || character == '*'
                    || character == '?' || character == '"' || character == '<' || character == '>'
                    || character == '|' || character.isISOControl()
                ) {
                    return false
                }
            }
            return true
        }
    }
}
