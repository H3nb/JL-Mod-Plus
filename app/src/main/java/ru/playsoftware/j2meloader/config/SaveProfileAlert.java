/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2019-2023 Yury Kharchenko
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

import static ru.playsoftware.j2meloader.util.Constants.KEY_CONFIG_PATH;
import static ru.playsoftware.j2meloader.util.Constants.PREF_DEFAULT_PROFILE;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.ui.ThemedToast;

/** Compose presentation with the legacy modular profile-save contract preserved. */
public class SaveProfileAlert extends DialogFragment {
	private String configPath;

	@NonNull
	public static SaveProfileAlert getInstance(String parent) {
		SaveProfileAlert fragment = new SaveProfileAlert();
		Bundle args = new Bundle();
		args.putString(KEY_CONFIG_PATH, parent);
		fragment.setArguments(args);
		return fragment;
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		configPath = requireArguments().getString(KEY_CONFIG_PATH);
		Set<String> existingProfileNames = new HashSet<>();
		for (Profile profile : ProfilesManager.getProfiles()) {
			existingProfileNames.add(profile.getName());
		}
		boolean keyboardAvailable = new File(configPath, Config.MIDLET_KEY_LAYOUT_FILE).isFile();
		ComposeView composeView = new ComposeView(requireContext());
		ConfigDialogComposeBridge.setSaveProfileContent(
				composeView,
				existingProfileNames,
				keyboardAvailable,
				new ConfigDialogComposeBridge.SaveProfileCallbacks() {
					@Override
					public void onDismiss() {
						dismiss();
					}

					@Override
					public void onError() {
						Context context = getContext();
						if (context != null) {
							ThemedToast.show(context, R.string.error, Toast.LENGTH_SHORT);
						}
					}

					@Override
					public void onConfirm(String name, boolean config, boolean keyboard, boolean asDefault) {
						Context context = getContext();
						if (context == null || (!config && !keyboard)) {
							return;
						}
						try {
							ProfilesManager.save(new Profile(name), configPath, config, keyboard);
							if (asDefault) {
								PreferenceManager.getDefaultSharedPreferences(context)
										.edit().putString(PREF_DEFAULT_PROFILE, name).apply();
							}
							if (context instanceof ConfigActivity) {
								((ConfigActivity) context).onProfileDataChanged();
							}
							ThemedToast.show(context, getString(R.string.saved, name), Toast.LENGTH_SHORT);
							dismiss();
						} catch (Exception e) {
							e.printStackTrace();
							ThemedToast.show(context, R.string.error, Toast.LENGTH_SHORT);
						}
					}
				});

		Dialog dialog = new Dialog(requireContext());
		dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
		dialog.setContentView(composeView);
		dialog.setCanceledOnTouchOutside(true);
		return dialog;
	}

	@Override
	public void onStart() {
		super.onStart();
		Dialog dialog = getDialog();
		if (dialog == null || dialog.getWindow() == null) {
			return;
		}
		Window window = dialog.getWindow();
		window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
		WindowManager.LayoutParams attributes = window.getAttributes();
		attributes.dimAmount = 0.32f;
		window.setAttributes(attributes);
		int availableWidth = Math.max(getResources().getDisplayMetrics().widthPixels - dp(32), 1);
		int availableHeight = Math.max(getResources().getDisplayMetrics().heightPixels - dp(24), 1);
		int width = Math.min(availableWidth, dp(840));
		int height = Math.min(availableHeight, dp(680));
		window.setLayout(width, height);
	}

	private int dp(int value) {
		return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
	}
}
