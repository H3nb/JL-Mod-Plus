/*
 * Copyright 2017 Nikita Shakarun
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

package io.github.h3nb.jlmodplus.settings;

import static io.github.h3nb.jlmodplus.util.Constants.PREF_EMULATOR_DIR;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_ACCENT;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_APPS_ENHANCED_ICONS;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_APPS_GRID_SPACING;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_APPS_GRID_ICON_SIZE;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_APPS_HIDE_GRID_TITLES;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_APPS_ICON_RATIO;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_APPS_ICON_SHAPE;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_KEEP_SCREEN;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_SCREENSHOT_SWITCH;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_THEME;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_TOOLBAR;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_STATUSBAR;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_VIBRATION;
import static io.github.h3nb.jlmodplus.util.Constants.LIBRARY_GRID_SPACING_STANDARD;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

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

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.ui.AppPageTransitions;
import io.github.h3nb.jlmodplus.ui.AccentPalette;
import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.applist.LibraryGridSpacing;
import io.github.h3nb.jlmodplus.applist.LibraryIconRatio;
import io.github.h3nb.jlmodplus.applist.LibraryIconShape;
import io.github.h3nb.jlmodplus.applist.LibraryGridIconSize;
import io.github.h3nb.jlmodplus.config.ProfilesActivity;
import io.github.h3nb.jlmodplus.crashes.CrashReportsActivity;
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat;
import io.github.h3nb.jlmodplus.util.FileUtils;
import io.github.h3nb.jlmodplus.util.PickDirResultContract;
import io.github.h3nb.jlmodplus.util.XmlUtils;

public class SettingsActivity extends AppCompatActivity {
	public static final String EXTRA_OPEN_UPDATES = "io.github.h3nb.jlmodplus.settings.OPEN_UPDATES";

	@NonNull
	public static Intent updatesIntent(@NonNull Context context) {
		return new Intent(context, SettingsActivity.class).putExtra(EXTRA_OPEN_UPDATES, true);
	}

	private SharedPreferences preferences;
	private SettingsComposeController composeController;
	private String directoryError;
	private RuntimeChromeClient chromeClient;
	private boolean fullscreen;
	private boolean showStatusBar;

	private final ActivityResultLauncher<String> openDirLauncher = registerForActivityResult(
			new PickDirResultContract(),
			this::onPickDirResult);

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		AppPageTransitions.install(this);
		AppLocaleTags.migrateRetiredSantaliLocale();
		EdgeToEdgeCompat.enableForComposeSurface(this);
		preferences = PreferenceManager.getDefaultSharedPreferences(this);
		fullscreen = !preferences.getBoolean(PREF_TOOLBAR, false);
		showStatusBar = preferences.getBoolean(PREF_STATUSBAR, false);

		ComposeView composeView = new ComposeView(this);
		setContentView(composeView);
		composeController = new SettingsComposeController(
				composeView,
				buildState(),
				createActions(),
				getIntent().getBooleanExtra(EXTRA_OPEN_UPDATES, false));
		chromeClient = new RuntimeChromeClient(this, new RuntimeChromeClient.Listener() {
			@Override
			public void onChromeChanged(@NonNull RuntimeChromeState state) {
				fullscreen = state.getFullscreen();
				showStatusBar = state.getShowStatusBar();
				refreshState();
			}

			@Override
			public void onChromeError(@NonNull Exception failure) {
				Log.e("SettingsActivity", "Could not access runtime chrome preferences", failure);
				composeController.showNotice(getString(R.string.runtime_chrome_update_failed));
			}
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (chromeClient != null) chromeClient.start();
		refreshState();
	}

	@Override
	protected void onPause() {
		if (chromeClient != null) chromeClient.close();
		super.onPause();
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
					case PREF_APPS_ICON_RATIO:
						editor.putInt(PREF_APPS_ICON_RATIO,
								LibraryIconRatio.fromPreferenceKey(value).getStoredValue());
						break;
					case PREF_APPS_ICON_SHAPE:
						editor.putInt(PREF_APPS_ICON_SHAPE,
								LibraryIconShape.fromPreferenceKey(value).getStoredValue());
						break;
					case PREF_APPS_GRID_ICON_SIZE:
						editor.putInt(PREF_APPS_GRID_ICON_SIZE,
								LibraryGridIconSize.fromPreferenceKey(value).getStoredValue());
						break;
					case PREF_APPS_GRID_SPACING:
						editor.putInt(PREF_APPS_GRID_SPACING,
								LibraryGridSpacing.fromPreferenceKey(value).getStoredValue());
						break;
					default:
						return;
				}
				editor.apply();
				refreshState();
			}

			@Override
			public void onToggle(@NonNull String key, boolean checked) {
				if (PREF_TOOLBAR.equals(key)) {
					chromeClient.setFullscreen(checked);
					return;
				}
				if (PREF_STATUSBAR.equals(key)) {
					chromeClient.setShowStatusBar(checked);
					return;
				}
				preferences.edit().putBoolean(key, checked).apply();
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

			@Override
			public void onOpenCrashReports() {
				startActivity(new Intent(SettingsActivity.this, CrashReportsActivity.class));
			}

			@Override
			public void onOpenDonation() {
				openExternalPage("https://sociabuzz.com/h3nb/tribe", "Sociabuzz",
						R.string.library_donation_link_copied,
						R.string.library_donation_browser_unavailable);
			}

			@Override
			public void onOpenProjectRepository() {
				openExternalPage("https://github.com/H3nb/JL-Mod-Plus", "GitHub",
						R.string.about_github_link_copied, R.string.about_github_browser_unavailable);
			}

			@Override
			public void onOpenUpdateRelease(@NonNull String url) {
				if (url.startsWith("https://github.com/H3nb/JL-Mod-Plus/releases/tag/v")) {
					openExternalPage(url, "GitHub Releases",
							R.string.updates_link_copied, R.string.updates_browser_unavailable);
				}
			}

			@Override
			public void onOpenUpdateDownload(@NonNull String url) {
				if (url.startsWith("https://github.com/H3nb/JL-Mod-Plus/releases/download/v")
						&& url.endsWith(".apk")) {
					openExternalPage(url, "GitHub Releases",
							R.string.updates_link_copied, R.string.updates_browser_unavailable);
				}
			}
		};
	}

	private void openExternalPage(
			@NonNull String url, @NonNull String label, int copiedMessage, int unavailableMessage) {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
					.addCategory(Intent.CATEGORY_BROWSABLE));
		} catch (ActivityNotFoundException | SecurityException exception) {
			boolean copied = false;
			try {
				ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
				if (clipboard != null) {
					clipboard.setPrimaryClip(ClipData.newPlainText(label, url));
					copied = true;
				}
			} catch (SecurityException ignored) {
				// Fall back to the unavailable-browser message.
			}
			Toast.makeText(this, copied ? copiedMessage : unavailableMessage, Toast.LENGTH_LONG).show();
		}
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
		List<SettingsOption> accents = buildAccentOptions();
		String accentValue = AccentPalette.readPreference(preferences).getKey();
		SettingsOption selectedAccent = findOption(accents, accentValue, accents.get(0));

		List<SettingsOption> languages = buildLanguageOptions();
		Locale locale = AppCompatDelegate.getApplicationLocales().get(0);
		String languageValue = locale == null
				? ""
				: AppLocaleTags.canonicalize(locale.toLanguageTag());
		SettingsOption selectedLanguage = findOption(languages, languageValue, null);
		if (selectedLanguage == null && locale != null) {
			// Keep compatibility with older entries that stored only the language subtag.
			selectedLanguage = findOption(
					languages, AppLocaleTags.canonicalize(locale.getLanguage()), null);
		}
		if (selectedLanguage == null) {
			selectedLanguage = languages.get(0);
		}

		List<SettingsSwitch> switches = new ArrayList<>();
		switches.add(new SettingsSwitch(
				PREF_TOOLBAR,
				getString(R.string.pref_runtime_fullscreen_title),
				getString(R.string.pref_runtime_fullscreen_summary),
				fullscreen));
		switches.add(new SettingsSwitch(
				PREF_STATUSBAR,
				getString(R.string.pref_show_status_bar_title),
				getString(R.string.pref_show_status_bar_summary),
				showStatusBar));
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

		List<SettingsSwitch> diagnosticSwitches = Arrays.asList(
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
				diagnosticSwitches,
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
		List<SettingsOption> ratioOptions = Arrays.asList(
				new SettingsOption("square", getString(R.string.library_icon_ratio_square)),
				new SettingsOption("portrait", getString(R.string.library_icon_ratio_portrait)));
		choices.add(new SettingsChoice(
				PREF_APPS_ICON_RATIO,
				getString(R.string.library_icon_ratio_title),
				findOption(ratioOptions,
						LibraryIconRatio.fromStoredValue(preferences.getInt(
								PREF_APPS_ICON_RATIO, LibraryIconRatio.Square.getStoredValue())).getPreferenceKey(),
						ratioOptions.get(0)),
				ratioOptions));

		List<SettingsOption> shapeOptions = Arrays.asList(
				new SettingsOption("round", getString(R.string.library_icon_shape_round)),
				new SettingsOption("square", getString(R.string.library_icon_shape_square)));
		choices.add(new SettingsChoice(
				PREF_APPS_ICON_SHAPE,
				getString(R.string.library_icon_shape_title),
				findOption(shapeOptions,
						LibraryIconShape.fromStoredValue(preferences.getInt(
								PREF_APPS_ICON_SHAPE, LibraryIconShape.Round.getStoredValue())).getPreferenceKey(),
							shapeOptions.get(0)),
				shapeOptions));

		List<SettingsOption> iconSizes = Arrays.asList(
				new SettingsOption("small", getString(R.string.library_grid_icon_size_small)),
				new SettingsOption("standard", getString(R.string.library_grid_icon_size_standard)),
				new SettingsOption("large", getString(R.string.library_grid_icon_size_large)));
				choices.add(new SettingsChoice(
				PREF_APPS_GRID_ICON_SIZE,
				getString(R.string.library_grid_icon_size_title),
				findOption(iconSizes,
						LibraryGridIconSize.fromStoredValue(preferences.getInt(
								PREF_APPS_GRID_ICON_SIZE, LibraryGridIconSize.Standard.getStoredValue())).getPreferenceKey(),
						iconSizes.get(1)),
				iconSizes,
				getString(R.string.library_grid_icon_size_summary)));

		List<SettingsOption> spacingOptions = Arrays.asList(
				new SettingsOption("none", getString(R.string.library_grid_spacing_none)),
				new SettingsOption("compact", getString(R.string.library_grid_spacing_compact)),
				new SettingsOption("standard", getString(R.string.library_grid_spacing_standard)),
				new SettingsOption("spacious", getString(R.string.library_grid_spacing_spacious)));
		choices.add(new SettingsChoice(
				PREF_APPS_GRID_SPACING,
				getString(R.string.library_grid_spacing_title),
				findOption(spacingOptions,
						LibraryGridSpacing.fromStoredValue(preferences.getInt(
								PREF_APPS_GRID_SPACING, LIBRARY_GRID_SPACING_STANDARD)).getPreferenceKey(),
						spacingOptions.get(2)),
				spacingOptions,
				getString(R.string.library_grid_spacing_summary)));
		return choices;
	}

	private List<SettingsSwitch> buildLibrarySwitches() {
		List<SettingsSwitch> switches = new ArrayList<>();
		switches.add(new SettingsSwitch(
				PREF_APPS_ENHANCED_ICONS,
				getString(R.string.library_enhanced_icons_title),
				getString(R.string.library_enhanced_icons_summary),
				preferences.getBoolean(PREF_APPS_ENHANCED_ICONS, true)));
		switches.add(new SettingsSwitch(
				PREF_APPS_HIDE_GRID_TITLES,
				getString(R.string.library_hide_grid_titles),
				getString(R.string.library_hide_grid_titles_summary),
				preferences.getBoolean(PREF_APPS_HIDE_GRID_TITLES, false)));
		return switches;
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
			if ("wallpaper".equals(values[i]) &&
					android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
				continue;
			}
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
						String canonicalTag = AppLocaleTags.canonicalize(tag);
						if (!canonicalTag.isEmpty() && !tags.contains(canonicalTag)) {
							tags.add(canonicalTag);
						}
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
