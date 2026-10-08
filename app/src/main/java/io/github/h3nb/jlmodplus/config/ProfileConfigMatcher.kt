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

/** Small deterministic matcher for the effective, unsaved configuration draft. */
internal class ProfileConfigMatcher private constructor() {
    companion object {
        private val GSON: Gson = GsonBuilder().create()

        @JvmStatic
        fun copyConfig(source: ProfileModel): ProfileModel = copy(source)

        @JvmStatic
        fun effectiveConfig(current: ProfileModel, draft: ConfigFormState): ProfileModel {
            val effective = copy(current)
            draft.applyTo(effective)
            return effective
        }

        @JvmStatic
        fun sameEffectiveConfig(
            current: ProfileModel,
            draft: ConfigFormState,
            candidate: ProfileModel,
        ): Boolean = sameConfig(effectiveConfig(current, draft), candidate)

        @JvmStatic
        fun sameConfig(left: ProfileModel, right: ProfileModel): Boolean {
            return comparisonSnapshot(left) == comparisonSnapshot(right)
        }

        /** Keeps the serializer's numeric/adapter canonicalization without retaining a cache. */
        @JvmStatic
        fun comparisonSnapshot(source: ProfileModel): JsonElement {
            val normalized = copy(source)
            normalized.systemProperties = ConfigFormState.normalizeSystemProperties(normalized.systemProperties)
            return GSON.toJsonTree(normalized)
        }

        private fun copy(source: ProfileModel): ProfileModel {
            val copy = GSON.fromJson(GSON.toJson(source), ProfileModel::class.java)
            copy.dir = source.dir
            return copy
        }
    }
}
