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
import io.github.h3nb.jlmodplus.config.PresetLocalOverride;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.config.ProfilesManager;
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat;
import io.github.h3nb.jlmodplus.util.SparseIntArrayAdapter;
import io.github.h3nb.jlmodplus.ui.ThemedToast;

public class KeyMapperActivity extends AppCompatActivity {
	private static final String KEY_SAVE = "KEY_MAP_SAVE";
	private final SparseIntArray defaultKeyMap = KeyMapper.getDefaultKeyMap();
	private SparseIntArray androidToMIDP;
	private ProfileModel params;
	private File configDir;
	private boolean namedProfile;
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
		if (!namedProfile && !MidletConfigLoadBoundary.prepare(preferences, configDir)) {
			ThemedToast.show(this, R.string.error, Toast.LENGTH_SHORT);
			finish();
			return;
		}
		boolean legacyThemeLinked = !namedProfile && preferences
				.getBoolean(ProfileModel.builtInThemePreferenceKey(configDir), false);
		params = ProfilesManager.loadConfig(configDir, true,
				namedProfile
						? ProfilesManager.BackgroundMigrationContext.NAMED_PROFILE
						: ProfilesManager.BackgroundMigrationContext.MIDLET_CONFIG,
				legacyThemeLinked);

		if (savedInstanceState == null) {
			androidToMIDP = KeyMapperMappingRules.resolve(defaultKeyMap, params.keyMappings);
		} else {
			String save = savedInstanceState.getString(KEY_SAVE);
			if (save == null || save.isEmpty()) {
				androidToMIDP = KeyMapperMappingRules.resolve(defaultKeyMap, params.keyMappings);
			} else {
				androidToMIDP = new GsonBuilder()
						.registerTypeAdapter(SparseIntArray.class, new SparseIntArrayAdapter())
						.create()
						.fromJson(save, SparseIntArray.class);
			}
		}
		// Back is a host-reserved runtime control. Normalize older profile overrides in the
		// editor too, so the visible/effective map agrees with runtime dispatch.
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
		SparseIntArray currentOverrides = KeyMapperMappingRules.diff(defaultKeyMap, androidToMIDP);
		SparseIntArray persistedOverrides = currentOverrides.size() == 0 ? null : currentOverrides;
		if (!KeyMapperMappingRules.equalMaps(params.keyMappings, persistedOverrides)) {
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


	private boolean save() {
		SparseIntArray newMap = KeyMapperMappingRules.diff(defaultKeyMap, androidToMIDP);
		SparseIntArray oldMap = params.keyMappings;
		if (newMap.size() == 0) {
			newMap = null;
		}
		if (KeyMapperMappingRules.equalMaps(oldMap, newMap)) {
			return true;
		}

		PresetLocalOverride.Guard ownership = null;
		if (!namedProfile) {
			ownership = PresetLocalOverride.detachBeforeWrite(this, configDir);
			if (!ownership.canWrite()) {
				ThemedToast.show(this, R.string.error, Toast.LENGTH_SHORT);
				return false;
			}
		}

		params.keyMappings = newMap;
		if (ProfilesManager.saveConfig(params)) {
			return true;
		}
		params.keyMappings = oldMap;
		if (ownership != null) ownership.restoreIfUnchanged();
		ThemedToast.show(this, R.string.error, Toast.LENGTH_SHORT);
		return false;
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
