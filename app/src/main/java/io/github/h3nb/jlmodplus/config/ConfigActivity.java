/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2018-2019 Nikita Shakarun
 * Copyright 2019-2026 Yury Kharchenko
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

import static io.github.h3nb.jlmodplus.util.Constants.ACTION_EDIT;
import static io.github.h3nb.jlmodplus.util.Constants.ACTION_EDIT_PROFILE;
import static io.github.h3nb.jlmodplus.util.Constants.KEY_MIDLET_NAME;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_DEFAULT_PROFILE;

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
import java.util.Locale;
import java.util.Set;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import kotlin.io.FilesKt;
import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.model.Size;
import org.microemu.cldc.SecureConnectionPolicy;
import io.github.h3nb.jlmodplus.settings.KeyMapperActivity;
import io.github.h3nb.jlmodplus.ui.ComposeDialogHost;
import io.github.h3nb.jlmodplus.util.FileUtils;
import ru.woesss.j2me.mmapi.synth.SoundBankResolver;
import ru.woesss.j2me.rms.RmsSnapshotManager;
import ru.woesss.util.TextUtils;

public class ConfigActivity extends AppCompatActivity implements ConfigComposeView.Callback {
	private static final String TAG = ConfigActivity.class.getSimpleName();

	private final ArrayList<Size> screenPresets = new ArrayList<>();
	private final ArrayList<int[]> fontPresetValues = new ArrayList<>();
	private final ArrayList<String> fontPresetTitles = new ArrayList<>();

	private File keylayoutFile;
	private File dataDir;
	private ProfileModel params;
	private boolean isProfile;
	private Display display;
	private File configDir;
	private String defProfile;
	private ArrayList<ShaderInfo> shaders;
	private String workDir;
	private boolean needShow;
	private ConfigComposeView composeView;
	private int lastSafeSecureConnectionMode;
	private Disposable rmsOperation;

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
			File appDir = new File(path);
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
				ComposeDialogHost.showMessage(
						this,
						getString(R.string.error),
						getString(R.string.err_missing_app, storageName),
						getString(R.string.exit),
						null,
						null,
						false,
						this::finish,
						null,
						null
				);
				return;
			}
			dataDir = new File(workDir + Config.MIDLET_DATA_DIR + appDir.getName());
			dataDir.mkdirs();
			configDir = new File(workDir + Config.MIDLET_CONFIGS_DIR + appDir.getName());
		}
		configDir.mkdirs();

		defProfile = PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
				.getString(PREF_DEFAULT_PROFILE, null);
		loadConfig();
		if (!params.isNew && !needShow) {
			startMIDlet();
			return;
		}
		loadKeyLayout();
		composeView = new ConfigComposeView(this, this, !isProfile);
		setContentView(composeView);
		composeView.setToolbarTitle(getTitle().toString());
		if (!isProfile) {
			lastSafeSecureConnectionMode =
					params.secureConnectionMode == SecureConnectionPolicy.MODE_INSECURE
							? SecureConnectionPolicy.MODE_ANDROID
							: params.secureConnectionMode;
			composeView.setSecureConnectionSelection(
					Math.max(0, Math.min(params.secureConnectionMode, 2))
			);
		}
		if (getSupportActionBar() != null) {
			getSupportActionBar().hide();
		}
		display = getWindowManager().getDefaultDisplay();

		fillScreenSizePresets(display.getWidth(), display.getHeight());

		addFontSizePreset("128 x 128", 9, 13, 15);
		addFontSizePreset("128 x 160", 13, 15, 20);
		addFontSizePreset("176 x 220", 15, 18, 22);
		addFontSizePreset("240 x 320", 18, 22, 26);
		initSoundBankSpinner();
		initSkinSpinner();
		if (params.graphicsMode == 1) {
			initShaderSpinner();
		}
	}

	private void initSkinSpinner() {
		File dir = new File(workDir + Config.SKINS_DIR);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		ArrayList<String> options = new ArrayList<>();
		options.add(getString(R.string.pref_skin_not_set));
		String[] files = dir.list((d, n) -> new File(d, n).isFile());
		if (files != null) {
			Arrays.sort(files, (o1, o2) -> {
				int res = o1.compareToIgnoreCase(o2);
				return res != 0 ? res : o1.compareTo(o2);
			});
			options.addAll(Arrays.asList(files));
		}
		composeView.setSkinOptions(options);
	}

	private void initSoundBankSpinner() {
		File dir = new File(workDir + Config.SOUNDBANKS_DIR);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		ArrayList<String> options = new ArrayList<>();
		options.add(getString(R.string.default_label, "Android"));
		String[] files = dir.list((d, n) -> {
			try {
				return SoundBankResolver.resolve(d, n) != null;
			} catch (IOException e) {
				Log.w(TAG, "Unable to inspect soundbank " + n, e);
				return false;
			}
		});
		if (files != null) {
			Arrays.sort(files, (o1, o2) -> {
				int res = o1.compareToIgnoreCase(o2);
				return res != 0 ? res : o1.compareTo(o2);
			});
			options.addAll(Arrays.asList(files));
		}
		composeView.setSoundBankOptions(options);
	}

	void loadConfig() {
		params = ProfilesManager.loadConfig(configDir);
		if (params == null && defProfile != null) {
			FileUtils.copyFiles(new File(Config.getProfilesDir(), defProfile), configDir, null);
			params = ProfilesManager.loadConfig(configDir);
		}
		if (params == null) {
			params = new ProfileModel(configDir);
		}
	}

	private void showShaderSettings() {
		ShaderInfo shader = composeView.getSelectedShader();
		if (shader == null) return;
		params.shader = shader;
		ShaderInfo.Setting[] settings = shader.settings;
		String[] names = new String[4];
		float[] minimums = new float[4];
		float[] maximums = new float[4];
		float[] steps = new float[4];
		float[] defaults = new float[4];
		for (int i = 0; i < settings.length && i < 4; i++) {
			ShaderInfo.Setting setting = settings[i];
			if (setting != null) {
				names[i] = setting.name;
				minimums[i] = setting.min;
				maximums[i] = setting.max;
				steps[i] = setting.step;
				defaults[i] = setting.def;
			}
		}
		float[] initialValues = shader.values == null ? defaults : shader.values;
		ConfigComposeDialogHost.showShaderTuning(
				this,
				getString(R.string.shader_tuning),
				names,
				minimums,
				maximums,
				steps,
				defaults,
				initialValues,
				getString(android.R.string.ok),
				getString(android.R.string.cancel),
				getString(R.string.reset),
				false,
				values -> params.shader.values = values
		);
	}

	private void confirmInsecureSecureConnectionMode() {
		Runnable restoreSafeMode = () ->
				composeView.setSecureConnectionSelection(lastSafeSecureConnectionMode);
		ComposeDialogHost.showMessage(
				this,
				getString(R.string.secure_connection_insecure_title),
				getString(R.string.secure_connection_insecure_message),
				getString(R.string.secure_connection_use_insecure),
				getString(android.R.string.cancel),
				null,
				true,
				() -> lastSafeSecureConnectionMode = SecureConnectionPolicy.MODE_INSECURE,
				restoreSafeMode,
				null,
				restoreSafeMode
		);
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
		composeView.setShaderOptions(shaders);
		ShaderInfo selected = params.shader;
		if (selected != null) {
			int position = shaders.indexOf(selected);
			if (position > 0) {
				shaders.get(position).values = selected.values;
				composeView.setSelectedShaderPosition(position);
				onShaderSelected(position);
			} else {
				onShaderSelected(0);
			}
		}
	}

	private void showCharsetPicker() {
		String[] charsets = Charset.availableCharsets().keySet().toArray(new String[0]);
		ComposeDialogHost.showChoice(
				this,
				getString(R.string.pref_encoding_title),
				charsets,
				-1,
				getString(android.R.string.cancel),
				true,
				which -> {
			String text = composeView.getSystemPropertiesText();
			String key = "microedition.encoding:";
			int idx = text.lastIndexOf(key);
			if (idx != -1) {
				int nl = text.indexOf('\n', idx);
				text = text.substring(0, idx + key.length()) + " " + charsets[which] + (nl == -1 ? "\n" : text.substring(nl));
				composeView.setSystemPropertiesText(text);
				return;
			}

			if (!text.endsWith("\n")) {
				text += "\n";
			}
			composeView.setSystemPropertiesText(text + key + " " + charsets[which] + "\n");
			}
		);
	}

	private void loadKeyLayout() {
		File file = new File(configDir, Config.MIDLET_KEY_LAYOUT_FILE);
		keylayoutFile = file;
		if (isProfile || file.exists()) {
			return;
		}
		if (defProfile == null) {
			return;
		}
		File defaultKeyLayoutFile = new File(Config.getProfilesDir() + defProfile, Config.MIDLET_KEY_LAYOUT_FILE);
		if (!defaultKeyLayoutFile.exists()) {
			return;
		}
		try {
			FileUtils.copyFileUsingChannel(defaultKeyLayoutFile, file);
		} catch (IOException e) {
			e.printStackTrace();
		}
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
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		fillScreenSizePresets(display.getWidth(), display.getHeight());
	}

	private void fillScreenSizePresets(int w, int h) {
		ArrayList<Size> screenPresets = this.screenPresets;
		screenPresets.clear();

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
				}
			}
		}
		Collections.sort(screenPresets);
		Size prev = null;
		for (Iterator<Size> iterator = screenPresets.iterator(); iterator.hasNext(); ) {
			Size next = iterator.next();
			if (next.equals(prev)) iterator.remove();
			else prev = next;
		}
	}

	private void addFontSizePreset(String title, int small, int medium, int large) {
		fontPresetValues.add(new int[]{small, medium, large});
		fontPresetTitles.add(title);
	}

	public void loadParams(boolean reloadFromFile) {
		if (reloadFromFile) {
			loadConfig();
		}
		int screenWidth = params.screenWidth;
		if (screenWidth != 0) {
			composeView.setScreenWidthText(Integer.toString(screenWidth));
		}
		int screenHeight = params.screenHeight;
		if (screenHeight != 0) {
			composeView.setScreenHeightText(Integer.toString(screenHeight));
		}
		composeView.setScreenBackgroundText(String.format("%06X", params.screenBackgroundColor));
		composeView.setSkinSelection(params.screenBackgroundImage);
		composeView.setScaleRatioText(Integer.toString(params.screenScaleRatio));
		composeView.setOrientationSelection(params.orientation);
		composeView.setScaleTypeSelection(params.screenScaleType);
		composeView.setGravitySelection(params.screenGravity);
		composeView.setScreenPaddingText(Integer.toString(params.screenPadding));
		composeView.setFilterChecked(params.screenFilter);
		composeView.setImmediateChecked(params.immediateMode);
		composeView.setParallelChecked(params.parallelRedrawScreen);
		composeView.setForceFullscreenChecked(params.forceFullscreen);
		composeView.setGraphicsModeSelection(params.graphicsMode);
		if (shaders != null) {
			int position = shaders.indexOf(params.shader);
			if (position > 0) {
				shaders.get(position).values =  params.shader.values;
				composeView.setSelectedShaderPosition(position);
				onShaderSelected(position);
			} else {
				composeView.setSelectedShaderPosition(0);
				onShaderSelected(0);
			}
		}
		composeView.setShowFpsChecked(params.showFps);

		composeView.setFontSmallText(Integer.toString(params.fontSizeSmall));
		composeView.setFontMediumText(Integer.toString(params.fontSizeMedium));
		composeView.setFontLargeText(Integer.toString(params.fontSizeLarge));
		composeView.setFontInSpChecked(params.fontApplyDimensions);
		composeView.setFontAaChecked(params.fontAA);
		boolean showVk = params.showKeyboard;
		composeView.setShowKeyboardChecked(showVk);
		composeView.setVkFeedbackChecked(params.vkFeedback);
		composeView.setVkForceOpacityChecked(params.vkForceOpacity);
		composeView.setTouchInputChecked(params.touchInput);
		int fpsLimit = params.fpsLimit;
		composeView.setFpsLimitText(fpsLimit > 0 ? Integer.toString(fpsLimit) : "");

		composeView.setLayoutSelection(params.keyCodesLayout);
		composeView.setButtonShapeSelection(params.vkButtonShape);
		composeView.setVkAlphaProgress(params.vkAlpha);
		int vkHideDelay = params.vkHideDelay;
		composeView.setVkHideDelayText(vkHideDelay > 0 ? Integer.toString(vkHideDelay) : "");

		composeView.setVkBackText(String.format("%06X", params.vkBgColor));
		composeView.setVkForeText(String.format("%06X", params.vkFgColor));
		composeView.setVkSelectedBackText(String.format("%06X", params.vkBgColorSelected));
		composeView.setVkSelectedForeText(String.format("%06X", params.vkFgColorSelected));
		composeView.setVkOutlineText(String.format("%06X", params.vkOutlineColor));

		composeView.setSkipResumeChecked(params.skipResumeCall);
		composeView.setSecureConnectionSelection(
				Math.max(0, Math.min(params.secureConnectionMode, 2))
		);
		composeView.setSoundBankSelection(params.soundBank);

		String systemProperties = params.systemProperties;
		if (systemProperties == null) {
			systemProperties = ContextHolder.getAssetAsString("defaults/system.props");
		}
		composeView.setSystemPropertiesText(getSystemProperties(systemProperties));
	}

	private void saveParams() {
		try {
			int width;
			try {
				width = Integer.parseInt(composeView.getScreenWidthText());
			} catch (NumberFormatException e) {
				width = 0;
			}
			params.screenWidth = width;
			int height;
			try {
				height = Integer.parseInt(composeView.getScreenHeightText());
			} catch (NumberFormatException e) {
				height = 0;
			}
			params.screenHeight = height;
			try {
				params.screenBackgroundColor = Integer.parseInt(composeView.getScreenBackgroundText(), 16);
			} catch (NumberFormatException ignored) {
			}
			params.screenBackgroundImage = composeView.getSkinSelectedItem();
			try {
				params.screenScaleRatio = Integer.parseInt(composeView.getScaleRatioText());
			} catch (NumberFormatException e) {
				params.screenScaleRatio = 100;
			}
			params.orientation = composeView.getOrientationSelection();
			params.screenGravity = composeView.getGravitySelection();
			try {
				params.screenPadding = Integer.parseInt(composeView.getScreenPaddingText());
			} catch (NumberFormatException e) {
				params.screenPadding = 0;
			}
			params.screenScaleType = composeView.getScaleTypeSelection();
			params.screenFilter = composeView.isFilterChecked();
			params.immediateMode = composeView.isImmediateChecked();
			int mode = composeView.getGraphicsModeSelection();
			params.graphicsMode = mode;
			if (mode == 1) {
				if (composeView.getSelectedShaderPosition() == 0)
					params.shader = null;
				else
					params.shader = composeView.getSelectedShader();
			}
			params.parallelRedrawScreen = composeView.isParallelChecked();
			params.forceFullscreen = composeView.isForceFullscreenChecked();
			params.showFps = composeView.isShowFpsChecked();
			try {
				params.fpsLimit = Integer.parseInt(composeView.getFpsLimitText());
			} catch (NumberFormatException e) {
				params.fpsLimit = 0;
			}

			try {
				params.fontSizeSmall = Integer.parseInt(composeView.getFontSmallText());
			} catch (NumberFormatException e) {
				params.fontSizeSmall = 0;
			}
			try {
				params.fontSizeMedium = Integer.parseInt(composeView.getFontMediumText());
			} catch (NumberFormatException e) {
				params.fontSizeMedium = 0;
			}
			try {
				params.fontSizeLarge = Integer.parseInt(composeView.getFontLargeText());
			} catch (NumberFormatException e) {
				params.fontSizeLarge = 0;
			}
			params.fontApplyDimensions = composeView.isFontInSpChecked();
			params.fontAA = composeView.isFontAaChecked();
			params.showKeyboard = composeView.isShowKeyboardChecked();
			params.vkFeedback = composeView.isVkFeedbackChecked();
			params.vkForceOpacity = composeView.isVkForceOpacityChecked();
			params.touchInput = composeView.isTouchInputChecked();

			params.keyCodesLayout = composeView.getLayoutSelection();
			params.vkButtonShape = composeView.getButtonShapeSelection();
			params.vkAlpha = composeView.getVkAlphaProgress();
			try {
				params.vkHideDelay = Integer.parseInt(composeView.getVkHideDelayText());
			} catch (NumberFormatException e) {
				params.vkHideDelay = 0;
			}
			try {
				params.vkBgColor = Integer.parseInt(composeView.getVkBackText(), 16);
			} catch (Exception ignored) {
			}
			try {
				params.vkFgColor = Integer.parseInt(composeView.getVkForeText(), 16);
			} catch (Exception ignored) {
			}
			try {
				params.vkBgColorSelected = Integer.parseInt(composeView.getVkSelectedBackText(), 16);
			} catch (Exception ignored) {
			}
			try {
				params.vkFgColorSelected = Integer.parseInt(composeView.getVkSelectedForeText(), 16);
			} catch (Exception ignored) {
			}
			try {
				params.vkOutlineColor = Integer.parseInt(composeView.getVkOutlineText(), 16);
			} catch (Exception ignored) {
			}
			params.skipResumeCall = composeView.isSkipResumeChecked();
			params.secureConnectionMode = isProfile
					? SecureConnectionPolicy.MODE_ANDROID
					: composeView.getSecureConnectionSelection();
			params.soundBank = composeView.getSoundBankSelectedItem();
			params.systemProperties = getSystemProperties(composeView.getSystemPropertiesText());

			ProfilesManager.saveConfig(params);
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	@NonNull
	private String getSystemProperties(String text) {
		String[] lines = text.split("[\\r\\n]+");
		ArrayList<String> list = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		for (int i = lines.length - 1; i >= 0; i--) {
			String line = lines[i];
			int colon = line.indexOf(':');
			if (colon != -1 && keys.add(line.substring(0, colon).trim())) {
				list.add(line);
			}
		}
		Collections.sort(list);
		StringBuilder sb = new StringBuilder();
		for (String string : list) {
			sb.append(string);
			sb.append("\n");
		}
		return sb.toString();
	}

	@Override
	public void onBack() {
		finish();
	}

	@Override
	public void onToolbarAction(int itemId) {
		if (itemId == R.id.action_start) {
			startMIDlet();
		} else if (itemId == R.id.action_clear_data) {
			showClearDataDialog();
		} else if (itemId == R.id.action_rms_editor) {
			showRmsEditorDialog();
		} else if (itemId == R.id.action_reset_settings) {
			params = new ProfileModel(configDir);
			loadParams(false);
		} else if (itemId == R.id.action_reset_layout) {
			//noinspection ResultOfMethodCallIgnored
			keylayoutFile.delete();
			loadKeyLayout();
		} else if (itemId == R.id.action_load_profile) {
			showLoadProfileDialog();
		} else if (itemId == R.id.action_save_profile) {
			saveParams();
			showSaveProfileDialog();
		} else {
			return;
		}
	}

	private void showLoadProfileDialog() {
		ArrayList<Profile> profiles = ProfilesManager.getProfiles();
		Collections.sort(profiles);
		String defaultName = PreferenceManager.getDefaultSharedPreferences(this)
				.getString(PREF_DEFAULT_PROFILE, null);
		String[] names = new String[profiles.size()];
		boolean[] hasConfig = new boolean[profiles.size()];
		boolean[] hasKeyboard = new boolean[profiles.size()];
		int defaultIndex = -1;
		for (int i = 0; i < profiles.size(); i++) {
			Profile profile = profiles.get(i);
			names[i] = profile.getName();
			hasConfig[i] = profile.hasConfig() || profile.hasOldConfig();
			hasKeyboard[i] = profile.hasKeyLayout();
			if (profile.getName().equals(defaultName)) {
				defaultIndex = i;
			}
		}
		ConfigComposeDialogHost.showLoadProfile(
				this,
				getString(R.string.load_profile),
				names,
				hasConfig,
				hasKeyboard,
				defaultIndex,
				getString(R.string.action_settings),
				getString(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS),
				getString(android.R.string.ok),
				getString(android.R.string.cancel),
				true,
				(index, configChecked, keyboardChecked) -> {
					if (index < 0 || index >= profiles.size()) {
						Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
						return;
					}
					try {
						ProfilesManager.load(
								profiles.get(index),
								keylayoutFile.getParent(),
								configChecked,
								keyboardChecked
						);
						loadParams(true);
					} catch (Exception e) {
						e.printStackTrace();
						Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
					}
				}
		);
	}

	private void showSaveProfileDialog() {
		final android.app.Dialog[] dialogHolder = new android.app.Dialog[1];
		dialogHolder[0] = ConfigComposeDialogHost.showSaveProfile(
				this,
				getString(R.string.save_profile),
				getString(R.string.enter_name),
				"",
				getString(R.string.action_settings),
				getString(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS),
				getString(R.string.set_as_default),
				getString(android.R.string.ok),
				getString(android.R.string.cancel),
				true,
				(nameValue, configChecked, keyboardChecked, defaultChecked) -> {
					String name = nameValue.trim();
					if (name.isEmpty()) {
						Toast.makeText(this, R.string.error_name, Toast.LENGTH_SHORT).show();
						return false;
					}
					File config = new File(Config.getProfilesDir(), name + Config.MIDLET_CONFIG_FILE);
					if (config.exists()) {
						ComposeDialogHost.showMessage(
								this,
								getString(R.string.save_profile),
								getString(R.string.alert_rewrite_profile, name),
								getString(android.R.string.ok),
								getString(android.R.string.cancel),
								null,
								true,
								() -> {
									if (saveProfile(name, configChecked, keyboardChecked, defaultChecked)
											&& dialogHolder[0] != null) {
										dialogHolder[0].dismiss();
									}
								},
								null,
								null
						);
						return false;
					}
					return saveProfile(name, configChecked, keyboardChecked, defaultChecked);
				}
		);
	}

	private boolean saveProfile(
			String name,
			boolean configChecked,
			boolean keyboardChecked,
			boolean defaultChecked
	) {
		try {
			Profile profile = new Profile(name);
			ProfilesManager.save(profile, keylayoutFile.getParent(), configChecked, keyboardChecked);
			if (defaultChecked) {
				PreferenceManager.getDefaultSharedPreferences(this)
						.edit().putString(PREF_DEFAULT_PROFILE, name).apply();
			}
			Toast.makeText(this, getString(R.string.saved, name), Toast.LENGTH_SHORT).show();
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
			return false;
		}
	}

	private void showClearDataDialog() {
		ComposeDialogHost.showMessage(
				this,
				getString(android.R.string.dialog_alert_title),
				getString(R.string.message_clear_data),
				getString(android.R.string.ok),
				getString(android.R.string.cancel),
				null,
				true,
				() -> FileUtils.clearDirectory(dataDir),
				null,
				null
		);
	}

	private void showRmsEditorDialog() {
		File snapshotRoot = new File(Config.getRmsSnapshotsDir(), dataDir.getName());
		List<RmsSnapshotManager.Snapshot> snapshots;
		try {
			snapshots = RmsSnapshotManager.list(snapshotRoot);
		} catch (IOException e) {
			Toast.makeText(this, getString(R.string.rms_snapshot_failed, e.getMessage()), Toast.LENGTH_LONG).show();
			return;
		}
		final List<RmsSnapshotManager.Snapshot> available = snapshots;
		String[] labels = new String[Math.max(1, available.size())];
		if (available.isEmpty()) {
			labels[0] = getString(R.string.rms_snapshot_empty);
		} else {
			for (int i = 0; i < available.size(); i++) {
				RmsSnapshotManager.Snapshot snapshot = available.get(i);
				labels[i] = snapshot.label + " (" + snapshot.file.getName() + ")";
			}
		}
		ComposeDialogHost.showChoiceActions(
				this,
				getString(R.string.rms_editor_title),
				labels,
				-1,
				getString(R.string.rms_snapshot_create),
				getString(R.string.rms_snapshot_restore),
				getString(android.R.string.cancel),
				true,
				true,
				index -> createRmsSnapshot(snapshotRoot),
				index -> {
					if (index >= 0 && index < available.size()) {
						confirmRmsRestore(available.get(index));
					}
				},
				null
		);
	}

	private void confirmRmsRestore(RmsSnapshotManager.Snapshot snapshot) {
		ComposeDialogHost.showMessage(
				this,
				getString(R.string.rms_snapshot_restore),
				getString(R.string.rms_snapshot_restore_confirm, snapshot.label),
				getString(R.string.rms_snapshot_restore),
				getString(android.R.string.cancel),
				null,
				true,
				() -> restoreRmsSnapshot(snapshot),
				null,
				null
		);
	}

	private void createRmsSnapshot(File snapshotRoot) {
		startRmsOperation(Single.fromCallable(() -> {
			RmsSnapshotManager.create(dataDir, snapshotRoot, Long.toString(System.currentTimeMillis()));
			return true;
		}));
	}

	private void restoreRmsSnapshot(RmsSnapshotManager.Snapshot snapshot) {
		File snapshotRoot = new File(Config.getRmsSnapshotsDir(), dataDir.getName());
		String backupLabel = getString(R.string.rms_snapshot_before_restore);
		startRmsOperation(Single.fromCallable(() -> {
			RmsSnapshotManager.restoreWithBackup(
					snapshot, dataDir, snapshotRoot, backupLabel);
			return true;
		}));
	}

	private void startRmsOperation(Single<Boolean> operation) {
		if (rmsOperation != null) rmsOperation.dispose();
		rmsOperation = operation.subscribeOn(Schedulers.io())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(ignored -> Toast.makeText(this, R.string.rms_snapshot_done,
						Toast.LENGTH_SHORT).show(), error -> Toast.makeText(this,
						getString(R.string.rms_snapshot_failed, error.getMessage()), Toast.LENGTH_LONG).show());
	}

	@Override
	protected void onDestroy() {
		if (rmsOperation != null) {
			rmsOperation.dispose();
			rmsOperation = null;
		}
		super.onDestroy();
	}

	private void startMIDlet() {
		if (needShow && configDir != null) {
			saveParams();
		}
		Intent i = new Intent(this, MicroActivity.class);
		i.setData(getIntent().getData());
		i.putExtra(KEY_MIDLET_NAME, getIntent().getStringExtra(KEY_MIDLET_NAME));
		startActivity(i);
		finish();
	}

	@Override
	public void onScreenPresets() {
		showScreenPresets();
	}

	@Override
	public void onSwapSizes() {
		composeView.swapSizes();
	}

	@Override
	public void onAddResolution() {
		addResolutionToPresets();
	}

	@Override
	public void onFontPresets() {
		showFontPresets();
	}

	@Override
	public void onColorPicker(String field) {
		showColorPicker(field);
	}

	@Override
	public void onKeyMappings() {
		Intent i = new Intent(getIntent().getAction(), Uri.parse(configDir.getPath()),
				this, KeyMapperActivity.class);
		startActivity(i);
	}

	@Override
	public void onEncoding() {
		showCharsetPicker();
	}

	@Override
	public void onShaderTune() {
		showShaderSettings();
	}

	@Override
	public void onGraphicsModeSelected(int position) {
		if (position == 1) {
			initShaderSpinner();
		} else {
			composeView.setShaderTuningAvailable(false);
		}
	}

	@Override
	public void onShaderSelected(int position) {
		if (shaders == null || position < 0 || position >= shaders.size()) return;
		ShaderInfo item = shaders.get(position);
		ShaderInfo.Setting[] settings = item.settings;
		float[] values = item.values;
		if (values == null && settings != null) {
			for (int i = 0; i < 4; i++) {
				if (settings[i] != null) {
					if (values == null) values = new float[4];
					values[i] = settings[i].def;
				}
			}
		}
		if (values != null) item.values = values;
		composeView.setShaderTuningAvailable(values != null);
		params.shader = position == 0 ? null : item;
	}

	@Override
	public void onSecureConnectionSelected(int position) {
		if (position == SecureConnectionPolicy.MODE_INSECURE) {
			confirmInsecureSecureConnectionMode();
		} else {
			lastSafeSecureConnectionMode = position;
		}
	}

	@Override
	public void onShowKeyboardChanged(boolean visible) {
		// Visibility is derived from the Compose state; no imperative view group remains.
	}

	private void showFontPresets() {
		ComposeDialogHost.showChoice(
				this,
				getString(R.string.SIZE_PRESETS),
				fontPresetTitles.toArray(new String[0]),
				-1,
				getString(android.R.string.cancel),
				true,
				which -> {
					int[] values = fontPresetValues.get(which);
					composeView.setFontSmallText(Integer.toString(values[0]));
					composeView.setFontMediumText(Integer.toString(values[1]));
					composeView.setFontLargeText(Integer.toString(values[2]));
				}
		);
	}

	private void showScreenPresets() {
		String[] items = new String[screenPresets.size()];
		for (int i = 0; i < screenPresets.size(); i++) {
			items[i] = screenPresets.get(i).toString();
		}
		ComposeDialogHost.showChoice(
				this,
				getString(R.string.SIZE_PRESETS),
				items,
				-1,
				getString(android.R.string.cancel),
				true,
				which -> {
					Size size = screenPresets.get(which);
					composeView.setScreenWidthText(Integer.toString(size.width));
					composeView.setScreenHeightText(Integer.toString(size.height));
				}
		);
	}

	private void showColorPicker(String field) {
		int color;
		try {
			String value = switch (field) {
				case ConfigComposeView.COLOR_SCREEN_BACKGROUND -> composeView.getScreenBackgroundText();
				case ConfigComposeView.COLOR_VK_BACK -> composeView.getVkBackText();
				case ConfigComposeView.COLOR_VK_FORE -> composeView.getVkForeText();
				case ConfigComposeView.COLOR_VK_SELECTED_BACK -> composeView.getVkSelectedBackText();
				case ConfigComposeView.COLOR_VK_SELECTED_FORE -> composeView.getVkSelectedForeText();
				case ConfigComposeView.COLOR_VK_OUTLINE -> composeView.getVkOutlineText();
				default -> "";
			};
			color = Integer.parseInt(value.trim(), 16);
		} catch (NumberFormatException ignored) {
			color = 0;
		}
		ConfigComposeDialogHost.showColorPicker(
				this,
				color | 0xFF000000,
				getString(android.R.string.ok),
				getString(android.R.string.cancel),
				selectedColor -> composeView.setColorText(
						field,
						String.format(Locale.ROOT, "%06X", selectedColor & 0xFFFFFF)
				)
		);
	}

	private void addResolutionToPresets() {
		int w;
		int h;
		try {
			w = Integer.parseInt(composeView.getScreenWidthText());
			h = Integer.parseInt(composeView.getScreenHeightText());
		} catch (NumberFormatException e) {
			Toast.makeText(this, R.string.invalid_resolution_not_saved, Toast.LENGTH_SHORT).show();
			return;
		}
		if (w <= 0 || h <= 0) {
			Toast.makeText(this, R.string.invalid_resolution_not_saved, Toast.LENGTH_SHORT).show();
			return;
		}
		Size size = new Size(w, h);
		int index = Collections.binarySearch(screenPresets, size);
		if (index >= 0) {
			Toast.makeText(this, R.string.not_saved_exists, Toast.LENGTH_SHORT).show();
			return;
		}
		screenPresets.add(~index, size);
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		Set<String> set = preferences.getStringSet("ResolutionsPreset", null);
		Set<String> presets = set == null ? new HashSet<>(1) : new HashSet<>(set);
		presets.add(size.toString());
		preferences.edit().putStringSet("ResolutionsPreset", presets).apply();
		Toast.makeText(this, getString(R.string.saved, size.toString()), Toast.LENGTH_SHORT).show();
	}

}
