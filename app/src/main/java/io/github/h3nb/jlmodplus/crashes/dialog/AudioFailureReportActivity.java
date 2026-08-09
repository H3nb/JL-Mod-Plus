/*
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

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;

import io.github.h3nb.jlmodplus.R;
import ru.woesss.j2me.mmapi.audio.AudioFailureReportStore;

/** Displays one audio report or the recent global Audio diagnostics history. */
public final class AudioFailureReportActivity extends AppCompatActivity {
	public static final String EXTRA_REPORT_ID = "audio_report_id";

	private String report;
	private boolean diagnosticsMode;
	private final ReportComposeState composeState = new ReportComposeState();

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		String reportId = getIntent().getStringExtra(EXTRA_REPORT_ID);
		diagnosticsMode = reportId == null;
		try {
			report = diagnosticsMode
					? AudioFailureReportStore.readAll(getFilesDir())
					: AudioFailureReportStore.read(getFilesDir(), reportId);
		} catch (IOException | RuntimeException e) {
			Toast.makeText(this, R.string.audio_failure_report_unavailable, Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		if (diagnosticsMode && report.isEmpty()) {
			report = getString(R.string.audio_diagnostics_empty);
		}
		ReportComposeHost.install(this, composeState, new ReportComposeCallbacks() {
			@Override
			public void onPrimaryAction() {
				shareReport();
			}

			@Override
			public void onCopyAction() {
				copyReport();
			}

			@Override
			public void onCancelAction() {
				finish();
			}

			@Override
			public void onChoice(int index) {
				finish();
			}
		});
		showReportDialog();
	}

	private void showReportDialog() {
		composeState.setReport(
				getString(diagnosticsMode ? R.string.audio_diagnostics_title : R.string.audio_failure_report_title),
				report + "\n\n" + getString(R.string.audio_failure_report_instruction),
				getString(R.string.share_error_report),
				getString(android.R.string.copy),
				getString(android.R.string.cancel)
		);
	}

	private void copyReport() {
		ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
		if (clipboard != null) {
			clipboard.setPrimaryClip(ClipData.newPlainText(
					getString(diagnosticsMode ? R.string.audio_diagnostics_title : R.string.audio_failure_report_title), report));
			Toast.makeText(this, R.string.msg_text_copied_to_clipboard, Toast.LENGTH_SHORT).show();
		}
	}

	private void shareReport() {
		Intent sendIntent = new Intent(Intent.ACTION_SEND)
				.setType("text/plain")
				.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.audio_failure_report_subject))
				.putExtra(Intent.EXTRA_TEXT, report);
		try {
			startActivity(Intent.createChooser(sendIntent, getString(R.string.share_error_report)));
		} catch (ActivityNotFoundException e) {
			Toast.makeText(this, R.string.error_report_no_share_app, Toast.LENGTH_SHORT).show();
		}
	}
}
