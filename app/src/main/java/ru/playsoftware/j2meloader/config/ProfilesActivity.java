/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2019-2023 Yury Kharchenko
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

package ru.playsoftware.j2meloader.config;

import static ru.playsoftware.j2meloader.util.Constants.ACTION_EDIT_PROFILE;
import static ru.playsoftware.j2meloader.util.Constants.PREF_DEFAULT_PROFILE;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;

public class ProfilesActivity extends AppCompatActivity {
	private final Map<String, Profile> profilesByName = new HashMap<>();
	private SharedPreferences preferences;
	private ProfilesComposeController composeController;

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
		EdgeToEdgeCompat.enableIfSupported(this);
		ComposeView composeView = new ComposeView(this);
		setContentView(composeView);
		EdgeToEdgeCompat.protectHostContent(this);
		preferences = PreferenceManager.getDefaultSharedPreferences(this);
		composeController = new ProfilesComposeController(composeView, createActions());
		refreshProfiles();
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
				if (profilesByName.containsKey(name)) {
					preferences.edit().putString(PREF_DEFAULT_PROFILE, name).apply();
					refreshProfiles();
				}
			}

			@Override
			public void onEdit(@NonNull String name) {
				Profile profile = profilesByName.get(name);
				if (profile != null && (profile.hasConfig() || profile.hasOldConfig())) {
					Intent intent = new Intent(ACTION_EDIT_PROFILE, Uri.parse(name),
							getApplicationContext(), ConfigActivity.class);
					startActivity(intent);
				}
			}

			@Override
			public void onRename(@NonNull String oldName, @NonNull String newName) {
				Profile profile = profilesByName.get(oldName);
				if (profile == null) {
					return;
				}
				profile.renameTo(newName);
				if (oldName.equals(preferences.getString(PREF_DEFAULT_PROFILE, null))) {
					preferences.edit().putString(PREF_DEFAULT_PROFILE, newName).apply();
				}
				refreshProfiles();
			}

			@Override
			public void onDelete(@NonNull String name) {
				Profile profile = profilesByName.get(name);
				if (profile != null) {
					profile.delete();
					if (name.equals(preferences.getString(PREF_DEFAULT_PROFILE, null))) {
						preferences.edit().remove(PREF_DEFAULT_PROFILE).apply();
					}
					refreshProfiles();
				}
			}
		};
	}

	private void refreshProfiles() {
		ArrayList<Profile> profiles = ProfilesManager.getProfiles();
		profilesByName.clear();
		for (Profile profile : profiles) {
			profilesByName.put(profile.getName(), profile);
		}
		composeController.updateProfiles(
				profiles,
				preferences.getString(PREF_DEFAULT_PROFILE, null));
	}
}
