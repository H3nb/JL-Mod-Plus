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

package io.github.h3nb.jlmodplus.config;

import static io.github.h3nb.jlmodplus.util.Constants.ACTION_EDIT_PROFILE;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.ui.ComposeDialogHost;
import io.github.h3nb.jlmodplus.ui.WindowInsetsPolicy;

public class ProfilesActivity extends AppCompatActivity implements ProfilesComposeView.Callback {
	private ProfilesComposeView composeView;
	private ArrayList<Profile> profiles;
	private Profile defaultProfile;
	private SharedPreferences preferences;
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
					composeView.addProfile(new Profile(name));
				}
			});

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		WindowInsetsPolicy.enableEdgeToEdge(getWindow());
		composeView = new ProfilesComposeView(this, this);
		setContentView(composeView);
		if (getSupportActionBar() != null) {
			getSupportActionBar().hide();
		}
		setTitle(R.string.profiles);

		preferences = PreferenceManager.getDefaultSharedPreferences(this);
		profiles = ProfilesManager.getProfiles();
		final String def = preferences.getString(PREF_DEFAULT_PROFILE, null);
		if (def != null) {
			for (int i = profiles.size() - 1; i >= 0; i--) {
				Profile profile = profiles.get(i);
				if (profile.getName().equals(def)) {
					defaultProfile = profile;
					break;
				}
			}
		}
		composeView.setProfiles(profiles, defaultProfile == null ? null : defaultProfile.getName());
	}

	@Override
	public void onBack() {
		finish();
	}

	@Override
	public void onAdd() {
		showProfileNameDialog(getString(R.string.enter_name), null, -1);
	}

	@Override
	public void onProfileAction(Profile profile, int itemId) {
		if (itemId == R.id.action_context_default) {
			preferences.edit().putString(PREF_DEFAULT_PROFILE, profile.getName()).apply();
			defaultProfile = profile;
			composeView.setDefault(profile);
		} else if (itemId == R.id.action_context_edit) {
			final Intent intent = new Intent(ACTION_EDIT_PROFILE,
					Uri.parse(profile.getName()),
					getApplicationContext(), ConfigActivity.class);
			startActivity(intent);
		} else if (itemId == R.id.action_context_rename) {
			showProfileNameDialog(
					getString(R.string.enter_new_name),
					profile.getName(),
					profiles.indexOf(profile)
			);
		} else if (itemId == R.id.action_context_delete) {
			profile.delete();
			composeView.removeProfile(profile);
		}
	}

	private void showProfileNameDialog(String title, String initialName, int id) {
		ComposeDialogHost.showTextInput(
				this,
				title,
				getString(R.string.enter_name),
				initialName == null ? "" : initialName,
				getString(android.R.string.ok),
				getString(android.R.string.cancel),
				false,
				value -> {
					String newName = value.trim();
					if (newName.isEmpty()) {
						Toast.makeText(this, R.string.error_name, Toast.LENGTH_SHORT).show();
						return false;
					}
					if (newName.equals(initialName)
							|| new File(Config.getProfilesDir(), newName).exists()) {
						Toast toast = Toast.makeText(this, R.string.not_saved_exists, Toast.LENGTH_SHORT);
						toast.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 50);
						toast.show();
						return false;
					}
					if (id == -1) {
						editProfileLauncher.launch(newName);
					} else {
						Profile profile = profiles.get(id);
						profile.renameTo(newName);
						composeView.refresh();
						if (defaultProfile == profile) {
							preferences.edit().putString(PREF_DEFAULT_PROFILE, newName).apply();
							composeView.setDefault(profile);
						}
					}
					return true;
				}
		);
	}
}
