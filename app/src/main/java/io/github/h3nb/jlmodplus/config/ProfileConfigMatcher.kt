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

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.ArrayList
import java.util.Collections

/** Small deterministic matcher for the effective, unsaved configuration draft. */
internal class ProfileConfigMatcher private constructor() {
    class Candidate(
        @JvmField val profile: Profile,
        @JvmField val config: ProfileModel,
        @JvmField val hasKeyboardLayout: Boolean,
        @JvmField val keyboard: ByteArray?,
    )

    companion object {
        private val GSON: Gson = GsonBuilder().create()

        @JvmStatic
        fun readKeyboard(file: File?): ByteArray? {
            if (file == null || !file.isFile) {
                return null
            }
            val initialCapacity = minOf(file.length(), 16 * 1024L).toInt()
            return try {
                FileInputStream(file).use { input ->
                    ByteArrayOutputStream(maxOf(initialCapacity, 32)).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            } catch (_: IOException) {
                null
            }
        }

        @JvmStatic
        fun sameEffectiveConfig(
            current: ProfileModel,
            draft: ConfigFormState,
            candidate: ProfileModel,
        ): Boolean {
            val effective = copy(current)
            draft.applyTo(effective)
            return sameConfig(effective, candidate)
        }

        /** Builds matcher candidates from the shared capability inspection used by the UI and actions. */
        @JvmStatic
        fun loadCandidatesFromInspection(
            inspected: List<ProfilesManager.ProfileInfo>?,
        ): List<Candidate> {
            if (inspected == null || inspected.isEmpty()) return Collections.emptyList()
            val candidates = ArrayList<Candidate>()
            for (info in inspected) {
                val config = info.config
                if (!info.settings.isReady() || config == null) continue
                var hasKeyboardLayout = info.keyboardLayout.isReady()
                val keyboard = if (hasKeyboardLayout) readKeyboard(info.profile.keyLayout) else null
                if (hasKeyboardLayout && keyboard == null) hasKeyboardLayout = false
                candidates.add(Candidate(info.profile, config, hasKeyboardLayout, keyboard))
            }
            return candidates
        }

        /**
         * Compares a candidate against the effective draft without selecting an identity. A preset's
         * separate keyboard artifact participates in equality only when the preset owns that artifact.
         */
        @JvmStatic
        fun matchesCandidate(
            current: ProfileModel?,
            draft: ConfigFormState?,
            candidate: Candidate?,
            currentKeyboard: ByteArray?,
        ): Boolean {
            if (current == null || draft == null || candidate == null) {
                return false
            }
            val effective = copy(current)
            draft.applyTo(effective)
            return matchesCandidate(effective, candidate, currentKeyboard)
        }

        @JvmStatic
        fun sameConfig(left: ProfileModel, right: ProfileModel): Boolean {
            val leftCopy = copy(left)
            val rightCopy = copy(right)
            leftCopy.systemProperties = ConfigFormState.normalizeSystemProperties(leftCopy.systemProperties)
            rightCopy.systemProperties = ConfigFormState.normalizeSystemProperties(rightCopy.systemProperties)
            val leftJson: JsonElement = GSON.toJsonTree(leftCopy)
            val rightJson: JsonElement = GSON.toJsonTree(rightCopy)
            return leftJson == rightJson
        }

        private fun matchesCandidate(
            effective: ProfileModel,
            candidate: Candidate,
            currentKeyboard: ByteArray?,
        ): Boolean {
            if (!sameConfig(effective, candidate.config)) {
                return false
            }
            return !candidate.hasKeyboardLayout || sameKeyboardBytes(currentKeyboard, candidate.keyboard)
        }

        private fun copy(source: ProfileModel): ProfileModel {
            val copy = GSON.fromJson(GSON.toJson(source), ProfileModel::class.java)
            copy.dir = source.dir
            return copy
        }

        private fun sameKeyboardBytes(left: ByteArray?, right: ByteArray?): Boolean {
            if (left === right) {
                return true
            }
            if (left == null || right == null || left.size != right.size) {
                return false
            }
            for (i in left.indices) {
                if (left[i] != right[i]) {
                    return false
                }
            }
            return true
        }
    }
}
