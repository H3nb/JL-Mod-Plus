/*
 * Copyright 2018-2019 Nikita Shakarun
 * Copyright 2020-2023 Yury Kharchenko
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

import static io.github.h3nb.jlmodplus.util.Constants.ACTION_EDIT_PROFILE;
import static io.github.h3nb.jlmodplus.util.Constants.KEY_INSTALLED_APP_PATH;
import static io.github.h3nb.jlmodplus.util.Constants.KEY_LIBRARY_APP_ID;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.preference.PreferenceManager;

import com.google.gson.GsonBuilder;

import java.io.File;

import javax.microedition.lcdui.keyboard.KeyMapper;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.MidletConfigLoadBoundary;
import io.github.h3nb.jlmodplus.config.InstalledAppWriteGuard;
import io.github.h3nb.jlmodplus.config.PresetLocalOverride;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.config.ProfilesManager;
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat;
import io.github.h3nb.jlmodplus.util.SparseIntArrayAdapter;
import io.github.h3nb.jlmodplus.ui.ThemedToast;

public class KeyMapperActivity extends AppCompatActivity {
	private static final String KEY_SAVE = "KEY_MAP_SAVE";
	private static final String STATE_EXPECTED_APP_ID = "expected_library_app_id";
	private final SparseIntArray defaultKeyMap = KeyMapper.getDefaultKeyMap();
	SparseIntArray androidToMIDP;
	private SparseIntArray persistedEffectiveMap;
	private ProfileModel params;
	private File configDir;
	private File profilesRoot;
	private boolean namedProfile;
	private String installedAppPath;
	private long expectedAppId;
	private InstalledAppWriteGuard installedWriteGuard;
	private int canvasKey;
	private KeyMapperComposeController composeController;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableForComposeSurface(this);
		Intent intent = getIntent();
		String path = intent.getDataString();
		if (path == null) {
			ThemedToast.show(this, R.string.error, Toast.LENGTH_SHORT);
			finish();
			return;
		}
		ComposeView composeView = new ComposeView(this);
		setContentView(composeView);
		namedProfile = ACTION_EDIT_PROFILE.equals(intent.getAction());
		configDir = new File(path);
		SharedPreferences preferences = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext());
		if (!namedProfile) {
			installedAppPath = intent.getStringExtra(KEY_INSTALLED_APP_PATH);
			File convertedDir = installedAppPath == null
					? null : new File(installedAppPath).getParentFile();
			File workDir = convertedDir == null ? null : convertedDir.getParentFile();
			if (workDir == null) {
				ThemedToast.show(this, R.string.error, Toast.LENGTH_SHORT);
				finish();
				return;
			}
			profilesRoot = new File(workDir, "templates");
			expectedAppId = savedInstanceState == null
					? intent.getLongExtra(KEY_LIBRARY_APP_ID, 0L)
					: savedInstanceState.getLong(
							STATE_EXPECTED_APP_ID, intent.getLongExtra(KEY_LIBRARY_APP_ID, 0L));
			installedWriteGuard = InstalledAppWriteGuard.create(this);
		}
		if (!initializeConfig(preferences)) {
			ThemedToast.show(this, R.string.error, Toast.LENGTH_SHORT);
			finish();
			return;
		}

		SparseIntArray loadedEffective =
				KeyMapperMappingRules.resolve(defaultKeyMap, params.keyMappings);
		// Back is a host-reserved runtime control. Treat this editor normalization as the persisted
		// effective baseline so opening and closing an older profile is not a user-owned mutation.
		loadedEffective.put(KeyEvent.KEYCODE_BACK, KeyMapper.KEY_OPTIONS_MENU);
		persistedEffectiveMap = loadedEffective.clone();
		if (savedInstanceState == null) {
			androidToMIDP = loadedEffective;
		} else {
			String save = savedInstanceState.getString(KEY_SAVE);
			if (save == null || save.isEmpty()) {
				androidToMIDP = loadedEffective;
			} else {
				androidToMIDP = new GsonBuilder()
						.registerTypeAdapter(SparseIntArray.class, new SparseIntArrayAdapter())
						.create()
						.fromJson(save, SparseIntArray.class);
			}
		}
		androidToMIDP.put(KeyEvent.KEYCODE_BACK, KeyMapper.KEY_OPTIONS_MENU);
		composeController = new KeyMapperComposeController(composeView, new KeyMapperActions() {
			@Override
			public void onVirtualKey(int canvasKey) {
				showMappingDialog(canvasKey);
			}

			@Override
			public void onDismissMapping() {
				dismissMappingDialog();
			}

			@Override
			public void onRemoveMappedKey(int androidKeyCode) {
				if (androidKeyCode == KeyEvent.KEYCODE_BACK) return;
				androidToMIDP = KeyMapperMappingRules.removeBinding(androidToMIDP, androidKeyCode);
				showMappingDialog(canvasKey);
			}

			@Override
			public void onBack() {
				getOnBackPressedDispatcher().onBackPressed();
			}

			@Override
			public void onResetMapping() {
				androidToMIDP = defaultKeyMap.clone();
			}

			@Override
			public void onSaveAndExit() {
				composeController.hideMenuKeyWarning();
				if (save()) finish();
			}

			@Override
			public void onDismissWarning() {
				composeController.hideMenuKeyWarning();
			}
		});
		getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (!KeyMapperMappingRules.containsValue(androidToMIDP, KeyMapper.KEY_OPTIONS_MENU)) {
					composeController.showMenuKeyWarning();
					return;
				}
				if (save()) finish();
			}
		});
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		if (!namedProfile && expectedAppId > 0L) {
			outState.putLong(STATE_EXPECTED_APP_ID, expectedAppId);
		}
		if (!KeyMapperMappingRules.equalMaps(persistedEffectiveMap, androidToMIDP)) {
			String currMap = new GsonBuilder()
					.registerTypeAdapter(SparseIntArray.class, new SparseIntArrayAdapter())
					.create()
					.toJson(androidToMIDP);
			outState.putString(KEY_SAVE, currMap);
		} else {
			outState.putString(KEY_SAVE, "");
		}

		super.onSaveInstanceState(outState);
	}

	private void showMappingDialog(int canvasKey) {
		this.canvasKey = canvasKey;
		java.util.ArrayList<KeyMapperAssignedInput> assigned = new java.util.ArrayList<>();
		for (int index = 0; index < androidToMIDP.size(); index++) {
			if (androidToMIDP.valueAt(index) == canvasKey) {
				int androidKeyCode = androidToMIDP.keyAt(index);
				assigned.add(new KeyMapperAssignedInput(
						androidKeyCode, KeyEvent.keyCodeToString(androidKeyCode)));
			}
		}
		composeController.showMappingDialog(canvasKey, assigned);
	}

	private void dismissMappingDialog() {
		composeController.hideMappingDialog();
	}


	boolean save() {
		if (KeyMapperMappingRules.equalMaps(persistedEffectiveMap, androidToMIDP)) {
			return true;
		}
		SparseIntArray newMap = KeyMapperMappingRules.diff(defaultKeyMap, androidToMIDP);
		SparseIntArray oldMap = params.keyMappings;
		if (newMap.size() == 0) {
			newMap = null;
		}

		params.keyMappings = newMap;
		boolean saved;
		if (namedProfile) {
			saved = ProfilesManager.saveConfig(params);
		} else {
			InstalledAppWriteGuard.Result result = runInstalledWrite(() ->
					PresetLocalOverride.runDetachedWrite(
							this, configDir, () -> ProfilesManager.saveConfig(params)));
			saved = result == InstalledAppWriteGuard.Result.SUCCESS;
		}
		if (saved) {
			persistedEffectiveMap = androidToMIDP.clone();
			return true;
		}
		params.keyMappings = oldMap;
		ThemedToast.show(this, R.string.error, Toast.LENGTH_SHORT);
		return false;
	}

	private boolean initializeConfig(@NonNull SharedPreferences preferences) {
		if (namedProfile) return loadConfig(preferences);
		InstalledAppWriteGuard.Result result = runInstalledWrite(() ->
				MidletConfigLoadBoundary.prepare(preferences, configDir, profilesRoot)
						&& loadConfig(preferences));
		return result == InstalledAppWriteGuard.Result.SUCCESS;
	}

	private boolean loadConfig(@NonNull SharedPreferences preferences) {
		boolean legacyThemeLinked = !namedProfile && preferences
				.getBoolean(ProfileModel.builtInThemePreferenceKey(configDir), false);
		params = ProfilesManager.loadConfig(configDir, true,
				namedProfile
						? ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE
						: ProfilesManager.BackgroundMigrationContext.MIDLET_CONFIG,
				legacyThemeLinked);
		return params != null;
	}

	private InstalledAppWriteGuard.Result runInstalledWrite(
			@NonNull InstalledAppWriteGuard.WriteOperation operation) {
		if (installedWriteGuard == null || installedAppPath == null || expectedAppId <= 0L) {
			return InstalledAppWriteGuard.Result.STALE;
		}
		InstalledAppWriteGuard.Result result = installedWriteGuard.run(
				installedAppPath, expectedAppId, operation);
		if (result == InstalledAppWriteGuard.Result.STALE) finish();
		return result;
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (composeController != null && composeController.isMappingDialogVisible()
				&& KeyMapperDispatchRules.isAssignableKey(event.getAction(), event.getKeyCode())) {
			androidToMIDP = KeyMapperMappingRules.assign(
					androidToMIDP, canvasKey, event.getKeyCode());
			dismissMappingDialog();
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		if (composeController != null && composeController.isMappingDialogVisible()
				&& event.getAction() == MotionEvent.ACTION_DOWN) {
			if (!KeyMapperDispatchRules.isInsidePopup(
					composeController.getPopupBounds(), (int) event.getX(), (int) event.getY())) {
				dismissMappingDialog();
			}
			return true;
		}
		return super.dispatchTouchEvent(event);
	}
}
