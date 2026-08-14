/*
 * Copyright 2017 Nikita Shakarun
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

package ru.playsoftware.j2meloader.settings;

import static ru.playsoftware.j2meloader.util.Constants.PREF_EMULATOR_DIR;
import static ru.playsoftware.j2meloader.util.Constants.PREF_KEEP_SCREEN;
import static ru.playsoftware.j2meloader.util.Constants.PREF_SCREENSHOT_SWITCH;
import static ru.playsoftware.j2meloader.util.Constants.PREF_STATUSBAR;
import static ru.playsoftware.j2meloader.util.Constants.PREF_THEME;
import static ru.playsoftware.j2meloader.util.Constants.PREF_TOOLBAR;
import static ru.playsoftware.j2meloader.util.Constants.PREF_VIBRATION;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ProfilesActivity;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.PickDirResultContract;
import ru.playsoftware.j2meloader.util.XmlUtils;

public class SettingsActivity extends AppCompatActivity {
	private SharedPreferences preferences;
	private SettingsComposeController composeController;
	private String directoryError;

	private final ActivityResultLauncher<String> openDirLauncher = registerForActivityResult(
			new PickDirResultContract(),
			this::onPickDirResult);

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableIfSupported(this);
		preferences = PreferenceManager.getDefaultSharedPreferences(this);

		ComposeView composeView = new ComposeView(this);
		setContentView(composeView);
		if (getSupportActionBar() != null) {
			// The Material 3 TopAppBar is the single host toolbar for this migrated screen.
			getSupportActionBar().hide();
		}
		EdgeToEdgeCompat.protectHostContent(this);
		composeController = new SettingsComposeController(
				composeView,
				buildState(),
				createActions());
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}

	private SettingsActions createActions() {
		return new SettingsActions() {
			@Override
			public void onBack() {
				finish();
			}

			@Override
			public void onThemeChanged(@NonNull String value) {
				preferences.edit().putString(PREF_THEME, value).apply();
				refreshState();
			}

			@Override
			public void onLanguageChanged(@NonNull String value) {
				AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value));
				refreshState();
			}

			@Override
			public void onToggle(@NonNull String key, boolean checked) {
				preferences.edit().putBoolean(key, checked).apply();
				refreshState();
			}

			@Override
			public void onOpenProfiles() {
				startActivity(new Intent(SettingsActivity.this, ProfilesActivity.class));
			}

			@Override
			public void onChooseDirectory() {
				openDirLauncher.launch(null);
			}

			@Override
			public void onDismissDirectoryError() {
				directoryError = null;
				refreshState();
			}
		};
	}

	private void refreshState() {
		if (composeController != null) {
			composeController.update(buildState());
		}
	}

	private SettingsUiState buildState() {
		List<SettingsOption> themes = buildThemeOptions();
		String themeValue = preferences.getString(PREF_THEME, getString(R.string.pref_theme_default));
		SettingsOption selectedTheme = findOption(themes, themeValue, themes.get(0));

		List<SettingsOption> languages = buildLanguageOptions();
		Locale locale = AppCompatDelegate.getApplicationLocales().get(0);
		String languageValue = locale == null ? "" : locale.getLanguage();
		SettingsOption selectedLanguage = findOption(languages, languageValue, languages.get(0));

		List<SettingsSwitch> switches = Arrays.asList(
				new SettingsSwitch(
						PREF_TOOLBAR,
						getString(R.string.pref_enable_actionbar_title),
						getString(R.string.pref_enable_actionbar_summary),
						preferences.getBoolean(PREF_TOOLBAR, false)),
				new SettingsSwitch(
						PREF_STATUSBAR,
						getString(R.string.pref_enable_statusbar_title),
						getString(R.string.pref_enable_actionbar_summary),
						preferences.getBoolean(PREF_STATUSBAR, false)),
				new SettingsSwitch(
						PREF_KEEP_SCREEN,
						getString(R.string.pref_wakelock_title),
						null,
						preferences.getBoolean(PREF_KEEP_SCREEN, false)),
				new SettingsSwitch(
						PREF_SCREENSHOT_SWITCH,
						getString(R.string.pref_screenshot_title),
						getString(R.string.pref_screenshot_summary),
						preferences.getBoolean(PREF_SCREENSHOT_SWITCH, false)),
				new SettingsSwitch(
						PREF_VIBRATION,
						getString(R.string.pref_vibration_title),
						null,
						preferences.getBoolean(PREF_VIBRATION, true)));
		List<SettingsSwitch> experimentalSwitches = Arrays.asList(
				new SettingsSwitch(
						"micro3d_using_message",
						getString(R.string.pref_mascot_title),
						getString(R.string.pref_mascot_summary),
						preferences.getBoolean("micro3d_using_message", false)));

		return new SettingsUiState(
				selectedTheme,
				themes,
				selectedLanguage,
				languages,
				switches,
				experimentalSwitches,
				true,
				Config.getEmulatorDir(),
				directoryError);
	}

	private List<SettingsOption> buildThemeOptions() {
		String[] values = getResources().getStringArray(R.array.pref_theme_values);
		String[] labels = getResources().getStringArray(R.array.pref_theme_entries);
		List<SettingsOption> options = new ArrayList<>(Math.min(values.length, labels.length));
		for (int i = 0; i < Math.min(values.length, labels.length); i++) {
			options.add(new SettingsOption(values[i], labels[i]));
		}
		return options;
	}

	@SuppressLint("DiscouragedApi")
	private List<SettingsOption> buildLanguageOptions() {
		List<String> tags = new ArrayList<>();
		Resources resources = getResources();
		int id = resources.getIdentifier(
				"_generated_res_locale_config", "xml", getPackageName());
		if (id != 0) {
			try (XmlResourceParser parser = resources.getXml(id)) {
				while (XmlUtils.nextElement(parser, "locale")) {
					String tag = parser.getAttributeValue(0);
					if (tag != null && !tag.isEmpty()) {
						tags.add(tag);
					}
				}
			} catch (Exception ignored) {
				// Keep the system-language option available when locale metadata is unavailable.
			}
		}

		List<SettingsOption> options = new ArrayList<>(tags.size() + 1);
		options.add(new SettingsOption("", getString(R.string.pref_theme_system)));
		for (String tag : tags) {
			Locale locale = Locale.forLanguageTag(tag);
			String label = locale.getDisplayName(locale);
			options.add(new SettingsOption(tag, label));
		}
		return options;
	}

	private static SettingsOption findOption(
			List<SettingsOption> options,
			String value,
			SettingsOption fallback) {
		for (SettingsOption option : options) {
			if (option.getValue().equals(value)) {
				return option;
			}
		}
		return fallback;
	}

	private void onPickDirResult(Uri uri) {
		if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) {
			return;
		}
		File file = new File(uri.getPath());
		String path = file.getAbsolutePath();
		if (!FileUtils.initWorkDir(file)) {
			directoryError = getString(R.string.create_apps_dir_failed, path);
			refreshState();
			return;
		}
		directoryError = null;
		preferences.edit().putString(PREF_EMULATOR_DIR, path).apply();
		refreshState();
	}
}
