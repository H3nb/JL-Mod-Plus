/*
 * Copyright 2024 Yury Kharchenko
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

package io.github.h3nb.jlmodplus.crashes.dialog;

import static android.content.DialogInterface.BUTTON_NEGATIVE;
import static android.content.DialogInterface.BUTTON_NEUTRAL;
import static android.content.DialogInterface.BUTTON_POSITIVE;
import static io.github.h3nb.jlmodplus.crashes.dialog.DialogInteraction.EXTRA_REPORT_FILE;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.TypefaceSpan;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.TextViewCompat;
import androidx.lifecycle.ViewModelProvider;

import org.acra.file.BulkReportDeleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

import io.github.h3nb.jlmodplus.R;

public final class CrashReportDialog extends AppCompatActivity implements OnClickListener {
	private File reportFile;
	private CrashViewModel viewModel;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		reportFile = (File) intent.getSerializableExtra(EXTRA_REPORT_FILE);
		if (reportFile == null) {
			finish();
			return;
		}

		viewModel = new ViewModelProvider(this).get(CrashViewModel.class);
		viewModel.loadStackTrace(reportFile).observe(this, stackTrace -> {
			if (stackTrace == null) {
				finish();
			} else {
				buildAndShowDialog(stackTrace);
			}
		});
	}

	private void buildAndShowDialog(String stackTrace) {
		SpannableStringBuilder builder = new SpannableStringBuilder(stackTrace);
		TypefaceSpan span = new TypefaceSpan("monospace");
		builder.setSpan(span, 0, builder.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
		builder.append("\n\n");
		builder.append(getString(R.string.crash_report_instruction));

		ContextThemeWrapper context = new ContextThemeWrapper(this, R.style.AppTheme);
		AlertDialog dialog = new AlertDialog.Builder(context)
				.setTitle(R.string.crash_dialog_title)
				.setMessage(new SpannedString(builder))
				.setPositiveButton(R.string.open_github, this)
				.setNegativeButton(android.R.string.cancel, this)
				.setNeutralButton(android.R.string.copy, this)
				.setOnCancelListener(dialogInterface -> onClick(dialogInterface, BUTTON_NEGATIVE))
				.setOnDismissListener(dialogInterface -> finish())
				.create();
		dialog.setCanceledOnTouchOutside(false);
		dialog.show();
		Drawable drawable = ContextCompat.getDrawable(this,
				androidx.appcompat.R.drawable.abc_ic_menu_copy_mtrl_am_alpha);
		if (drawable != null) {
			Button button = dialog.getButton(BUTTON_NEUTRAL);
			DrawableCompat.setTint(drawable, ContextCompat.getColor(this, R.color.accent));
			TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(button,
					drawable, null, null, null);
			button.setText(null);
		}
	}

	@Override
	public void onClick(@NotNull DialogInterface dialog, int which) {
		if (which == BUTTON_POSITIVE) {
			copyStackTrace();
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.crash_issue_url))));
		}
		if (which == BUTTON_NEUTRAL) {
			copyStackTrace();
		}

		new Thread(() -> new BulkReportDeleter(CrashReportDialog.this).deleteReports(false, 0))
				.start();
	}

	private void copyStackTrace() {
		ClipboardManager cm = ContextCompat.getSystemService(this, ClipboardManager.class);
		if (cm != null) {
			String label = getString(R.string.app_name) + " stacktrace";
			ClipData clip = ClipData.newPlainText(label, viewModel.getStackTrace());
			cm.setPrimaryClip(clip);
			Toast.makeText(this, R.string.msg_text_copied_to_clipboard, Toast.LENGTH_SHORT).show();
		}
	}
}
