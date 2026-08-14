/*
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

package ru.playsoftware.j2meloader.config;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.DialogFragment;

import ru.playsoftware.j2meloader.R;

/** Compose shader tuning surface; shader values and callback ownership remain unchanged. */
public class ShaderTuneAlert extends DialogFragment {
	private static final String SHADER_KEY = "shader";
	private ShaderInfo shader;
	private Callback callback;

	static ShaderTuneAlert newInstance(ShaderInfo shader) {
		ShaderTuneAlert fragment = new ShaderTuneAlert();
		Bundle args = new Bundle();
		args.putParcelable(SHADER_KEY, shader);
		fragment.setArguments(args);
		fragment.setCancelable(false);
		return fragment;
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		if (context instanceof Callback) {
			callback = (Callback) context;
		}
		shader = requireArguments().getParcelable(SHADER_KEY);
		if (shader == null) {
			Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show();
			dismiss();
		}
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		ComposeView composeView = new ComposeView(requireContext());
		if (shader == null) {
			return new Dialog(requireContext());
		}
		ConfigDialogComposeBridge.setShaderContent(
				composeView,
				shader,
				new ConfigDialogComposeBridge.ShaderCallbacks() {
					@Override
					public void onDismiss() {
						dismiss();
					}

					@Override
					public void onConfirm(@NonNull float[] values) {
						if (callback != null) {
							callback.onTuneComplete(values);
						}
						dismiss();
					}
				});
		Dialog dialog = new Dialog(requireContext());
		dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
		dialog.setContentView(composeView);
		// The legacy dialog is non-cancelable; only Cancel/OK in the Compose surface may close it.
		dialog.setCancelable(false);
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
		int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(620));
		int height = Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.84f), dp(720));
		window.setLayout(Math.max(width, dp(280)), Math.max(height, dp(320)));
	}

	private int dp(int value) {
		return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
	}

	interface Callback {
		void onTuneComplete(float[] values);
	}
}
