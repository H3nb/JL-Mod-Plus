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
	private final Map<String, Profile> profilesByName = new HashMap<>();
	private SharedPreferences preferences;
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
				editProfileLauncher.launch(name);
			}

			@Override
			public void onSetBuiltInDefault() {
				preferences.edit().remove(PREF_DEFAULT_PROFILE).apply();
				refreshProfiles();
			}

			@Override
			public void onSetDefault(@NonNull String name) {
				Profile profile = profilesByName.get(name);
				if (profile != null && ProfilesManager.inspectProfile(profile).settings.isReady()) {
					preferences.edit().putString(PREF_DEFAULT_PROFILE, name).apply();
					refreshProfiles();
				}
			}

			@Override
			public void onEdit(@NonNull String name) {
				Profile profile = profilesByName.get(name);
				if (profile != null && ProfilesManager.inspectProfile(profile).settings.isReady()) {
					editProfileLauncher.launch(name);
				}
			}

			@Override
			public void onRename(@NonNull String oldName, @NonNull String newName) {
				Profile profile = profilesByName.get(oldName);
				if (profile == null || !Profile.isValidName(newName)
						|| ProfilesManager.profileNameExists(newName)) {
					return;
				}
				if (!profile.renameTo(newName)) {
					ThemedToast.show(ProfilesActivity.this,
							R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
					return;
				}
				if (oldName.equals(preferences.getString(PREF_DEFAULT_PROFILE, null))) {
					preferences.edit().putString(PREF_DEFAULT_PROFILE, newName).apply();
				}
				refreshProfiles();
			}

			@Override
			public void onDelete(@NonNull String name) {
				Profile profile = profilesByName.get(name);
				if (profile != null) {
					if (!profile.delete()) {
						ThemedToast.show(ProfilesActivity.this,
								R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
						return;
					}
					if (name.equals(preferences.getString(PREF_DEFAULT_PROFILE, null))) {
						preferences.edit().remove(PREF_DEFAULT_PROFILE).apply();
					}
					refreshProfiles();
				}
			}
		};
	}

	private void refreshProfiles() {
		final int generation = ++refreshGeneration;
		final String defaultName = preferences.getString(PREF_DEFAULT_PROFILE, null);
		profileExecutor.execute(() -> {
			ArrayList<Profile> profiles = ProfilesManager.getProfiles();
			Collections.sort(profiles);
			ArrayList<ProfilesManager.ProfileInfo> inspected = ProfilesManager.inspectProfiles(profiles);
			boolean hasValidDefault = false;
			String legacyKeyboardDefaultName = null;
			for (ProfilesManager.ProfileInfo info : inspected) {
				if (info.settings.isReady() && defaultName != null
						&& defaultName.equals(info.profile.getName())) {
					hasValidDefault = true;
				} else if (legacyKeyboardDefaultName == null
						&& info.settings.status == ProfilesManager.CapabilityStatus.ABSENT
						&& info.keyboardLayout.isReady()
						&& defaultName != null && defaultName.equals(info.profile.getName())) {
					legacyKeyboardDefaultName = info.profile.getName();
				}
			}
			ArrayList<ProfileUiItem> items = new ArrayList<>(inspected.size() + 1);
			items.add(new ProfileUiItem(
					"", !hasValidDefault, false, true, false, false, 0, 0, 0, false, false));
			for (ProfilesManager.ProfileInfo info : inspected) {
				boolean valid = info.settings.isReady();
				boolean keyboardOnly = info.settings.status == ProfilesManager.CapabilityStatus.ABSENT
						&& info.keyboardLayout.isReady();
				boolean unavailable = !valid && !keyboardOnly;
				items.add(new ProfileUiItem(
						info.profile.getName(),
						valid && info.profile.getName().equals(defaultName),
						valid,
						false,
						keyboardOnly,
						info.keyboardLayout.isReady(),
						info.config == null ? 0 : info.config.screenWidth,
						info.config == null ? 0 : info.config.screenHeight,
						info.config == null ? 0 : info.config.orientation,
						unavailable,
						info.keyboardLayout.status == ProfilesManager.CapabilityStatus.UNAVAILABLE));
			}
			final String resolvedLegacyKeyboardDefaultName = legacyKeyboardDefaultName;
			runOnUiThread(() -> {
				if (generation != refreshGeneration || isFinishing() || isDestroyed()) return;
				profilesByName.clear();
				for (Profile profile : profiles) profilesByName.put(profile.getName(), profile);
				composeController.updateProfileItems(items, resolvedLegacyKeyboardDefaultName);
			});
		});
	}
}
