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

package io.github.h3nb.jlmodplus.config;

import static io.github.h3nb.jlmodplus.util.Constants.ACTION_EDIT_PROFILE;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;

import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat;
import io.github.h3nb.jlmodplus.ui.ThemedToast;
import io.github.h3nb.jlmodplus.R;

public class ProfilesActivity extends AppCompatActivity {
	private static final String STATE_PROFILES_ROOT = "profiles_root";
	private final Map<String, Profile> profilesByName = new HashMap<>();
	private SharedPreferences preferences;
	private File profilesRoot;
	private ProfilesComposeController composeController;
	private final ExecutorService profileExecutor = Executors.newSingleThreadExecutor();
	private int refreshGeneration;

	private final ActivityResultLauncher<String> editProfileLauncher = registerForActivityResult(
			new ActivityResultContract<String, String>() {
				@NonNull
				@Override
				public Intent createIntent(@NonNull Context context, String input) {
					return new Intent(ACTION_EDIT_PROFILE, Uri.parse(input),
							getApplicationContext(), ConfigActivity.class);
				}

				@Override
				public String parseResult(int resultCode, @Nullable Intent intent) {
					if (resultCode == Activity.RESULT_OK && intent != null) {
						return intent.getDataString();
					}
					return null;
				}
			},
			name -> {
				if (name != null) {
					refreshProfiles();
				}
			});

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableForComposeSurface(this);
		ComposeView composeView = new ComposeView(this);
		setContentView(composeView);
		preferences = PreferenceManager.getDefaultSharedPreferences(this);
		String savedRoot = savedInstanceState == null
				? null : savedInstanceState.getString(STATE_PROFILES_ROOT);
		profilesRoot = new File(savedRoot == null ? Config.getProfilesDir() : savedRoot).getAbsoluteFile();
		composeController = new ProfilesComposeController(composeView, createActions());
		refreshProfiles();
	}

	@Override
	protected void onDestroy() {
		profileExecutor.shutdownNow();
		super.onDestroy();
	}

	private ProfilesActions createActions() {
		return new ProfilesActions() {
			@Override
			public void onBack() {
				finish();
			}

			@Override
			public void onCreate(@NonNull String name) {
				editProfileLauncher.launch(new File(profilesRoot, name).getAbsolutePath());
			}

			@Override
			public void onSetBuiltInDefault() {
				if (setBuiltInDefault(preferences)) refreshProfiles();
			}

			@Override
			public void onSetDefault(@NonNull String name) {
				if (setNamedDefault(preferences, profilesRoot, name)) {
					refreshProfiles();
				}
			}

			@Override
			public void onEdit(@NonNull String name) {
				Profile profile = profilesByName.get(name);
				if (profile != null && ProfilesManager.inspectProfile(
						profile, new File(profilesRoot, name)).settings.isReady()) {
					editProfileLauncher.launch(new File(profilesRoot, name).getAbsolutePath());
				}
			}

			@Override
			public void onRename(@NonNull String oldName, @NonNull String newName) {
				String normalizedName = newName.trim();
				if (profilesByName.get(oldName) == null || !Profile.isValidName(normalizedName)
						|| profileNameExists(profilesRoot, normalizedName)) {
					return;
				}
				profileExecutor.execute(() -> {
					PresetLifecycle.Result result = PresetLifecycle.rename(
							preferences,
							profilesRoot,
							oldName,
							normalizedName);
					runOnUiThread(() -> finishLifecycleOperation(result));
				});
			}

			@Override
			public void onDelete(@NonNull String name) {
				if (profilesByName.get(name) == null) return;
				profileExecutor.execute(() -> {
					PresetLifecycle.Result result = PresetLifecycle.delete(
							preferences,
							profilesRoot,
							name);
					runOnUiThread(() -> finishLifecycleOperation(result));
				});
			}
		};
	}

	static boolean setBuiltInDefault(@NonNull SharedPreferences preferences) {
		synchronized (ProfilesManager.presetSourceLock()) {
			return preferences.edit().remove(PREF_DEFAULT_PROFILE).commit();
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		outState.putString(STATE_PROFILES_ROOT, profilesRoot.getAbsolutePath());
		super.onSaveInstanceState(outState);
	}

	private static boolean profileNameExists(@NonNull File root, @NonNull String name) {
		synchronized (ProfilesManager.presetSourceLock()) {
			try {
				return ProfilesManager.profileNameExistsLocked(root, name);
			} catch (IOException | RuntimeException unavailable) {
				return true;
			}
		}
	}

	static boolean setNamedDefault(
			@NonNull SharedPreferences preferences,
			@NonNull File profilesRoot,
			@NonNull String name) {
		synchronized (ProfilesManager.presetSourceLock()) {
			if (!Profile.isValidName(name)) return false;
			File sourceDir = new File(profilesRoot, name);
			if (!sourceDir.isDirectory()) return false;
			if (!ProfilesManager.isCompleteSnapshotReady(sourceDir)) return false;
			return preferences.edit().putString(PREF_DEFAULT_PROFILE, name).commit();
		}
	}

	private void finishLifecycleOperation(@NonNull PresetLifecycle.Result result) {
		if (isFinishing() || isDestroyed()) return;
		if (result != PresetLifecycle.Result.SUCCESS) {
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
		}
		refreshProfiles();
	}

	private void refreshProfiles() {
		final int generation = ++refreshGeneration;
		final String defaultName = preferences.getString(PREF_DEFAULT_PROFILE, null);
		profileExecutor.execute(() -> {
			ArrayList<Profile> profiles = ProfilesManager.getList(profilesRoot);
			Collections.sort(profiles);
			ArrayList<ProfilesManager.ProfileInfo> inspected = new ArrayList<>(profiles.size());
			for (Profile profile : profiles) {
				inspected.add(ProfilesManager.inspectProfile(
						profile, new File(profilesRoot, profile.getName())));
			}
			boolean hasValidDefault = false;
			for (ProfilesManager.ProfileInfo info : inspected) {
				if (info.completeSnapshotReady && defaultName != null
						&& defaultName.equals(info.profile.getName())) {
					hasValidDefault = true;
				}
			}

			final boolean defaultStateChanged;
			final boolean defaultFallbackApplied;
			final boolean defaultFallbackFailed;
			synchronized (ProfilesManager.presetSourceLock()) {
				String currentDefault = preferences.getString(PREF_DEFAULT_PROFILE, null);
				boolean sameDefault = defaultName == null
						? currentDefault == null : defaultName.equals(currentDefault);
				boolean currentDefaultReady = defaultName == null
						|| Profile.isValidName(defaultName)
						&& ProfilesManager.isCompleteSnapshotReady(
								new File(profilesRoot, defaultName));
				boolean inspectedDefaultReady = defaultName == null || hasValidDefault;
				defaultStateChanged = !sameDefault || currentDefaultReady != inspectedDefaultReady;
				if (defaultStateChanged) {
					defaultFallbackApplied = false;
					defaultFallbackFailed = false;
				} else if (defaultName != null && !currentDefaultReady) {
					defaultFallbackApplied = setBuiltInDefault(preferences);
					defaultFallbackFailed = !defaultFallbackApplied;
				} else {
					defaultFallbackApplied = false;
					defaultFallbackFailed = false;
				}
			}
			if (defaultStateChanged) {
				runOnUiThread(() -> {
					if (generation == refreshGeneration && !isFinishing() && !isDestroyed()) {
						refreshProfiles();
					}
				});
				return;
			}
			if (defaultFallbackFailed) {
				runOnUiThread(() -> {
					if (generation == refreshGeneration && !isFinishing() && !isDestroyed()) {
						ThemedToast.show(
								this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
					}
				});
				return;
			}

			boolean builtInDefault = defaultName == null || defaultFallbackApplied;
			ArrayList<ProfileUiItem> items = new ArrayList<>(inspected.size() + 1);
			items.add(new ProfileUiItem(
					"", builtInDefault, false, true, true, false, false, 0, 0, 0, false, false));
			for (ProfilesManager.ProfileInfo info : inspected) {
				boolean valid = info.settings.isReady();
				boolean keyboardOnly = info.settings.status == ProfilesManager.CapabilityStatus.ABSENT
						&& info.keyboardLayout.isReady();
				boolean unavailable = !valid && !keyboardOnly;
				items.add(new ProfileUiItem(
						info.profile.getName(),
						hasValidDefault && info.profile.getName().equals(defaultName),
						valid,
						info.completeSnapshotReady,
						false,
						keyboardOnly,
						info.keyboardLayout.isReady(),
						info.config == null ? 0 : info.config.screenWidth,
						info.config == null ? 0 : info.config.screenHeight,
						info.config == null ? 0 : info.config.orientation,
						unavailable,
						info.keyboardLayout.status == ProfilesManager.CapabilityStatus.UNAVAILABLE));
			}
			runOnUiThread(() -> {
				if (generation != refreshGeneration || isFinishing() || isDestroyed()) return;
				profilesByName.clear();
				for (Profile profile : profiles) profilesByName.put(profile.getName(), profile);
				composeController.updateProfileItems(items);
				if (defaultFallbackApplied) {
					ThemedToast.show(
							this,
							getString(R.string.profile_default_fallback_notice, defaultName),
							Toast.LENGTH_LONG);
				}
			});
		});
	}
}
