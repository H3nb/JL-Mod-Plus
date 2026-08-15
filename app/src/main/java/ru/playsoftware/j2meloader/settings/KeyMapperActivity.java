/*
 * Copyright 2018-2019 Nikita Shakarun
 * Copyright 2020-2023 Yury Kharchenko
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

import android.content.Intent;
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

import com.google.gson.GsonBuilder;

import java.io.File;

import javax.microedition.lcdui.keyboard.KeyMapper;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.ProfileModel;
import ru.playsoftware.j2meloader.config.ProfilesManager;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;
import ru.playsoftware.j2meloader.util.SparseIntArrayAdapter;

public class KeyMapperActivity extends AppCompatActivity {
	private static final String KEY_SAVE = "KEY_MAP_SAVE";
	private final SparseIntArray defaultKeyMap = KeyMapper.getDefaultKeyMap();
	private SparseIntArray androidToMIDP;
	private ProfileModel params;
	private int canvasKey;
	private KeyMapperComposeController composeController;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableIfSupported(this);
		Intent intent = getIntent();
		String path = intent.getDataString();
		if (path == null) {
			Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show();
			finish();
			return;
		}
		ComposeView composeView = new ComposeView(this);
		setContentView(composeView);
		EdgeToEdgeCompat.protectHostContent(this);
		if (getSupportActionBar() != null) {
			getSupportActionBar().hide();
		}
		params = ProfilesManager.loadConfig(new File(path));

		if (savedInstanceState == null) {
			SparseIntArray keyMap = params.keyMappings;
			androidToMIDP = keyMap == null ? defaultKeyMap.clone() : keyMap.clone();
		} else {
			String save = savedInstanceState.getString(KEY_SAVE);
			if (save == null) {
				androidToMIDP = defaultKeyMap.clone();
			} else if (save.isEmpty()) {
				SparseIntArray keyMap = params.keyMappings;
				androidToMIDP = keyMap == null ? defaultKeyMap.clone() : keyMap.clone();
			} else {
				androidToMIDP = new GsonBuilder()
						.registerTypeAdapter(SparseIntArray.class, new SparseIntArrayAdapter())
						.create()
						.fromJson(save, SparseIntArray.class);
			}
		}
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
				save();
				finish();
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
				save();
				finish();
			}
		});
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		if (!KeyMapperMappingRules.equalMaps(androidToMIDP, defaultKeyMap)) {
			if (!KeyMapperMappingRules.equalMaps(params.keyMappings, androidToMIDP)) {
				String currMap = new GsonBuilder()
						.registerTypeAdapter(SparseIntArray.class, new SparseIntArrayAdapter())
						.create()
						.toJson(androidToMIDP);
				outState.putString(KEY_SAVE, currMap);
			} else {
				outState.putString(KEY_SAVE, "");
			}
		}

		super.onSaveInstanceState(outState);
	}

	private void showMappingDialog(int canvasKey) {
		this.canvasKey = canvasKey;
		SparseIntArray androidToMIDP = this.androidToMIDP;
		int idx = androidToMIDP.indexOfValue(canvasKey);
		String keyName;
		if (idx < 0) {
			keyName = getString(R.string.mapping_dialog_key_not_specified);
		} else {
			keyName = KeyEvent.keyCodeToString(androidToMIDP.keyAt(idx));
		}
		composeController.showMappingDialog(canvasKey, keyName);
	}

	private void dismissMappingDialog() {
		composeController.hideMappingDialog();
	}


	private void save() {
		SparseIntArray newMap = androidToMIDP;
		SparseIntArray oldMap = params.keyMappings;
		if (KeyMapperMappingRules.equalMaps(newMap, defaultKeyMap)) {
			newMap = null;
		}
		if (!KeyMapperMappingRules.equalMaps(oldMap, newMap)) {
			params.keyMappings = newMap;
			ProfilesManager.saveConfig(params);
		}
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
