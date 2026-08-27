/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2018-2019 Nikita Shakarun
 * Copyright 2019-2026 Yury Kharchenko
 *
 * Modified by JL-Mod Plus contributors; original upstream attribution is retained.
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

import static ru.playsoftware.j2meloader.util.Constants.ACTION_EDIT;
import static ru.playsoftware.j2meloader.util.Constants.ACTION_EDIT_PROFILE;
import static ru.playsoftware.j2meloader.util.Constants.KEY_MIDLET_NAME;
import static ru.playsoftware.j2meloader.util.Constants.PREF_DEFAULT_PROFILE;
import static ru.playsoftware.j2meloader.util.Constants.PREF_THEME;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.microedition.shell.timing.TimingTransformMetadata;
import javax.microedition.util.ContextHolder;

import kotlin.io.FilesKt;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.model.Size;
import ru.playsoftware.j2meloader.settings.KeyMapperActivity;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.ui.ThemedToast;
import ru.woesss.util.TextUtils;
import ru.woesss.j2me.jar.Descriptor;
import static ru.playsoftware.j2meloader.config.ConfigFormEvents.ColorField;

public class ConfigActivity extends AppCompatActivity implements ShaderTuneAlert.Callback {
	private static final String TAG = ConfigActivity.class.getSimpleName();

	private final ArrayList<Size> screenPresets = new ArrayList<>();
	private final ArrayList<Size> removableScreenPresets = new ArrayList<>();
	private final ArrayList<ConfigUiState.FontPreset> fontPresets = new ArrayList<>();
	private final ArrayList<String> skinOptions = new ArrayList<>();
	private final ArrayList<String> soundBankOptions = new ArrayList<>();

	private File keylayoutFile;
	private File dataDir;
	private ProfileModel params;
	private boolean isProfile;
	private File appDir;
	private Display display;
	private File configDir;
	private String defProfile;
	private ArrayList<ShaderInfo> shaders;
	private String workDir;
	private boolean needShow;
	private ConfigFormState currentForm;
	private ConfigComposeController composeController;
	private ProfileModel builtInDefaultParams;
	/** True when this app's Settings source is the app-provided Built-In profile. */
	private boolean builtInThemeLinked;
	private SharedPreferences hostPreferences;
	private final SharedPreferences.OnSharedPreferenceChangeListener hostThemeListener =
			(sharedPreferences, key) -> {
				if (PREF_THEME.equals(key) && builtInThemeLinked && !isProfile) {
					// AppCompat applies the new uiMode after the preference callback. Post the sync so
					// the profile palette is derived from the new configuration, not the old one.
					getWindow().getDecorView().post(this::syncLinkedBuiltInTheme);
				}
			};
	private List<ProfileConfigMatcher.Candidate> profileCandidates = Collections.emptyList();
	@Nullable private String profileOrigin;

	private final ConfigFormEvents formEvents = new ConfigFormEvents() {
		@Override
		public void onFormChanged(@NonNull ConfigFormState state) {
			updateForm(state);
		}

		@Override
		public void onAddResolutionPreset(@NonNull Size size) {
			addResolutionToPresets(size);
		}

		@Override
		public void onRemoveResolutionPreset(Size size) {
			removeResolutionPreset(size);
		}

		@Override
		public void onColorPicker(ColorField field) {
			showColorPicker(field);
		}

		@Override
		public void onColorPicked(ColorField field, @NonNull String value) {
			if (currentForm != null) {
				updateForm(setColorValue(currentForm, field, value));
			}
		}

		@Override
		public void onKeyMappings() {
			openKeyMappings();
		}

		@Override
		public void onEncodingPicker() {
			showCharsetPicker();
		}

		@Override
		public void onEncodingSelected(@NonNull String charset) {
			applyCharset(charset);
		}

		@Override
		public void onShaderTuning() {
			showShaderSettings();
		}

		@Override
		public void onShaderTuningComplete(@NonNull float[] values) {
			onTuneComplete(values);
		}


		@Override
		public void onSaveAsProfile() {
			showSaveProfile();
		}


		@Override
		public void onApplyBuiltInTemplate() {
			applyBuiltInTemplate();
		}

		@Override
		public void onApplyTemplate(@NonNull String name) {
			applyTemplate(name, true, true);
		}

		@Override
		public void onApplyTemplateComponents(@NonNull String name, boolean settings, boolean keyboard) {
			applyTemplate(name, settings, keyboard);
		}

		@Override
		public void onKeepSettingsForApp() {
			keepProfileComponent(true);
		}

		@Override
		public void onKeepKeyboardForApp() {
			keepProfileComponent(false);
		}


		@Override
		public void onUpdateTemplate(@NonNull String name) {
			updateTemplate(name);
		}

		@Override
		public void onRenameTemplate(@NonNull String oldName, @NonNull String newName) {
			renameTemplate(oldName, newName);
		}

		@Override
		public void onDeleteTemplate(@NonNull String name) {
			deleteTemplate(name);
		}

		@Override
		public void onSetDefaultTemplate(@Nullable String name) {
			setDefaultTemplate(name);
		}

	};

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		String action = intent.getAction();
		isProfile = ACTION_EDIT_PROFILE.equals(action);
		needShow = isProfile || ACTION_EDIT.equals(action);
		String path = intent.getDataString();
		if (path == null) {
			needShow = false;
			finish();
			return;
		}
		if (isProfile) {
			setResult(RESULT_OK, new Intent().setData(intent.getData()));
			configDir = new File(Config.getProfilesDir(), path);
			workDir = Config.getEmulatorDir();
			setTitle(path);
		} else {
			setTitle(intent.getStringExtra(KEY_MIDLET_NAME));
			appDir = new File(path);
			File convertedDir = appDir.getParentFile();
			if (!appDir.isDirectory() || convertedDir == null
					|| (workDir = convertedDir.getParent()) == null) {
				needShow = false;
				String storageName = "";
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
					StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
					if (sm != null) {
						StorageVolume storageVolume = sm.getStorageVolume(appDir);
						if (storageVolume != null) {
							String desc = storageVolume.getDescription(this);
							if (desc != null) {
								storageName = "\"" + desc + "\" ";
							}
						}
					}
				}
				ComposeView errorView = new ComposeView(this);
				EdgeToEdgeCompat.enableIfSupported(this);
				setContentView(errorView);
				EdgeToEdgeCompat.protectHostContent(this);
				ConfigErrorComposeBridge.install(errorView,
						getString(R.string.err_missing_app, storageName),
						new ConfigErrorActions() {
							@Override
							public void onExit() {
								finish();
							}
						});
				return;
			}
			dataDir = new File(workDir + Config.MIDLET_DATA_DIR + appDir.getName());
			dataDir.mkdirs();
			configDir = new File(workDir + Config.MIDLET_CONFIGS_DIR + appDir.getName());
		}
		configDir.mkdirs();
		hostPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		hostPreferences.registerOnSharedPreferenceChangeListener(hostThemeListener);
		profileOrigin = readProfileOrigin();
		builtInDefaultParams = newBuiltInProfile();

		defProfile = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
				.getString(PREF_DEFAULT_PROFILE, null);
		loadConfig();
		if (!params.isNew && !needShow) {
			startMIDlet();
			return;
		}
		loadKeyLayout();
		refreshProfileMatchCache();
		EdgeToEdgeCompat.enableForComposeSurface(this);
		ComposeView composeView = new ComposeView(this);
		setContentView(composeView);
		if (getSupportActionBar() != null) {
			getSupportActionBar().hide();
		}
		display = getWindowManager().getDefaultDisplay();

		fillScreenSizePresets(display.getWidth(), display.getHeight());

		addFontSizePreset("128 x 128", 9, 13, 15);
		addFontSizePreset("128 x 160", 13, 15, 20);
		addFontSizePreset("176 x 220", 15, 18, 22);
		addFontSizePreset("240 x 320", 18, 22, 26);
		initSoundBankOptions();
		initSkinOptions();
		initShaderSpinner();
		currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
		composeController = new ConfigComposeController(
				composeView,
				createUiState(),
				formEvents,
				new ConfigMenuActions() {
					@Override
					public void onBack() {
						finish();
					}

					@Override
					public void onStart() {
						startMIDlet();
					}

					@Override
					public void onClearData() {
						if (dataDir != null) {
							FileUtils.clearDirectory(dataDir);
						}
					}

					@Override
					public void onResetSettings() {
						if (!isProfile) ProfileLinks.detachSettings(configDir);
						setProfileOrigin(null);
						params = newBuiltInProfile();
						setBuiltInThemeLinked(true);
						loadParams(false);
					}

					@Override
					public void onResetLayout() {
						if (!isProfile) ProfileLinks.detachKeyboard(configDir);
						setProfileOrigin(null);
						if (keylayoutFile != null) {
							//noinspection ResultOfMethodCallIgnored
							keylayoutFile.delete();
						}
						loadKeyLayout();
						refreshProfileMatchCache();
						if (composeController != null) {
							composeController.update(createUiState());
						}
					}

				},
				getTitle() == null ? "" : getTitle().toString(),
				isProfile);
	}

	private void initSkinOptions() {
		skinOptions.clear();
		File dir = new File(workDir + Config.SKINS_DIR);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		skinOptions.add(getString(R.string.pref_skin_not_set));
		String[] files = dir.list((d, n) -> new File(d, n).isFile());
		if (files != null) {
			Arrays.sort(files, (o1, o2) -> {
				int res = o1.compareToIgnoreCase(o2);
				return res != 0 ? res : o1.compareTo(o2);
			});
			skinOptions.addAll(Arrays.asList(files));
		}
	}

	private void initSoundBankOptions() {
		soundBankOptions.clear();
		File dir = new File(workDir + Config.SOUNDBANKS_DIR);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		soundBankOptions.add(getString(R.string.default_label, "Android"));
		String[] files = dir.list((d, n) -> new File(d, n).isFile());
		if (files != null) {
			Arrays.sort(files, (o1, o2) -> {
				int res = o1.compareToIgnoreCase(o2);
				return res != 0 ? res : o1.compareTo(o2);
			});
			soundBankOptions.addAll(Arrays.asList(files));
		}
	}

	void loadConfig() {
		defProfile = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
				.getString(PREF_DEFAULT_PROFILE, null);
		params = isProfile
				? ProfilesManager.loadConfig(configDir)
				: ProfilesManager.loadGameConfig(configDir);
		if (!isProfile) {
			// ProfileLinks consumes the old one-shot origin marker during migration. Keep the
			// activity field synchronized so legacy metadata cannot affect later UI decisions.
			profileOrigin = readProfileOrigin();
		}

		boolean existingAppState = !isProfile && (
				new File(configDir, Config.MIDLET_CONFIG_FILE).exists()
						|| new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE).exists()
						|| ProfileLinks.getSettingsProfile(configDir) != null
						|| ProfileLinks.getKeyboardProfile(configDir) != null
						|| ProfileLinks.isBuiltInSettingsLinked(configDir));

		// A default profile is a policy for a genuinely new app only. Existing materialized files
		// or explicit source provenance must never be retargeted merely because config parsing failed
		// or a first-run settings save was interrupted.
		if (params == null && !isProfile && defProfile != null && !existingAppState) {
			Profile defaultProfile = findProfile(defProfile);
			if (defaultProfile != null) {
				try {
					ProfilesManager.load(defaultProfile, configDir.getPath(), true, true);
					params = ProfilesManager.loadGameConfig(configDir);
				} catch (IOException e) {
					Log.e(TAG, "loadConfig: default profile " + defProfile, e);
				}
			}
		}

		if (params == null) {
			params = newBuiltInProfile();
			if (!isProfile && ProfileLinks.getSettingsProfile(configDir) == null) {
				setBuiltInThemeLinked(true);
			} else {
				builtInThemeLinked = false;
			}
			return;
		}
		if (isProfile) {
			builtInThemeLinked = false;
			return;
		}

		boolean linked = readBuiltInThemeLinked();
		if (ProfileLinks.getSettingsProfile(configDir) != null) {
			ProfileLinks.detachBuiltInSettings(configDir);
			linked = false;
		}
		// Source identity is explicit. Equality with Built-In values never creates a link.
		builtInThemeLinked = linked;
	}

	private void showShaderSettings() {
		ShaderInfo shader = currentForm == null ? null : currentForm.shader;
		if (shader == null || !shader.hasTunableSettings()) {
			return;
		}
		ensureShaderValues(shader);
		params.shader = shader;
		ShaderTuneAlert.newInstance(shader).show(getSupportFragmentManager(), "ShaderTuning");
	}

	private void initShaderSpinner() {
		if (shaders != null) {
			return;
		}
		File dir = new File(workDir + Config.SHADERS_DIR);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		shaders = new ArrayList<>();
		String[] files = dir.list();
		if (files != null) {
			for (String fileName : files) {
				if (!TextUtils.endsWithIgnoreCase(fileName, ".ini")) {
					continue;
				}
				File file = new File(dir, fileName);
				String text;
				try {
					//noinspection CharsetObjectCanBeUsed
					text = FilesKt.readText(file, Charset.forName("UTF-8"));
				} catch (Exception e) {
					Log.e(TAG, "getText: " + file, e);
					continue;
				}

				String[] split = text.split("[\\n\\r]+");
				ShaderInfo info = null;
				for (String line : split) {
					if (line.startsWith("[")) {
						if (info != null && info.fragment != null && info.vertex != null) {
							shaders.add(info);
						}
						info = new ShaderInfo(line.replaceAll("[\\[\\]]", ""), "unknown");
					} else if (info != null) {
						try {
							info.set(line);
						} catch (Exception e) {
							Log.e(TAG, "initShaderSpinner: ", e);
						}
					}
				}
				if (info != null && info.fragment != null && info.vertex != null) {
					shaders.add(info);
				}
			}
			Collections.sort(shaders);
		}
		shaders.add(0, new ShaderInfo(getString(R.string.identity_filter), "woesss"));
		ShaderInfo selected = params.shader;
		if (selected != null) {
			int position = shaders.indexOf(selected);
			if (position > 0) {
				shaders.get(position).values = selected.values;
			}
		}
	}

	private void showCharsetPicker() {
		if (composeController == null) {
			return;
		}
		String[] charsets = Charset.availableCharsets().keySet().toArray(new String[0]);
		String selected = null;
		if (currentForm != null) {
			String key = "microedition.encoding:";
			for (String line : currentForm.systemProperties.split("[\\n\\r]+")) {
				if (line.startsWith(key)) {
					selected = line.substring(key.length()).trim();
					break;
				}
		}
		}
		composeController.showEncodingPicker(Arrays.asList(charsets), selected);
	}

	private void applyCharset(@NonNull String charset) {
		if (currentForm == null) {
			return;
		}
		String text = currentForm.systemProperties;
		String key = "microedition.encoding:";
		int idx = text.lastIndexOf(key);
		if (idx != -1) {
			int nl = text.indexOf('\n', idx);
			text = text.substring(0, idx + key.length()) + " " + charset
					+ (nl == -1 ? "\n" : text.substring(nl));
			updateForm(currentForm.toBuilder().systemProperties(text).build());
			return;
		}

		if (!text.endsWith("\n")) {
			text += "\n";
		}
		updateForm(currentForm.toBuilder().systemProperties(
				text + key + " " + charset + "\n").build());
	}

	private void loadKeyLayout() {
		// Default-profile layout selection is handled together with settings in loadConfig().
		// Existing apps must never begin following a newly selected global default just because
		// their settings screen was opened later.
		keylayoutFile = new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE);
	}

	@Override
	public void onPause() {
		if (needShow && configDir != null) {
			saveParams();
		}
		super.onPause();
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (needShow) {
			loadParams(true);
		}
	}

	@Override
	protected void onDestroy() {
		if (hostPreferences != null) {
			hostPreferences.unregisterOnSharedPreferenceChangeListener(hostThemeListener);
			hostPreferences = null;
		}
		super.onDestroy();
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (configDir != null) {
			syncLinkedBuiltInTheme();
			builtInDefaultParams = newBuiltInProfile();
		}
		if (display != null) {
			fillScreenSizePresets(display.getWidth(), display.getHeight());
			if (composeController != null) {
				composeController.update(createUiState());
			}
		}
	}

	private void fillScreenSizePresets(int w, int h) {
		ArrayList<Size> screenPresets = this.screenPresets;
		screenPresets.clear();
		removableScreenPresets.clear();

		screenPresets.add(new Size(128, 128));
		screenPresets.add(new Size(128, 160));
		screenPresets.add(new Size(132, 176));
		screenPresets.add(new Size(176, 220));
		screenPresets.add(new Size(240, 320));
		screenPresets.add(new Size(352, 416));
		screenPresets.add(new Size(640, 360));
		screenPresets.add(new Size(800, 480));

		if (w > h) {
			screenPresets.add(new Size(h * 3 / 4, h));
			screenPresets.add(new Size(h * 4 / 3, h));
		} else {
			screenPresets.add(new Size(w, w * 4 / 3));
			screenPresets.add(new Size(w, w * 3 / 4));
		}

		screenPresets.add(new Size(w, h));
		Set<String> preset = PreferenceManager.getDefaultSharedPreferences(this)
				.getStringSet("ResolutionsPreset", null);
		if (preset != null) {
			for (String s : preset) {
				Size size = Size.parse(s);
				if (size != null) {
					screenPresets.add(size);
					if (!removableScreenPresets.contains(size)) {
						removableScreenPresets.add(size);
					}
				}
			}
		}
		Collections.sort(screenPresets);
		Collections.sort(removableScreenPresets);
		Size prev = null;
		for (Iterator<Size> iterator = screenPresets.iterator(); iterator.hasNext(); ) {
			Size next = iterator.next();
			if (next.equals(prev)) iterator.remove();
			else prev = next;
		}
	}

	private void addFontSizePreset(String title, int small, int medium, int large) {
		fontPresets.add(new ConfigUiState.FontPreset(title, small, medium, large));
	}

	public void loadParams(boolean reloadFromFile) {
		if (reloadFromFile) {
			loadConfig();
		}
		currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
		refreshProfileMatchCache();
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	void saveParams() {
		try {
			boolean builtInUnmodified = builtInThemeLinked && currentForm != null
					&& builtInDefaultParams != null
					&& ProfileConfigMatcher.sameEffectiveConfig(
							params, currentForm, builtInDefaultParams);
			if (currentForm != null) {
				currentForm.applyTo(params);
			}
			if (ProfilesManager.saveConfig(params) && builtInUnmodified) {
				ProfileLinks.refreshBuiltInBaseline(configDir);
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private void startMIDlet() {
		if (needShow && configDir != null) {
			saveParams();
		}
		Config.startApp(
				this,
				getIntent().getStringExtra(KEY_MIDLET_NAME),
				getIntent().getData());
		finish();
	}

	private void openKeyMappings() {
		Intent i = new Intent(getIntent().getAction(), Uri.parse(configDir.getPath()),
				this, KeyMapperActivity.class);
		startActivity(i);
	}

	private void keepProfileComponent(boolean settings) {
		if (isProfile || configDir == null) return;
		// Persist the draft before changing provenance so "Keep" always retains exactly what the
		// user is looking at, even if another lifecycle event follows immediately.
		saveParams();
		if (settings) {
			ProfileLinks.detachSettings(configDir);
			ProfileLinks.detachBuiltInSettings(configDir);
			builtInThemeLinked = false;
		} else {
			ProfileLinks.detachKeyboard(configDir);
		}
		setProfileOrigin(null);
		refreshProfileMatchCache();
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	private void applyBuiltInTemplate() {
		if (!isProfile) ProfileLinks.detachSettings(configDir);
		setProfileOrigin(null);
		params = newBuiltInProfile();
		setBuiltInThemeLinked(true);
		currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
		refreshProfileMatchCache();
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	private ProfileModel newBuiltInProfile() {
		return ProfileModel.createBuiltIn(configDir, isDarkTheme());
	}

	/** Re-resolves the Built-In source when its dynamic host-theme values change. */
	private void syncLinkedBuiltInTheme() {
		if (isProfile || !builtInThemeLinked || params == null || configDir == null) return;
		if (currentForm != null && builtInDefaultParams != null
				&& !ProfileConfigMatcher.sameEffectiveConfig(
						params, currentForm, builtInDefaultParams)) {
			return;
		}
		params = newBuiltInProfile();
		if (ProfilesManager.saveConfig(params)) {
			ProfileLinks.refreshBuiltInBaseline(configDir);
		}
		currentForm = ConfigFormState.fromProfile(params, normalizedSystemProperties());
		builtInDefaultParams = newBuiltInProfile();
		refreshProfileMatchCache();
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	private boolean isDarkTheme() {
		return ProfileModel.isDarkTheme(this);
	}

	private void applyTemplate(@NonNull String name, boolean settings, boolean keyboard) {
		Profile profile = findProfile(name);
		if (profile == null || (!settings && !keyboard)) return;
		boolean appliesSettings = settings && (profile.hasConfig() || profile.hasOldConfig());
		boolean appliesKeyboard = keyboard && profile.hasKeyLayout();
		if (!appliesSettings && !appliesKeyboard) return;
		saveParams();
		try {
			ProfilesManager.load(profile, configDir.getPath(), settings, keyboard);
			// New operations use explicit per-component links; profileOrigin is migration-only.
			setProfileOrigin(null);
			loadParams(true);
		} catch (IOException e) {
			Log.e(TAG, "applyTemplate: " + name, e);
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
		}
	}


	private void updateTemplate(@NonNull String name) {
		Profile profile = findProfile(name);
		if (profile == null) return;
		try {
			saveParams();
			ProfilesManager.saveSnapshot(profile, configDir.getPath());
			setProfileOrigin(null);
			// Re-resolve after a successful global update so an unchanged sibling that changed
			// elsewhere is refreshed immediately on this screen as well.
			loadParams(true);
		} catch (ProfilesManager.ProfileUpdateConflictException e) {
			Log.w(TAG, "updateTemplate: stale profile " + name, e);
			ThemedToast.show(this, R.string.profile_update_conflict, Toast.LENGTH_LONG);
			loadParams(true);
		} catch (IOException e) {
			Log.e(TAG, "updateTemplate: " + name, e);
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
		}
	}

	private void renameTemplate(@NonNull String oldName, @NonNull String rawNewName) {
		String newName = rawNewName.trim();
		Profile profile = findProfile(oldName);
		if (profile == null || newName.isEmpty() || findProfile(newName) != null) return;
		if (!profile.renameTo(newName)) {
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return;
		}
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		if (oldName.equals(preferences.getString(PREF_DEFAULT_PROFILE, null))) {
			preferences.edit().putString(PREF_DEFAULT_PROFILE, newName).apply();
		}
		if (oldName.equals(profileOrigin)) setProfileOrigin(newName);
		refreshProfileMatchCache();
		composeController.update(createUiState());
	}

	private void deleteTemplate(@NonNull String name) {
		Profile profile = findProfile(name);
		if (profile == null) return;
		profile.delete();
		if (profile.getDir().exists()) {
			ThemedToast.show(this, R.string.profile_template_operation_failed, Toast.LENGTH_SHORT);
			return;
		}
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		if (name.equals(preferences.getString(PREF_DEFAULT_PROFILE, null))) {
			preferences.edit().remove(PREF_DEFAULT_PROFILE).apply();
		}
		if (name.equals(profileOrigin)) setProfileOrigin(null);
		refreshProfileMatchCache();
		composeController.update(createUiState());
	}

	private void setDefaultTemplate(@Nullable String name) {
		SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
		if (name == null) editor.remove(PREF_DEFAULT_PROFILE);
		else if (findProfile(name) != null) editor.putString(PREF_DEFAULT_PROFILE, name);
		else return;
		editor.apply();
		if (composeController != null) composeController.update(createUiState());
	}

	@Nullable
	private Profile findProfile(@NonNull String name) {
		for (Profile profile : ProfilesManager.getProfiles()) {
			if (profile.getName().equals(name)) return profile;
		}
		return null;
	}

	private String profileOriginKey() {
		return "config_profile_origin:" + configDir.getAbsolutePath();
	}

	private boolean readBuiltInThemeLinked() {
		return !isProfile && configDir != null && ProfileLinks.isBuiltInSettingsLinked(configDir);
	}

	private void setBuiltInThemeLinked(boolean linked) {
		builtInThemeLinked = linked;
		if (isProfile || configDir == null) return;
		if (linked) ProfileLinks.linkBuiltInSettings(configDir);
		else ProfileLinks.detachBuiltInSettings(configDir);
	}

	@Nullable
	private String readProfileOrigin() {
		if (isProfile || configDir == null) return null;
		return PreferenceManager.getDefaultSharedPreferences(this).getString(profileOriginKey(), null);
	}

	private void setProfileOrigin(@Nullable String name) {
		profileOrigin = name;
		if (isProfile || configDir == null) return;
		if (name != null) {
			setBuiltInThemeLinked(false);
		}
		SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
		if (name == null) editor.remove(profileOriginKey());
		else editor.putString(profileOriginKey(), name);
		editor.apply();
	}


	private void showSaveProfile() {
		if (keylayoutFile == null) {
			return;
		}
		// Save the effective draft so the profile becomes a reusable snapshot of this screen.
		saveParams();
		SaveProfileAlert.getInstance(keylayoutFile.getParent())
				.show(getSupportFragmentManager(), "save_profile");
	}

	/** Called by the save dialog after a profile snapshot is written and linked. */
	void onProfileDataChanged() {
		profileOrigin = readProfileOrigin();
		builtInThemeLinked = readBuiltInThemeLinked();
		refreshProfileMatchCache();
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	private void showColorPicker(ColorField field) {
		if (composeController != null && currentForm != null) {
			composeController.showColorPicker(field, colorValue(currentForm, field));
		}
	}

	private void addResolutionToPresets(@NonNull Size size) {
		if (size.width <= 0 || size.height <= 0) {
			ThemedToast.show(this, R.string.invalid_resolution_not_saved, Toast.LENGTH_SHORT);
			return;
		}
		int index = Collections.binarySearch(screenPresets, size);
		if (index >= 0) {
			ThemedToast.show(this, R.string.not_saved_exists, Toast.LENGTH_SHORT);
			return;
		}
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		Set<String> set = preferences.getStringSet("ResolutionsPreset", null);
		Set<String> presets = set == null ? new HashSet<>(1) : new HashSet<>(set);
		presets.add(size.toString());
		preferences.edit().putStringSet("ResolutionsPreset", presets).apply();
		fillScreenSizePresets(display.getWidth(), display.getHeight());
		if (composeController != null) {
			composeController.update(createUiState());
		}
		ThemedToast.show(this, getString(R.string.saved, size.toString()), Toast.LENGTH_SHORT);
	}

	private void removeResolutionPreset(Size size) {
		if (!removableScreenPresets.contains(size)) {
			return;
		}
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		Set<String> set = preferences.getStringSet("ResolutionsPreset", null);
		if (set == null || !set.contains(size.toString())) {
			return;
		}
		Set<String> presets = new HashSet<>(set);
		presets.remove(size.toString());
		SharedPreferences.Editor editor = preferences.edit();
		if (presets.isEmpty()) {
			editor.remove("ResolutionsPreset");
		} else {
			editor.putStringSet("ResolutionsPreset", presets).apply();
		}
		if (presets.isEmpty()) {
			editor.apply();
		}
		fillScreenSizePresets(display.getWidth(), display.getHeight());
		if (composeController != null) {
			composeController.update(createUiState());
		}
		ThemedToast.show(this, getString(R.string.removed, size.toString()), Toast.LENGTH_SHORT);
	}

	@Override
	public void onTuneComplete(float[] values) {
		if (params.shader != null) {
			params.shader.values = values;
		}
		if (currentForm != null && currentForm.shader != null) {
			currentForm.shader.values = values;
			if (composeController != null) {
				composeController.update(createUiState());
			}
		}
	}

	private void refreshProfileMatchCache() {
		if (isProfile) {
			profileCandidates = Collections.emptyList();
			return;
		}
		profileCandidates = ProfileConfigMatcher.loadCandidates(ProfilesManager.getProfiles());
	}

	private ConfigUiState createUiState() {
		ConfigFormState state = currentForm == null
				? ConfigFormState.fromProfile(params, normalizedSystemProperties())
				: currentForm;
		String defaultProfile = PreferenceManager.getDefaultSharedPreferences(this)
				.getString(PREF_DEFAULT_PROFILE, null);
		ConfigUiState.ProfileStatus profileStatus;

		if (isProfile) {
			profileStatus = ConfigUiState.ProfileStatus.custom(defaultProfile);
		} else {
			String settingsProfile = ProfileLinks.getSettingsProfile(configDir);
			String keyboardProfile = ProfileLinks.getKeyboardProfile(configDir);
			boolean settingsModified = ProfileLinks.isSettingsModified(configDir);
			boolean keyboardModified = ProfileLinks.isKeyboardModified(configDir);

			if (settingsProfile != null) {
				ProfileConfigMatcher.Candidate candidate = findProfileCandidate(settingsProfile);
				if (candidate == null || candidate.config == null
						|| !ProfileConfigMatcher.sameEffectiveConfig(params, state, candidate.config)) {
					settingsModified = true;
				}
			}

			boolean builtInSettings = settingsProfile == null && builtInThemeLinked;
			if (builtInSettings) {
				settingsModified = builtInDefaultParams == null
						|| !ProfileConfigMatcher.sameEffectiveConfig(params, state, builtInDefaultParams);
			}

			profileStatus = ConfigUiState.ProfileStatus.components(
					settingsProfile, settingsModified, keyboardProfile, keyboardModified,
					builtInSettings, defaultProfile);
		}

		ArrayList<ConfigUiState.ProfileTemplate> templates = new ArrayList<>();
		for (ProfileConfigMatcher.Candidate candidate : profileCandidates) {
			String name = candidate.profile.getName();
			templates.add(new ConfigUiState.ProfileTemplate(
					name, name.equals(defaultProfile), candidate.config != null, candidate.keyboard != null));
		}
		return new ConfigUiState(state, screenPresets, fontPresets, skinOptions, soundBankOptions,
				shaders == null ? Collections.emptyList() : shaders, removableScreenPresets,
				profileStatus, templates, isProfile || hasCompatibleTimingTransform());
	}

	private boolean hasCompatibleTimingTransform() {
		if (appDir == null) {
			return false;
		}
		try {
			Descriptor descriptor = new Descriptor(
					new File(appDir, Config.MIDLET_MANIFEST_FILE), false);
			return TimingTransformMetadata.isCompatible(descriptor.getAttrs());
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	@Nullable
	private ProfileConfigMatcher.Candidate findProfileCandidate(@NonNull String name) {
		for (ProfileConfigMatcher.Candidate candidate : profileCandidates) {
			if (candidate.profile.getName().equals(name)) return candidate;
		}
		return null;
	}

	private String normalizedSystemProperties() {
		String systemProperties = params == null ? null : params.systemProperties;
		if (systemProperties == null) {
			systemProperties = ContextHolder.getAssetAsString("defaults/system.props");
		}
		return ConfigFormState.normalizeSystemProperties(systemProperties);
	}

	private void updateForm(ConfigFormState state) {
		currentForm = state;
		if (composeController != null) {
			composeController.update(createUiState());
		}
	}

	private void ensureShaderValues(ShaderInfo shader) {
		if (shader.values != null || shader.settings == null) {
			return;
		}
		float[] values = new float[4];
		boolean hasValues = false;
		for (int i = 0; i < shader.settings.length; i++) {
			ShaderInfo.Setting setting = shader.settings[i];
			if (setting != null) {
				values[i] = setting.def;
				hasValues = true;
			}
		}
		if (hasValues) {
			shader.values = values;
		}
	}

	private static String colorValue(ConfigFormState state, ColorField field) {
		return switch (field) {
			case SCREEN_BACKGROUND -> state.screenBackground;
			case VIRTUAL_KEYBOARD_BACKGROUND -> state.vkBackground;
			case VIRTUAL_KEYBOARD_FOREGROUND -> state.vkForeground;
			case VIRTUAL_KEYBOARD_SELECTED_BACKGROUND -> state.vkSelectedBackground;
			case VIRTUAL_KEYBOARD_SELECTED_FOREGROUND -> state.vkSelectedForeground;
			case VIRTUAL_KEYBOARD_OUTLINE -> state.vkOutline;
		};
	}

	private static ConfigFormState setColorValue(ConfigFormState state, ColorField field, String value) {
		ConfigFormState.Builder builder = state.toBuilder();
		switch (field) {
			case SCREEN_BACKGROUND -> builder.screenBackground(value);
			case VIRTUAL_KEYBOARD_BACKGROUND -> builder.vkBackground(value);
			case VIRTUAL_KEYBOARD_FOREGROUND -> builder.vkForeground(value);
			case VIRTUAL_KEYBOARD_SELECTED_BACKGROUND -> builder.vkSelectedBackground(value);
			case VIRTUAL_KEYBOARD_SELECTED_FOREGROUND -> builder.vkSelectedForeground(value);
			case VIRTUAL_KEYBOARD_OUTLINE -> builder.vkOutline(value);
		}
		return builder.build();
	}
}
