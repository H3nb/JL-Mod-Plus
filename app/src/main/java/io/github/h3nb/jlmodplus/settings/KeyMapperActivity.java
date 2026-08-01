/*
 * Copyright 2018-2019 Nikita Shakarun
 * Copyright 2020-2023 Yury Kharchenko
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

import android.content.Intent;
import android.os.Bundle;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.GsonBuilder;

import java.io.File;

import javax.microedition.lcdui.keyboard.KeyMapper;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.ui.ComposeDialogHost;
import io.github.h3nb.jlmodplus.config.ProfileModel;
import io.github.h3nb.jlmodplus.config.ProfilesManager;
import io.github.h3nb.jlmodplus.util.SparseIntArrayAdapter;

public class KeyMapperActivity extends AppCompatActivity implements KeyMapperComposeView.Callback {
	private static final String KEY_SAVE = "KEY_MAP_SAVE";
	private final SparseIntArray defaultKeyMap = KeyMapper.getDefaultKeyMap();
	private SparseIntArray androidToMIDP;
	private ProfileModel params;
	private int canvasKey;
	private KeyMapperComposeView composeView;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		String path = intent.getDataString();
		if (path == null) {
			Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show();
			finish();
			return;
		}
		composeView = new KeyMapperComposeView(this, this);
		setContentView(composeView);
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
		getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (androidToMIDP.indexOfValue(KeyMapper.KEY_OPTIONS_MENU) < 0) {
					alertMenuKey();
					return;
				}
				save();
				finish();
			}
		});
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		if (!equalMaps(androidToMIDP, defaultKeyMap)) {
			if (!equalMaps(params.keyMappings, androidToMIDP)) {
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

	@Override
	public void onKeyClick(int canvasKey) {
		showMappingDialog(canvasKey);
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
		composeView.showMappingDialog(getString(R.string.mapping_dialog_message, keyName));
	}

	private void deleteDuplicates(int value) {
		SparseIntArray androidToMIDP = this.androidToMIDP;
		for (int i = androidToMIDP.size() - 1; i >= 0; i--) {
			if (androidToMIDP.valueAt(i) == value) {
				androidToMIDP.removeAt(i);
			}
		}
	}

	@Override
	public void onBack() {
		getOnBackPressedDispatcher().onBackPressed();
	}

	@Override
	public void onResetMapping() {
		androidToMIDP = defaultKeyMap.clone();
	}

	private void save() {
		SparseIntArray newMap = androidToMIDP;
		SparseIntArray oldMap = params.keyMappings;
		if (equalMaps(newMap, defaultKeyMap)) {
			newMap = null;
		}
		if (!equalMaps(oldMap, newMap)) {
			params.keyMappings = newMap;
			ProfilesManager.saveConfig(params);
		}
	}

	private void alertMenuKey() {
		ComposeDialogHost.showMessage(
				this,
				getString(R.string.warning),
				getString(R.string.alert_map_menu),
				getString(R.string.CANCEL_CMD),
				getString(R.string.save),
				null,
				true,
				null,
				() -> {
					save();
					finish();
				},
				null
		);
	}

	private boolean equalMaps(SparseIntArray map1, SparseIntArray map2) {
		if (map1 == map2) {
			return true;
		}
		if (map1 == null || map2 == null || map1.size() != map2.size()) {
			return false;
		}
		for (int i = 0, size = map1.size(); i < size; i++) {
			if (map2.keyAt(i) != map1.keyAt(i) ||
					map2.valueAt(i) != map1.valueAt(i)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (composeView.isMappingDialogVisible()
				&& event.getAction() == KeyEvent.ACTION_DOWN) {
			int keyCode = event.getKeyCode();
			switch (keyCode) {
				case KeyEvent.KEYCODE_HOME:
				case KeyEvent.KEYCODE_VOLUME_UP:
				case KeyEvent.KEYCODE_VOLUME_DOWN:
					break;
				default:
					deleteDuplicates(canvasKey);
					androidToMIDP.put(keyCode, canvasKey);
					composeView.hideMappingDialog();
					return true;
			}
		}
		return super.dispatchKeyEvent(event);
	}

}
