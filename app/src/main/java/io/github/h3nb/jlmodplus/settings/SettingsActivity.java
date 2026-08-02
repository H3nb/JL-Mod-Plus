/*
 * Copyright 2017 Nikita Shakarun
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

package io.github.h3nb.jlmodplus.settings;

import static io.github.h3nb.jlmodplus.util.Constants.PREF_EMULATOR_DIR;

import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import io.github.h3nb.jlmodplus.config.ProfilesActivity;
import io.github.h3nb.jlmodplus.util.FileUtils;
import io.github.h3nb.jlmodplus.util.PickDirResultContract;

import java.io.File;

public class SettingsActivity extends AppCompatActivity {
	private SettingsComposeView composeView;
	private final ActivityResultLauncher<String> openDirLauncher = registerForActivityResult(
			new PickDirResultContract(),
			this::onPickDirResult);

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		composeView = new SettingsComposeView(this, new SettingsComposeView.Callback() {
			@Override
			public void onBack() {
				finish();
			}

			@Override
			public void onProfiles() {
				startActivity(new Intent(SettingsActivity.this, ProfilesActivity.class));
			}

			@Override
			public void onChooseDirectory() {
				openDirLauncher.launch(null);
			}
		});
		setContentView(composeView);
		if (getSupportActionBar() != null) {
			getSupportActionBar().hide();
		}
	}

	private void onPickDirResult(Uri uri) {
		if (uri == null || uri.getPath() == null) {
			return;
		}
		File file = new File(uri.getPath());
		String path = file.getAbsolutePath();
		if (!FileUtils.initWorkDir(file)) {
			composeView.showDirectoryError(path);
			return;
		}
		PreferenceManager.getDefaultSharedPreferences(this)
				.edit()
				.putString(PREF_EMULATOR_DIR, path)
				.apply();
		composeView.setDirectory(path);
	}
}
