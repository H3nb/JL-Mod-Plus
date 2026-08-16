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

package ru.playsoftware.j2meloader.config;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Small deterministic matcher for the effective, unsaved configuration draft. */
final class ProfileConfigMatcher {
	private static final Gson GSON = new GsonBuilder().create();

	private ProfileConfigMatcher() {
	}

	@Nullable
	static Profile findMatch(
			ProfileModel current,
			ConfigFormState draft,
			List<Profile> profiles,
			@Nullable String defaultProfile,
			@Nullable File currentKeyLayout) {
		if (current == null || draft == null || profiles == null || profiles.isEmpty()) {
			return null;
		}

		ProfileModel effective = copy(current);
		draft.applyTo(effective);
		ArrayList<Profile> matches = new ArrayList<>();
		for (Profile profile : profiles) {
			// Do not invoke legacy XML migration from a render-time status refresh.
			if (!profile.hasConfig()) {
				continue;
			}
			ProfileModel candidate = ProfilesManager.loadConfig(profile.getDir(), false);
			if (candidate == null || !sameConfig(effective, candidate)) {
				continue;
			}
			// Keyboard state is part of a profile only when that profile explicitly owns a
			// keyboard artifact. Config-only profiles intentionally ignore keyboard differences.
			if (profile.hasKeyLayout() && !sameKeyboardFile(currentKeyLayout, profile.getKeyLayout())) {
				continue;
			}
			matches.add(profile);
		}

		return selectMatch(matches, defaultProfile);
	}

	@Nullable
	static Profile selectMatch(List<Profile> matches, @Nullable String defaultProfile) {
		if (matches == null || matches.isEmpty()) {
			return null;
		}
		ArrayList<Profile> ordered = new ArrayList<>(matches);
		Collections.sort(ordered, new Comparator<Profile>() {
			@Override
			public int compare(Profile left, Profile right) {
				int result = left.getName().compareToIgnoreCase(right.getName());
				return result != 0 ? result : left.getName().compareTo(right.getName());
			}
		});
		if (defaultProfile != null) {
			for (Profile profile : ordered) {
				if (defaultProfile.equals(profile.getName())) {
					return profile;
				}
			}
		}
		return ordered.get(0);
	}

	static boolean sameEffectiveConfig(ProfileModel current, ConfigFormState draft,
			ProfileModel candidate) {
		ProfileModel effective = copy(current);
		draft.applyTo(effective);
		return sameConfig(effective, candidate);
	}

	private static boolean sameConfig(ProfileModel left, ProfileModel right) {
		left = copy(left);
		right = copy(right);
		left.systemProperties = ConfigFormState.normalizeSystemProperties(left.systemProperties);
		right.systemProperties = ConfigFormState.normalizeSystemProperties(right.systemProperties);
		JsonElement leftJson = GSON.toJsonTree(left);
		JsonElement rightJson = GSON.toJsonTree(right);
		return leftJson.equals(rightJson);
	}

	private static ProfileModel copy(ProfileModel source) {
		ProfileModel copy = GSON.fromJson(GSON.toJson(source), ProfileModel.class);
		copy.dir = source.dir;
		return copy;
	}

	static boolean sameKeyboardFile(@Nullable File left, File right) {
		if (left == null || !left.isFile() || !right.isFile() || left.length() != right.length()) {
			return false;
		}
		try (FileInputStream leftStream = new FileInputStream(left);
				 FileInputStream rightStream = new FileInputStream(right)) {
			int leftByte;
			while ((leftByte = leftStream.read()) != -1) {
				if (leftByte != rightStream.read()) {
					return false;
				}
			}
			return rightStream.read() == -1;
		} catch (IOException e) {
			return false;
		}
	}
}
