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
import static ru.playsoftware.j2meloader.util.Constants.PREF_ACCENT;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_ENHANCED_ICONS;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_GRID_SPACING;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_HIDE_GRID_TITLES;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_ICON_RATIO;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_ICON_SHAPE;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_SHOW_LIST_DESCRIPTION;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_VIEW;
import static ru.playsoftware.j2meloader.util.Constants.PREF_KEEP_SCREEN;
import static ru.playsoftware.j2meloader.util.Constants.PREF_SCREENSHOT_SWITCH;
import static ru.playsoftware.j2meloader.util.Constants.PREF_STATUSBAR;
import static ru.playsoftware.j2meloader.util.Constants.PREF_THEME;
import static ru.playsoftware.j2meloader.util.Constants.PREF_TOOLBAR;
import static ru.playsoftware.j2meloader.util.Constants.PREF_USE_DISPLAY_CUTOUT;
import static ru.playsoftware.j2meloader.util.Constants.PREF_VIBRATION;
import static ru.playsoftware.j2meloader.util.Constants.LIBRARY_GRID_SPACING_COMPACT;
import static ru.playsoftware.j2meloader.util.Constants.LIBRARY_GRID_SPACING_NONE;
import static ru.playsoftware.j2meloader.util.Constants.LIBRARY_GRID_SPACING_SPACIOUS;
import static ru.playsoftware.j2meloader.util.Constants.LIBRARY_GRID_SPACING_STANDARD;
import static ru.playsoftware.j2meloader.util.Constants.LIBRARY_ICON_RATIO_PORTRAIT;
import static ru.playsoftware.j2meloader.util.Constants.LIBRARY_ICON_RATIO_SQUARE;
import static ru.playsoftware.j2meloader.util.Constants.LIBRARY_ICON_SHAPE_ROUND;
import static ru.playsoftware.j2meloader.util.Constants.LIBRARY_ICON_SHAPE_SQUARE;
import static ru.playsoftware.j2meloader.util.Constants.LIBRARY_LAYOUT_GRID;
import static ru.playsoftware.j2meloader.util.Constants.LIBRARY_LAYOUT_LIST;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Build;
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

import javax.microedition.util.ContextHolder;

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
		normalizeChromePreferences();

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
			public void onAccentChanged(@NonNull String value) {
				preferences.edit().putString(PREF_ACCENT, value).apply();
				refreshState();
			}

			@Override
			public void onLanguageChanged(@NonNull String value) {
				AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value));
				refreshState();
			}

			@Override
			public void onLibraryChoiceChanged(@NonNull String key, @NonNull String value) {
				SharedPreferences.Editor editor = preferences.edit();
				switch (key) {
					case PREF_APPS_VIEW:
						editor.putInt(PREF_APPS_VIEW,
								"grid".equals(value) ? LIBRARY_LAYOUT_GRID : LIBRARY_LAYOUT_LIST);
						break;
					case PREF_APPS_ICON_RATIO:
						editor.putInt(PREF_APPS_ICON_RATIO,
								"portrait".equals(value)
										? LIBRARY_ICON_RATIO_PORTRAIT : LIBRARY_ICON_RATIO_SQUARE);
						break;
					case PREF_APPS_ICON_SHAPE:
						editor.putInt(PREF_APPS_ICON_SHAPE,
								"square".equals(value)
										? LIBRARY_ICON_SHAPE_SQUARE : LIBRARY_ICON_SHAPE_ROUND);
						break;
					case PREF_APPS_GRID_SPACING:
						editor.putInt(PREF_APPS_GRID_SPACING, gridSpacingValue(value));
						break;
					default:
						return;
				}
				editor.apply();
				refreshState();
			}

			@Override
			public void onToggle(@NonNull String key, boolean checked) {
				SharedPreferences.Editor editor = preferences.edit().putBoolean(key, checked);
				if (PREF_STATUSBAR.equals(key) && checked) {
					editor.putBoolean(PREF_USE_DISPLAY_CUTOUT, false);
				} else if (PREF_USE_DISPLAY_CUTOUT.equals(key) && checked) {
					editor.putBoolean(PREF_STATUSBAR, false);
				}
				editor.apply();
				if (PREF_VIBRATION.equals(key)) {
					ContextHolder.setVibration(checked);
				}
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
		normalizeChromePreferences();
		List<SettingsOption> themes = buildThemeOptions();
		String themeValue = preferences.getString(PREF_THEME, getString(R.string.pref_theme_default));
		SettingsOption selectedTheme = findOption(themes, themeValue, themes.get(0));
		List<SettingsOption> accents = buildAccentOptions();
		String accentValue = preferences.getString(PREF_ACCENT, "blue");
		SettingsOption selectedAccent = findOption(accents, accentValue, accents.get(0));

		List<SettingsOption> languages = buildLanguageOptions();
		Locale locale = AppCompatDelegate.getApplicationLocales().get(0);
		String languageValue = locale == null ? "" : locale.toLanguageTag();
		SettingsOption selectedLanguage = findOption(languages, languageValue, null);
		if (selectedLanguage == null && locale != null) {
			// Keep compatibility with older entries that stored only the language subtag.
			selectedLanguage = findOption(languages, locale.getLanguage(), null);
		}
		if (selectedLanguage == null) {
			selectedLanguage = languages.get(0);
		}

		List<SettingsSwitch> switches = new ArrayList<>();
		switches.add(new SettingsSwitch(
				PREF_TOOLBAR,
				getString(R.string.pref_enable_actionbar_title),
				getString(R.string.pref_enable_actionbar_summary),
				preferences.getBoolean(PREF_TOOLBAR, false)));
		boolean statusBarEnabled = preferences.getBoolean(PREF_STATUSBAR, false);
		boolean displayCutoutEnabled = preferences.getBoolean(PREF_USE_DISPLAY_CUTOUT, true);
		switches.add(new SettingsSwitch(
				PREF_STATUSBAR,
				getString(R.string.pref_enable_statusbar_title),
				getString(R.string.pref_enable_statusbar_summary),
				statusBarEnabled,
				true));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			switches.add(new SettingsSwitch(
					PREF_USE_DISPLAY_CUTOUT,
					getString(R.string.pref_use_display_cutout_title),
					getString(R.string.pref_use_display_cutout_summary),
					displayCutoutEnabled,
					true));
		}
		switches.add(new SettingsSwitch(
				PREF_KEEP_SCREEN,
				getString(R.string.pref_wakelock_title),
				null,
				preferences.getBoolean(PREF_KEEP_SCREEN, false)));
		switches.add(new SettingsSwitch(
				PREF_SCREENSHOT_SWITCH,
				getString(R.string.pref_screenshot_title),
				getString(R.string.pref_screenshot_summary),
				preferences.getBoolean(PREF_SCREENSHOT_SWITCH, false)));
		switches.add(new SettingsSwitch(
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
				directoryError,
				selectedAccent,
				accents,
				buildLibraryChoices(),
				buildLibrarySwitches());
	}

	private List<SettingsChoice> buildLibraryChoices() {
		List<SettingsChoice> choices = new ArrayList<>();
		boolean gridView = preferences.getInt(PREF_APPS_VIEW, LIBRARY_LAYOUT_LIST)
				== LIBRARY_LAYOUT_GRID;
		List<SettingsOption> viewOptions = Arrays.asList(
				new SettingsOption("list", getString(R.string.library_view_list)),
				new SettingsOption("grid", getString(R.string.library_view_grid)));
		choices.add(new SettingsChoice(
				PREF_APPS_VIEW,
				getString(R.string.pref_apps_view),
				findOption(viewOptions,
						preferences.getInt(PREF_APPS_VIEW, LIBRARY_LAYOUT_LIST) == LIBRARY_LAYOUT_GRID
								? "grid" : "list",
						viewOptions.get(0)),
				viewOptions));

		List<SettingsOption> ratioOptions = Arrays.asList(
				new SettingsOption("square", getString(R.string.library_icon_ratio_square)),
				new SettingsOption("portrait", getString(R.string.library_icon_ratio_portrait)));
		choices.add(new SettingsChoice(
				PREF_APPS_ICON_RATIO,
				getString(R.string.library_icon_ratio_title),
				findOption(ratioOptions,
						preferences.getInt(PREF_APPS_ICON_RATIO, LIBRARY_ICON_RATIO_SQUARE)
								== LIBRARY_ICON_RATIO_PORTRAIT ? "portrait" : "square",
						ratioOptions.get(0)),
				ratioOptions));

		List<SettingsOption> shapeOptions = Arrays.asList(
				new SettingsOption("round", getString(R.string.library_icon_shape_round)),
				new SettingsOption("square", getString(R.string.library_icon_shape_square)));
		choices.add(new SettingsChoice(
				PREF_APPS_ICON_SHAPE,
				getString(R.string.library_icon_shape_title),
				findOption(shapeOptions,
						preferences.getInt(PREF_APPS_ICON_SHAPE, LIBRARY_ICON_SHAPE_ROUND)
								== LIBRARY_ICON_SHAPE_SQUARE ? "square" : "round",
						shapeOptions.get(0)),
				shapeOptions));

		if (gridView) {
			List<SettingsOption> spacingOptions = Arrays.asList(
					new SettingsOption("none", getString(R.string.library_grid_spacing_none)),
					new SettingsOption("compact", getString(R.string.library_grid_spacing_compact)),
					new SettingsOption("standard", getString(R.string.library_grid_spacing_standard)),
					new SettingsOption("spacious", getString(R.string.library_grid_spacing_spacious)));
			choices.add(new SettingsChoice(
					PREF_APPS_GRID_SPACING,
					getString(R.string.library_grid_spacing_title),
					findOption(spacingOptions,
							gridSpacingKey(preferences.getInt(
									PREF_APPS_GRID_SPACING, LIBRARY_GRID_SPACING_STANDARD)),
							spacingOptions.get(2)),
					spacingOptions));
		}
		return choices;
	}

	private List<SettingsSwitch> buildLibrarySwitches() {
		boolean gridView = preferences.getInt(PREF_APPS_VIEW, LIBRARY_LAYOUT_LIST)
				== LIBRARY_LAYOUT_GRID;
		List<SettingsSwitch> switches = new ArrayList<>();
		switches.add(new SettingsSwitch(
				PREF_APPS_ENHANCED_ICONS,
				getString(R.string.library_enhanced_icons_title),
				getString(R.string.library_enhanced_icons_summary),
				preferences.getBoolean(PREF_APPS_ENHANCED_ICONS, true)));
		if (gridView) {
			switches.add(new SettingsSwitch(
					PREF_APPS_HIDE_GRID_TITLES,
					getString(R.string.library_hide_grid_titles),
					getString(R.string.library_hide_grid_titles_summary),
					preferences.getBoolean(PREF_APPS_HIDE_GRID_TITLES, false)));
		} else {
			switches.add(new SettingsSwitch(
					PREF_APPS_SHOW_LIST_DESCRIPTION,
					getString(R.string.library_show_list_description),
					getString(R.string.library_show_list_description_summary),
					preferences.getBoolean(PREF_APPS_SHOW_LIST_DESCRIPTION, true)));
		}
		return switches;
	}

	private static int gridSpacingValue(String value) {
		switch (value) {
			case "none":
				return LIBRARY_GRID_SPACING_NONE;
			case "compact":
				return LIBRARY_GRID_SPACING_COMPACT;
			case "spacious":
				return LIBRARY_GRID_SPACING_SPACIOUS;
			case "standard":
			default:
				return LIBRARY_GRID_SPACING_STANDARD;
		}
	}

	private static String gridSpacingKey(int value) {
		switch (value) {
			case LIBRARY_GRID_SPACING_NONE:
				return "none";
			case LIBRARY_GRID_SPACING_COMPACT:
				return "compact";
			case LIBRARY_GRID_SPACING_SPACIOUS:
				return "spacious";
			case LIBRARY_GRID_SPACING_STANDARD:
			default:
				return "standard";
		}
	}

	/** Repairs combinations persisted by versions that did not enforce the chrome interlock. */
	private void normalizeChromePreferences() {
		if (preferences == null) return;
		boolean statusBarEnabled = preferences.getBoolean(PREF_STATUSBAR, false);
		boolean displayCutoutEnabled = preferences.getBoolean(PREF_USE_DISPLAY_CUTOUT, true);
		if (statusBarEnabled && displayCutoutEnabled) {
			preferences.edit().putBoolean(PREF_USE_DISPLAY_CUTOUT, false).apply();
		}
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

	private List<SettingsOption> buildAccentOptions() {
		String[] values = getResources().getStringArray(R.array.pref_accent_values);
		String[] labels = getResources().getStringArray(R.array.pref_accent_entries);
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
