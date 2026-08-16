/*
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

package ru.playsoftware.j2meloader.crashes;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;

/** Read-only diagnostic detail view with explicit copy/share/report/delete actions. */
public class CrashReportDetailsActivity extends AppCompatActivity {
	static final String EXTRA_REPORT_ID = "ru.playsoftware.j2meloader.crashes.REPORT_ID";
	private static final String GITHUB_NEW_ISSUE_URL =
			"https://github.com/H3nb/JL-Mod-Plus/issues/new";
	private static final int MAX_GITHUB_PREFILL_CHARS = 16 * 1024;

	private LocalDiagnosticRepository.Record record;
	private String exportText;
	private String githubExportText;
	private ComposeView composeView;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableIfSupported(this);
		composeView = new ComposeView(this);
		composeView.setId(R.id.crash_report_details_compose_root);
		setContentView(composeView);
		EdgeToEdgeCompat.protectHostContent(this);
		String recordId = getIntent().getStringExtra(EXTRA_REPORT_ID);
		// Historical reconciliation is owned by CrashReporter in the background. Opening a report
		// must never copy framework traces or run maintenance on the UI thread.
		record = LocalDiagnosticRepository.findStored(this, recordId);
		if (record == null) {
			Toast.makeText(this, R.string.crash_report_unavailable, Toast.LENGTH_SHORT).show();
			finish();
			return;
		}

		String displayText = DiagnosticReportText.build(record);
		exportText = DiagnosticExportSanitizer.sanitize(this, displayText);
		githubExportText = DiagnosticExportSanitizer.sanitize(
				this, DiagnosticReportText.buildForGitHub(record));
		CrashReportsComposeBridge.installDetails(composeView, displayText,
				new CrashReportDetailsActions() {
					@Override
					public void onBack() {
						finish();
					}

					@Override
					public void onCopy() {
						copyReport();
					}

					@Override
					public void onShare() {
						shareReport();
					}

					@Override
					public void onReportGitHub() {
						reportOnGitHub();
					}

					@Override
					public void onDelete() {
						deleteReport();
					}
				});
	}

	private void copyReport() {
		ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_reports), exportText));
			Toast.makeText(this, R.string.crash_report_copied, Toast.LENGTH_SHORT).show();
		}
	}

	private void shareReport() {
		DiagnosticTraceAttachment.Attachment attachment = DiagnosticTraceAttachment.find(
				this, record.getId(), record.getSessionId());
		Intent share = new Intent(Intent.ACTION_SEND);
		share.setType(attachment == null ? "text/plain" : attachment.mimeType);
		share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_reports));
		share.putExtra(Intent.EXTRA_TEXT, exportText);
		if (attachment != null) {
			share.putExtra(Intent.EXTRA_STREAM, attachment.uri);
			share.setClipData(ClipData.newUri(
					getContentResolver(), getString(R.string.crash_reports), attachment.uri));
			share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		}
		startActivity(Intent.createChooser(share, getString(R.string.crash_report_share_title)));
	}

	private void reportOnGitHub() {
		String subject = record.getMidletName();
		if (subject == null || subject.trim().isEmpty()) {
			subject = getString(switch (record.getKind()) {
				case MIDLET_FAILURE -> R.string.crash_report_midlet_failure;
				case JAVA_REPORT -> R.string.crash_report_java_report;
				case PROCESS_EXIT -> R.string.crash_report_process_exit;
			});
		}
		subject = DiagnosticExportSanitizer.sanitize(this, subject);
		String title = getString(R.string.crash_report_github_issue_title, subject);
		String body = getString(R.string.crash_report_github_intro) + "\n\n" + githubExportText;
		if (body.length() > MAX_GITHUB_PREFILL_CHARS) {
			body = body.substring(0, MAX_GITHUB_PREFILL_CHARS)
					+ getString(R.string.crash_report_github_truncated);
		}
		Uri issueUri = Uri.parse(GITHUB_NEW_ISSUE_URL)
				.buildUpon()
				.appendQueryParameter("title", title)
				.appendQueryParameter("body", body)
				.build();
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, issueUri));
		} catch (ActivityNotFoundException | SecurityException e) {
			Toast.makeText(this, R.string.crash_report_github_unavailable, Toast.LENGTH_LONG).show();
		}
	}

	private void deleteReport() {
		if (LocalDiagnosticRepository.delete(this, record)) {
			finish();
		} else {
			Toast.makeText(this, R.string.crash_report_delete_failed, Toast.LENGTH_LONG).show();
		}
	}
}
