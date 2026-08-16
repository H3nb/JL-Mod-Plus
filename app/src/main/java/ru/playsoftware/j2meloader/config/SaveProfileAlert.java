/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2019-2023 Yury Kharchenko
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

import java.util.HashSet;
import java.util.Set;

import ru.playsoftware.j2meloader.R;

/** Compose presentation with filename filtering and overwrite confirmation preserved. */
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
		Set<String> existingConfigNames = new HashSet<>();
		for (Profile profile : ProfilesManager.getProfiles()) {
			if (profile.getConfig().exists() || profile.hasOldConfig()) {
				existingConfigNames.add(profile.getName());
			}
		}
		ComposeView composeView = new ComposeView(requireContext());
		ConfigDialogComposeBridge.setSaveProfileContent(
				composeView,
				existingConfigNames,
				new ConfigDialogComposeBridge.SaveProfileCallbacks() {
					@Override
					public void onDismiss() {
						dismiss();
					}

					@Override
					public void onError() {
						Context context = getContext();
						if (context != null) {
							Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show();
						}
					}

					@Override
					public void onConfirm(String name, boolean config, boolean keyboard, boolean asDefault) {
						Context context = getContext();
						if (context == null) {
							return;
						}
						try {
							ProfilesManager.saveSnapshot(new Profile(name), configPath);
							if (context instanceof ConfigActivity) {
								((ConfigActivity) context).onProfileDataChanged();
							}
							Toast.makeText(context, getString(R.string.saved, name), Toast.LENGTH_SHORT).show();
							dismiss();
						} catch (Exception e) {
							e.printStackTrace();
							Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show();
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
		int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560));
		int height = Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.84f), dp(680));
		window.setLayout(Math.max(width, dp(280)), Math.max(height, dp(320)));
	}

	private int dp(int value) {
		return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
	}
}
