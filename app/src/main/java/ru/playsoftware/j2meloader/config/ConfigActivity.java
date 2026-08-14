/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2018-2019 Nikita Shakarun
 * Copyright 2019-2026 Yury Kharchenko
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

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.widget.TextViewCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import kotlin.io.FilesKt;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.model.Size;
import ru.playsoftware.j2meloader.databinding.ActivityConfigBinding;
import ru.playsoftware.j2meloader.settings.KeyMapperActivity;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.ViewUtils;
import ru.woesss.util.TextUtils;
import yuku.ambilwarna.AmbilWarnaDialog;

import static ru.playsoftware.j2meloader.config.ConfigFormEvents.ColorField;

public class ConfigActivity extends AppCompatActivity implements ShaderTuneAlert.Callback {
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
	private ActivityConfigBinding binding;

	private final ConfigFormEvents formEvents = new ConfigFormEvents() {
		@Override
		public void onScreenSizePresets() {
			showScreenPresets(binding.cmdScreenSizePresets);
		}

		@Override
		public void onSwapSizes() {
			swapScreenSizes();
		}

		@Override
		public void onAddResolutionPreset() {
			addResolutionToPresets();
		}

		@Override
		public void onFontSizePresets() {
			showFontSizePresets();
		}

		@Override
		public void onColorPicker(ColorField field) {
			showColorPicker(editTextFor(field));
		}

		@Override
		public void onKeyMappings() {
			openKeyMappings();
		}

		@Override
		public void onEncodingPicker() {
			showCharsetPicker(binding.btEncoding);
		}

		@Override
		public void onShaderTuning() {
			showShaderSettings(binding.btShaderTune);
		}
	};

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableIfSupported(this);
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
				new AlertDialog.Builder(this)
						.setTitle(R.string.error)
						.setMessage(getString(R.string.err_missing_app, storageName))
						.setPositiveButton(R.string.exit, (d, w) -> finish())
						.setCancelable(false)
						.show();
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
		binding = ActivityConfigBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		EdgeToEdgeCompat.protectHostContent(this);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		display = getWindowManager().getDefaultDisplay();

		fillScreenSizePresets(display.getWidth(), display.getHeight());

		addFontSizePreset("128 x 128", 9, 13, 15);
		addFontSizePreset("128 x 160", 13, 15, 20);
		addFontSizePreset("176 x 220", 15, 18, 22);
		addFontSizePreset("240 x 320", 18, 22, 26);

		binding.cbLockAspect.setOnCheckedChangeListener(this::onLockAspectChanged);
		binding.cmdScreenSizePresets.setOnClickListener(v -> formEvents.onScreenSizePresets());
		binding.cmdSwapSizes.setOnClickListener(v -> formEvents.onSwapSizes());
		binding.cmdAddToPreset.setOnClickListener(v -> formEvents.onAddResolutionPreset());
		binding.cmdFontSizePresets.setOnClickListener(v -> formEvents.onFontSizePresets());
		binding.cmdScreenBack.setOnClickListener(v -> formEvents.onColorPicker(ColorField.SCREEN_BACKGROUND));
		binding.cmdKeyMappings.setOnClickListener(v -> formEvents.onKeyMappings());
		binding.cmdVKBack.setOnClickListener(v -> formEvents.onColorPicker(ColorField.VIRTUAL_KEYBOARD_BACKGROUND));
		binding.cmdVKFore.setOnClickListener(v -> formEvents.onColorPicker(ColorField.VIRTUAL_KEYBOARD_FOREGROUND));
		binding.cmdVKSelBack.setOnClickListener(v -> formEvents.onColorPicker(ColorField.VIRTUAL_KEYBOARD_SELECTED_BACKGROUND));
		binding.cmdVKSelFore.setOnClickListener(v -> formEvents.onColorPicker(ColorField.VIRTUAL_KEYBOARD_SELECTED_FOREGROUND));
		binding.cmdVKOutline.setOnClickListener(v -> formEvents.onColorPicker(ColorField.VIRTUAL_KEYBOARD_OUTLINE));
		binding.btEncoding.setOnClickListener(v -> formEvents.onEncodingPicker());
		binding.btShaderTune.setOnClickListener(v -> formEvents.onShaderTuning());
		binding.tfScaleRatioValue.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				int length = s.length();
				if (length > 4) {
					int st = Math.min(start + count, 4);
					int end = st + length - 4;
					binding.tfScaleRatioValue.getText().delete(st, end);
				}
			}

			@Override
			public void afterTextChanged(Editable s) {
				if (s.length() == 0) return;
				try {
					int progress = Integer.parseInt(s.toString());
					if (progress > 1000) {
						s.replace(0, s.length(), "1000");
					}
				} catch (NumberFormatException e) {
					s.clear();
				}
			}
		});
		binding.spGraphicsMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				switch (position) {
					case 0, 3 -> {
						binding.cxParallel.setVisibility(View.VISIBLE);
						binding.shaderContainer.setVisibility(View.GONE);
					}
					case 1 -> {
						binding.cxParallel.setVisibility(View.GONE);
						binding.shaderContainer.setVisibility(View.VISIBLE);
						initShaderSpinner();
					}
					case 2 -> {
						binding.cxParallel.setVisibility(View.GONE);
						binding.shaderContainer.setVisibility(View.GONE);
					}
				}
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});
		binding.spShader.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				ShaderInfo item = (ShaderInfo) parent.getItemAtPosition(position);
				ShaderInfo.Setting[] settings = item.settings;
				float[] values = item.values;
				if (values == null) {
					for (int i = 0; i < 4; i++) {
						if (settings[i] != null) {
							if (values == null) {
								values = new float[4];
							}
							values[i] = settings[i].def;
						}
					}
				}
				if (values == null) {
					binding.btShaderTune.setVisibility(View.GONE);
				} else {
					item.values = values;
					binding.btShaderTune.setVisibility(View.VISIBLE);
				}
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});
		binding.cxIsShowKeyboard.setOnClickListener((b) -> {
			View.OnLayoutChangeListener onLayoutChangeListener = new View.OnLayoutChangeListener() {
				@Override
				public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
					View focus = binding.getRoot().findFocus();
					if (focus != null) focus.clearFocus();
					v.scrollTo(0, binding.rootConfigInput.getTop());
					v.removeOnLayoutChangeListener(this);
				}
			};
			binding.getRoot().addOnLayoutChangeListener(onLayoutChangeListener);
			binding.groupVkConfig.setVisibility(binding.cxIsShowKeyboard.isChecked() ? View.VISIBLE : View.GONE);
		});
		binding.tfScreenBack.addTextChangedListener(new ColorTextWatcher(binding.tfScreenBack));
		binding.tfVKFore.addTextChangedListener(new ColorTextWatcher(binding.tfVKFore));
		binding.tfVKBack.addTextChangedListener(new ColorTextWatcher(binding.tfVKBack));
		binding.tfVKSelFore.addTextChangedListener(new ColorTextWatcher(binding.tfVKSelFore));
		binding.tfVKSelBack.addTextChangedListener(new ColorTextWatcher(binding.tfVKSelBack));
		binding.tfVKOutline.addTextChangedListener(new ColorTextWatcher(binding.tfVKOutline));
		TooltipCompat.setTooltipText(binding.cxSkipResumeCall, getString(R.string.tooltip_skip_resume_call));
		initSoundBankSpinner();
		initSkinSpinner();
	}

	private void initSkinSpinner() {
		File dir = new File(workDir + Config.SKINS_DIR);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		ArrayAdapter<String> skinAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
		skinAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		binding.spSkin.setAdapter(skinAdapter);
		skinAdapter.add(getString(R.string.pref_skin_not_set));
		String[] files = dir.list((d, n) -> new File(d, n).isFile());
		if (files != null) {
			Arrays.sort(files, (o1, o2) -> {
				int res = o1.compareToIgnoreCase(o2);
				return res != 0 ? res : o1.compareTo(o2);
			});
			skinAdapter.addAll(files);
		}
		skinAdapter.notifyDataSetChanged();
	}

	private void initSoundBankSpinner() {
		File dir = new File(workDir + Config.SOUNDBANKS_DIR);
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		binding.spSoundBank.setAdapter(adapter);
		adapter.add(getString(R.string.default_label, "Android"));
		String[] files = dir.list((d, n) -> new File(d, n).isFile());
		if (files != null) {
			Arrays.sort(files, (o1, o2) -> {
				int res = o1.compareToIgnoreCase(o2);
				return res != 0 ? res : o1.compareTo(o2);
			});
			adapter.addAll(files);
		}
		adapter.notifyDataSetChanged();
	}

	private void setSpinnerSelection(Spinner spinner, String item) {
		//noinspection unchecked
		ArrayAdapter<String> adapter = (ArrayAdapter<String>) spinner.getAdapter();
		spinner.setSelection(Math.max(adapter.getPosition(item), 0));
	}

	private void onLockAspectChanged(CompoundButton cb, boolean isChecked) {
		if (isChecked) {
			float w;
			try {
				w = Integer.parseInt(binding.tfScreenWidth.getText().toString());
			} catch (Exception ignored) {
				w = 0;
			}
			if (w <= 0) {
				cb.setChecked(false);
				return;
			}
			float h;
			try {
				h = Integer.parseInt(binding.tfScreenHeight.getText().toString());
			} catch (Exception ignored) {
				h = 0;
			}
			if (h <= 0) {
				cb.setChecked(false);
				return;
			}
			float finalW = w;
			float finalH = h;
			binding.tfScreenWidth.setOnFocusChangeListener(new ResolutionAutoFill(binding.tfScreenWidth, binding.tfScreenHeight, finalH / finalW));
			binding.tfScreenHeight.setOnFocusChangeListener(new ResolutionAutoFill(binding.tfScreenHeight, binding.tfScreenWidth, finalW / finalH));

		} else {
			View.OnFocusChangeListener listener = binding.tfScreenWidth.getOnFocusChangeListener();
			if (listener != null) {
				listener.onFocusChange(binding.tfScreenWidth, false);
				binding.tfScreenWidth.setOnFocusChangeListener(null);
			}
			listener = binding.tfScreenHeight.getOnFocusChangeListener();
			if (listener != null) {
				listener.onFocusChange(binding.tfScreenHeight, false);
				binding.tfScreenHeight.setOnFocusChangeListener(null);
			}
		}
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

	private void showShaderSettings(View v) {
		ShaderInfo shader = (ShaderInfo) binding.spShader.getSelectedItem();
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
		ArrayAdapter<ShaderInfo> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, shaders);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		binding.spShader.setAdapter(adapter);
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
		adapter.notifyDataSetChanged();
		ShaderInfo selected = params.shader;
		if (selected != null) {
			int position = shaders.indexOf(selected);
			if (position > 0) {
				shaders.get(position).values = selected.values;
				binding.spShader.setSelection(position);
			}
		}
	}

	private void showCharsetPicker(View v) {
		String[] charsets = Charset.availableCharsets().keySet().toArray(new String[0]);
		new AlertDialog.Builder(this).setItems(charsets, (d, w) -> {
			String text = binding.tfSystemProperties.getText().toString();
			String key = "microedition.encoding:";
			int idx = text.lastIndexOf(key);
			if (idx != -1) {
				int nl = text.indexOf('\n', idx);
				text = text.substring(0, idx + key.length()) + " " + charsets[w] + (nl == -1 ? "\n" : text.substring(nl));
				binding.tfSystemProperties.setText(text);
				return;
			}

			if (!text.endsWith("\n")) {
				binding.tfSystemProperties.append("\n");
			}
			binding.tfSystemProperties.append(key);
			binding.tfSystemProperties.append(" ");
			binding.tfSystemProperties.append(charsets[w]);
			binding.tfSystemProperties.append("\n");
		}).setTitle(R.string.pref_encoding_title).show();
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

	@SuppressLint("SetTextI18n")
	public void loadParams(boolean reloadFromFile) {
		if (reloadFromFile) {
			loadConfig();
		}
		int screenWidth = params.screenWidth;
		if (screenWidth != 0) {
			binding.tfScreenWidth.setText(Integer.toString(screenWidth));
		}
		int screenHeight = params.screenHeight;
		if (screenHeight != 0) {
			binding.tfScreenHeight.setText(Integer.toString(screenHeight));
		}
		binding.tfScreenBack.setText(String.format("%06X", params.screenBackgroundColor));
		setSpinnerSelection(binding.spSkin, params.screenBackgroundImage);
		binding.tfScaleRatioValue.setText(Integer.toString(params.screenScaleRatio));
		binding.spOrientation.setSelection(params.orientation);
		binding.spScaleType.setSelection(params.screenScaleType);
		binding.spScreenGravity.setSelection(params.screenGravity);
		binding.etScreenPadding.setText(Integer.toString(params.screenPadding));
		binding.cxFilter.setChecked(params.screenFilter);
		binding.cxImmediate.setChecked(params.immediateMode);
		binding.cxParallel.setChecked(params.parallelRedrawScreen);
		binding.cxForceFullscreen.setChecked(params.forceFullscreen);
		binding.spGraphicsMode.setSelection(params.graphicsMode);
		if (shaders != null) {
			int position = shaders.indexOf(params.shader);
			if (position > 0) {
				shaders.get(position).values =  params.shader.values;
				binding.spShader.setSelection(position);
			} else {
				binding.spShader.setSelection(0);
			}
		}
		binding.cxShowFps.setChecked(params.showFps);

		binding.tfFontSizeSmall.setText(Integer.toString(params.fontSizeSmall));
		binding.tfFontSizeMedium.setText(Integer.toString(params.fontSizeMedium));
		binding.tfFontSizeLarge.setText(Integer.toString(params.fontSizeLarge));
		binding.cxFontSizeInSP.setChecked(params.fontApplyDimensions);
		binding.cxFontAA.setChecked(params.fontAA);
		boolean showVk = params.showKeyboard;
		binding.cxIsShowKeyboard.setChecked(showVk);
		binding.groupVkConfig.setVisibility(showVk ? View.VISIBLE : View.GONE);
		binding.cxVKFeedback.setChecked(params.vkFeedback);
		binding.cxVKForceOpacity.setChecked(params.vkForceOpacity);
		binding.cxTouchInput.setChecked(params.touchInput);
		int fpsLimit = params.fpsLimit;
		binding.etFpsLimit.setText(fpsLimit > 0 ? Integer.toString(fpsLimit) : "");

		binding.spLayout.setSelection(params.keyCodesLayout);
		binding.spButtonsShape.setSelection(params.vkButtonShape);
		binding.sbVKAlpha.setProgress(params.vkAlpha);
		int vkHideDelay = params.vkHideDelay;
		binding.tfVKHideDelay.setText(vkHideDelay > 0 ? Integer.toString(vkHideDelay) : "");

		binding.tfVKBack.setText(String.format("%06X", params.vkBgColor));
		binding.tfVKFore.setText(String.format("%06X", params.vkFgColor));
		binding.tfVKSelBack.setText(String.format("%06X", params.vkBgColorSelected));
		binding.tfVKSelFore.setText(String.format("%06X", params.vkFgColorSelected));
		binding.tfVKOutline.setText(String.format("%06X", params.vkOutlineColor));

		binding.cxSkipResumeCall.setChecked(params.skipResumeCall);
		setSpinnerSelection(binding.spSoundBank, params.soundBank);

		String systemProperties = params.systemProperties;
		if (systemProperties == null) {
			systemProperties = ContextHolder.getAssetAsString("defaults/system.props");
		}
		binding.tfSystemProperties.setText(ConfigFormState.normalizeSystemProperties(systemProperties));
	}

	private void saveParams() {
		try {
			readFormState().applyTo(params);
			ProfilesManager.saveConfig(params);
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private ConfigFormState readFormState() {
		ConfigFormState.Builder state = ConfigFormState.builder()
				.screenWidth(binding.tfScreenWidth.getText().toString())
				.screenHeight(binding.tfScreenHeight.getText().toString())
				.screenBackground(binding.tfScreenBack.getText().toString())
				.screenScaleRatio(binding.tfScaleRatioValue.getText().toString())
				.screenPadding(binding.etScreenPadding.getText().toString())
				.fpsLimit(binding.etFpsLimit.getText().toString())
				.fontSizeSmall(binding.tfFontSizeSmall.getText().toString())
				.fontSizeMedium(binding.tfFontSizeMedium.getText().toString())
				.fontSizeLarge(binding.tfFontSizeLarge.getText().toString())
				.vkHideDelay(binding.tfVKHideDelay.getText().toString())
				.vkBackground(binding.tfVKBack.getText().toString())
				.vkForeground(binding.tfVKFore.getText().toString())
				.vkSelectedBackground(binding.tfVKSelBack.getText().toString())
				.vkSelectedForeground(binding.tfVKSelFore.getText().toString())
				.vkOutline(binding.tfVKOutline.getText().toString())
				.systemProperties(binding.tfSystemProperties.getText().toString())
				.orientation(binding.spOrientation.getSelectedItemPosition())
				.screenScaleType(binding.spScaleType.getSelectedItemPosition())
				.screenGravity(binding.spScreenGravity.getSelectedItemPosition())
				.graphicsMode(binding.spGraphicsMode.getSelectedItemPosition())
				.keyCodesLayout(binding.spLayout.getSelectedItemPosition())
				.vkButtonShape(binding.spButtonsShape.getSelectedItemPosition())
				.vkAlpha(binding.sbVKAlpha.getProgress())
				.screenFilter(binding.cxFilter.isChecked())
				.immediateMode(binding.cxImmediate.isChecked())
				.parallelRedrawScreen(binding.cxParallel.isChecked())
				.forceFullscreen(binding.cxForceFullscreen.isChecked())
				.showFps(binding.cxShowFps.isChecked())
				.fontApplyDimensions(binding.cxFontSizeInSP.isChecked())
				.fontAA(binding.cxFontAA.isChecked())
				.showKeyboard(binding.cxIsShowKeyboard.isChecked())
				.vkFeedback(binding.cxVKFeedback.isChecked())
				.vkForceOpacity(binding.cxVKForceOpacity.isChecked())
				.touchInput(binding.cxTouchInput.isChecked())
				.skipResumeCall(binding.cxSkipResumeCall.isChecked());
		if (binding.spSkin.getSelectedItemPosition() > 0) {
			state.screenBackgroundImage((String) binding.spSkin.getSelectedItem());
		}
		if (binding.spSoundBank.getSelectedItemPosition() > 0) {
			state.soundBank((String) binding.spSoundBank.getSelectedItem());
		}
		if (binding.spShader.getSelectedItemPosition() > 0) {
			state.shader((ShaderInfo) binding.spShader.getSelectedItem());
		}
		return state.build();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.config, menu);
		if (isProfile) {
			menu.findItem(R.id.action_start).setVisible(false);
			menu.findItem(R.id.action_clear_data).setVisible(false);
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.action_start) {
			startMIDlet();
		} else if (itemId == R.id.action_clear_data) {
			showClearDataDialog();
		} else if (itemId == R.id.action_reset_settings) {
			params = new ProfileModel(configDir);
			loadParams(false);
		} else if (itemId == R.id.action_reset_layout) {
			//noinspection ResultOfMethodCallIgnored
			keylayoutFile.delete();
			loadKeyLayout();
		} else if (itemId == R.id.action_load_profile) {
			LoadProfileAlert.newInstance(keylayoutFile.getParent())
					.show(getSupportFragmentManager(), "load_profile");
		} else if (itemId == R.id.action_save_profile) {
			saveParams();
			SaveProfileAlert.getInstance(keylayoutFile.getParent())
					.show(getSupportFragmentManager(), "save_profile");
		} else if (itemId == android.R.id.home) {
			finish();
		} else {
			return false;
		}
		return true;
	}

	private void showClearDataDialog() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(android.R.string.dialog_alert_title)
				.setMessage(R.string.message_clear_data)
				.setPositiveButton(android.R.string.ok, (d, w) -> FileUtils.clearDirectory(dataDir))
				.setNegativeButton(android.R.string.cancel, null);
		builder.show();
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

	@SuppressLint("SetTextI18n")
	private void swapScreenSizes() {
		String tmp = binding.tfScreenWidth.getText().toString();
		binding.tfScreenWidth.setText(binding.tfScreenHeight.getText().toString());
		binding.tfScreenHeight.setText(tmp);
	}

	@SuppressLint("SetTextI18n")
	private void showFontSizePresets() {
		new AlertDialog.Builder(this)
				.setTitle(getString(R.string.SIZE_PRESETS))
				.setItems(fontPresetTitles.toArray(new String[0]),
						(dialog, which) -> {
							int[] values = fontPresetValues.get(which);
							binding.tfFontSizeSmall.setText(Integer.toString(values[0]));
							binding.tfFontSizeMedium.setText(Integer.toString(values[1]));
							binding.tfFontSizeLarge.setText(Integer.toString(values[2]));
						})
				.show();
	}

	private EditText editTextFor(ConfigFormEvents.ColorField field) {
		return switch (field) {
			case SCREEN_BACKGROUND -> binding.tfScreenBack;
			case VIRTUAL_KEYBOARD_BACKGROUND -> binding.tfVKBack;
			case VIRTUAL_KEYBOARD_FOREGROUND -> binding.tfVKFore;
			case VIRTUAL_KEYBOARD_SELECTED_BACKGROUND -> binding.tfVKSelBack;
			case VIRTUAL_KEYBOARD_SELECTED_FOREGROUND -> binding.tfVKSelFore;
			case VIRTUAL_KEYBOARD_OUTLINE -> binding.tfVKOutline;
		};
	}

	private void openKeyMappings() {
		Intent i = new Intent(getIntent().getAction(), Uri.parse(configDir.getPath()),
				this, KeyMapperActivity.class);
		startActivity(i);
	}

	@SuppressLint("SetTextI18n")
	private void showScreenPresets(View v) {
		ListPopupWindow popup = new ListPopupWindow(this);
		popup.setAnchorView(v);
		popup.setModal(true);
		ArrayAdapter<Size> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, screenPresets);
		popup.setAdapter(adapter);
		final Resources res = getResources();
		int maxWidth = res.getDisplayMetrics().widthPixels;
		popup.setWidth(ViewUtils.measureListViewWidth(adapter, null, this, maxWidth));
		popup.setOnItemClickListener((parent, view, position, id) -> {
			Size size = ((Size) parent.getItemAtPosition(position));
			binding.tfScreenWidth.setText(Integer.toString(size.width));
			binding.tfScreenHeight.setText(Integer.toString(size.height));
			popup.dismiss();
		});
		popup.show();
	}

	private void showColorPicker(EditText et) {
		AmbilWarnaDialog.OnAmbilWarnaListener colorListener = new AmbilWarnaDialog.OnAmbilWarnaListener() {
			@SuppressLint("NewApi")
			@Override
			public void onOk(AmbilWarnaDialog dialog, int color) {
				et.setText(String.format("%06X", color & 0xFFFFFF));
				ColorDrawable drawable = (ColorDrawable) TextViewCompat.getCompoundDrawablesRelative(et)[2];
				drawable.setColor(color);
			}

			@Override
			public void onCancel(AmbilWarnaDialog dialog) {
			}
		};

		int color;
		try {
			color = Integer.parseInt(et.getText().toString().trim(), 16);
		} catch (NumberFormatException ignored) {
			color = 0;
		}
		new AmbilWarnaDialog(this, color | 0xFF000000, colorListener).show();
	}

	private void addResolutionToPresets() {
		int w;
		int h;
		try {
			w = Integer.parseInt(binding.tfScreenWidth.getText().toString());
			h = Integer.parseInt(binding.tfScreenHeight.getText().toString());
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

	@Override
	public void onTuneComplete(float[] values) {
		params.shader.values = values;
	}

	private static class ColorTextWatcher implements TextWatcher {
		private final EditText editText;
		private final ColorDrawable drawable;

		@SuppressLint("NewApi")
		ColorTextWatcher(EditText editText) {
			this.editText = editText;
			int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32,
					editText.getResources().getDisplayMetrics());
			ColorDrawable colorDrawable = new ColorDrawable();
			colorDrawable.setBounds(0, 0, size, size);
			TextViewCompat.setCompoundDrawablesRelative(editText, null, null, colorDrawable, null);
			drawable = colorDrawable;
			editText.setFilters(new InputFilter[]{this::filter});
		}

		private CharSequence filter(CharSequence src, int ss, int se, Spanned dst, int ds, int de) {
			StringBuilder sb = new StringBuilder(se - ss);
			boolean changed = false;
			for (int i = ss; i < se; i++) {
				char c = src.charAt(i);
				if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) {
					sb.append(c);
				} else if (c >= 'a' && c <= 'f') {
					sb.append((char) (c - 32));
					changed = true;
				} else {
					changed = true;
				}
			}
			if (!changed) {
				return null;
			}
			return sb;
		}

		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		}

		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {
			int length = s.length();
			if (length > 6) {
				int st = Math.min(start + count, 6);
				int end = st + (length - 6);
				editText.getText().delete(st, end);
			}
		}

		@Override
		public void afterTextChanged(Editable s) {
			if (s.length() == 0) return;
			try {
				int color = Integer.parseInt(s.toString(), 16);
				drawable.setColor(color | Color.BLACK);
			} catch (NumberFormatException e) {
				drawable.setColor(Color.BLACK);
				s.clear();
			}
		}
	}

	private static class ResolutionAutoFill implements TextWatcher, View.OnFocusChangeListener {
		private final EditText src;
		private final EditText dst;
		private final float aspect;

		public ResolutionAutoFill(EditText src, EditText dst, float aspect) {
			this.src = src;
			this.dst = dst;
			this.aspect = aspect;
			if (src.hasFocus())
				src.addTextChangedListener(this);
		}

		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {

		}

		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {

		}

		@Override
		public void afterTextChanged(Editable s) {
			try {
				int size = Integer.parseInt(src.getText().toString());
				if (size <= 0) return;
				int value = Math.round(size * aspect);
				dst.setText(String.valueOf(value));
			} catch (NumberFormatException ignored) {}
		}

		public void onFocusChange(View v, boolean hasFocus) {
			if (hasFocus) {
				src.addTextChangedListener(this);
			} else {
				src.removeTextChangedListener(this);
			}
		}
	}
}
