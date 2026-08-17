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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;

/** Local-only inbox for retained JL-Mod Plus diagnostic records. */
public class CrashReportsActivity extends AppCompatActivity {
	private static final long DIAGNOSTIC_REFRESH_RETRY_MILLIS = 200L;

	private ComposeView composeView;
	private CrashReportsListController composeController;
	private List<LocalDiagnosticRepository.Record> currentRecords = Collections.emptyList();
	private boolean resumed;
	private boolean refreshRetryScheduled;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableIfSupported(this);
		composeView = new ComposeView(this);
		composeView.setId(R.id.crash_reports_compose_root);
		setContentView(composeView);
		EdgeToEdgeCompat.protectHostContent(this);
		composeController = CrashReportsComposeBridge.installList(composeView, createActions());
	}

	@Override
	protected void onResume() {
		super.onResume();
		resumed = true;
		updateStoredRecords();
		CrashReporter.requestDiagnosticRefresh(getApplication());
		scheduleRefreshUpdate();
	}

	@Override
	protected void onPause() {
		resumed = false;
		super.onPause();
	}

	private void updateStoredRecords() {
		currentRecords = LocalDiagnosticRepository.loadStored(this);
		composeController.update(currentRecords);
	}

	private void scheduleRefreshUpdate() {
		if (!resumed || refreshRetryScheduled) {
			return;
		}
		if (CrashReporter.isDiagnosticRefreshReady()) {
			updateStoredRecords();
			return;
		}
		refreshRetryScheduled = true;
		composeView.postDelayed(() -> {
			refreshRetryScheduled = false;
			if (resumed) {
				scheduleRefreshUpdate();
			}
		}, DIAGNOSTIC_REFRESH_RETRY_MILLIS);
	}

	private CrashReportsActions createActions() {
		return new CrashReportsActions() {
			@Override
			public void onBack() {
				finish();
			}

			@Override
			public void onOpen(String reportId) {
				Intent intent = new Intent(CrashReportsActivity.this,
						CrashReportDetailsActivity.class);
				intent.putExtra(CrashReportDetailsActivity.EXTRA_REPORT_ID, reportId);
				startActivity(intent);
			}

			@Override
			public void onCopySelected(List<String> reportIds) {
				copySelected(reportIds);
			}

			@Override
			public void onShareSelected(List<String> reportIds) {
				shareSelected(reportIds);
			}

			@Override
			public void onDeleteSelected(List<String> reportIds) {
				deleteSelected(reportIds);
			}
		};
	}

	private List<LocalDiagnosticRepository.Record> selectedRecords(List<String> reportIds) {
		if (reportIds == null || reportIds.isEmpty()) {
			return Collections.emptyList();
		}
		Set<String> ids = new HashSet<>(reportIds);
		ArrayList<LocalDiagnosticRepository.Record> selected = new ArrayList<>(ids.size());
		for (LocalDiagnosticRepository.Record record : currentRecords) {
			if (ids.contains(record.getId())) {
				selected.add(record);
			}
		}
		return selected;
	}

	private void copySelected(List<String> reportIds) {
		List<LocalDiagnosticRepository.Record> records = selectedRecords(reportIds);
		if (records.isEmpty()) {
			return;
		}
		String exportText = DiagnosticExportSanitizer.sanitize(
				this, DiagnosticReportText.buildBatch(records));
		ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_reports), exportText));
			Toast.makeText(this, getResources().getQuantityString(
					R.plurals.crash_reports_copied, records.size(), records.size()),
					Toast.LENGTH_SHORT).show();
		}
	}

	private void shareSelected(List<String> reportIds) {
		List<LocalDiagnosticRepository.Record> records = selectedRecords(reportIds);
		if (records.isEmpty()) {
			return;
		}
		String exportText = DiagnosticExportSanitizer.sanitize(
				this, DiagnosticReportText.buildBatch(records));
		ArrayList<Uri> attachments = new ArrayList<>();
		ArrayList<String> mimeTypes = new ArrayList<>();
		HashSet<String> seenUris = new HashSet<>();
		for (LocalDiagnosticRepository.Record record : records) {
			DiagnosticTraceAttachment.Attachment attachment = DiagnosticTraceAttachment.find(
					this, record.getId(), record.getSessionId());
			if (attachment != null && seenUris.add(attachment.uri.toString())) {
				attachments.add(attachment.uri);
				mimeTypes.add(attachment.mimeType);
			}
		}

		Intent share;
		if (attachments.size() > 1) {
			share = new Intent(Intent.ACTION_SEND_MULTIPLE);
			share.setType("*/*");
			share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments);
		} else {
			share = new Intent(Intent.ACTION_SEND);
			share.setType(attachments.isEmpty() ? "text/plain" : mimeTypes.get(0));
			if (!attachments.isEmpty()) {
				share.putExtra(Intent.EXTRA_STREAM, attachments.get(0));
			}
		}
		share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_reports));
		share.putExtra(Intent.EXTRA_TEXT, exportText);
		if (!attachments.isEmpty()) {
			ClipData clipData = ClipData.newUri(
					getContentResolver(), getString(R.string.crash_reports), attachments.get(0));
			for (int i = 1; i < attachments.size(); i++) {
				clipData.addItem(new ClipData.Item(attachments.get(i)));
			}
			share.setClipData(clipData);
			share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		}
		startActivity(Intent.createChooser(share, getString(R.string.crash_report_share_title)));
	}

	private void deleteSelected(List<String> reportIds) {
		List<LocalDiagnosticRepository.Record> records = selectedRecords(reportIds);
		if (records.isEmpty()) {
			return;
		}
		boolean allDeleted = true;
		for (LocalDiagnosticRepository.Record record : records) {
			if (!LocalDiagnosticRepository.delete(this, record)) {
				allDeleted = false;
			}
		}
		updateStoredRecords();
		if (!allDeleted) {
			Toast.makeText(this, R.string.crash_report_delete_failed, Toast.LENGTH_LONG).show();
		}
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}
}
