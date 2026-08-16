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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.io.ByteArrayOutputStream;
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

	static final class Candidate {
		final Profile profile;
		final ProfileModel config;
		@Nullable final byte[] keyboard;

		Candidate(@NonNull Profile profile, @NonNull ProfileModel config, @Nullable byte[] keyboard) {
			this.profile = profile;
			this.config = config;
			this.keyboard = keyboard;
		}
	}

	@NonNull
	static List<Candidate> loadCandidates(@Nullable List<Profile> profiles) {
		if (profiles == null || profiles.isEmpty()) {
			return Collections.emptyList();
		}
		ArrayList<Candidate> candidates = new ArrayList<>();
		for (Profile profile : profiles) {
			if (!profile.hasConfig() && !profile.hasOldConfig()) {
				continue;
			}
			ProfileModel config = ProfilesManager.loadConfig(profile.getDir(), false);
			if (config == null) {
				continue;
			}
			byte[] keyboard = profile.hasKeyLayout() ? readKeyboard(profile.getKeyLayout()) : null;
			candidates.add(new Candidate(profile, config, keyboard));
		}
		return candidates;
	}

	@Nullable
	static byte[] readKeyboard(@Nullable File file) {
		if (file == null || !file.isFile()) {
			return null;
		}
		int initialCapacity = (int) Math.min(file.length(), 16 * 1024L);
		try (FileInputStream input = new FileInputStream(file);
			 ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(initialCapacity, 32))) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				output.write(buffer, 0, count);
			}
			return output.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

	@Nullable
	static Profile findMatchCached(
			ProfileModel current,
			ConfigFormState draft,
			List<Candidate> candidates,
			@Nullable String defaultProfile,
			@Nullable byte[] currentKeyboard) {
		if (current == null || draft == null || candidates == null || candidates.isEmpty()) {
			return null;
		}

		ProfileModel effective = copy(current);
		draft.applyTo(effective);
		ArrayList<Profile> matches = new ArrayList<>();
		for (Candidate candidate : candidates) {
			if (!sameConfig(effective, candidate.config)) {
				continue;
			}
			// Keyboard state is part of a profile only when that profile explicitly owns a
			// keyboard artifact. Config-only profiles intentionally ignore keyboard differences.
			if (candidate.keyboard != null && !sameKeyboardBytes(currentKeyboard, candidate.keyboard)) {
				continue;
			}
			matches.add(candidate.profile);
		}
		return selectMatch(matches, defaultProfile);
	}

	@Nullable
	static Profile findMatch(
			ProfileModel current,
			ConfigFormState draft,
			List<Profile> profiles,
			@Nullable String defaultProfile,
			@Nullable File currentKeyLayout) {
		return findMatchCached(
				current,
				draft,
				loadCandidates(profiles),
				defaultProfile,
				readKeyboard(currentKeyLayout));
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

	static boolean sameKeyboardBytes(@Nullable byte[] left, @Nullable byte[] right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null || left.length != right.length) {
			return false;
		}
		for (int i = 0; i < left.length; i++) {
			if (left[i] != right[i]) {
				return false;
			}
		}
		return true;
	}

	static boolean sameKeyboardFile(@Nullable File left, File right) {
		return sameKeyboardBytes(readKeyboard(left), readKeyboard(right));
	}
}
