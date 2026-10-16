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

package io.github.h3nb.jlmodplus.crashes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.ui.ThemedToast;
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat;

/** Local-only inbox for retained JL-Mod Plus diagnostic records. */
public class CrashReportsActivity extends AppCompatActivity {
	private static final long DIAGNOSTIC_REFRESH_RETRY_MILLIS = 200L;

	private ComposeView composeView;
	private CrashReportsListController composeController;
	private List<LocalDiagnosticRepository.Record> currentRecords = Collections.emptyList();
	private boolean resumed;
	private boolean refreshRetryScheduled;
	private boolean logSaveRunning;
	private int readGeneration;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableForComposeSurface(this);
		composeView = new ComposeView(this);
		composeView.setId(R.id.crash_reports_compose_root);
		setContentView(composeView);
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
		readGeneration++;
		super.onPause();
	}

	private void updateStoredRecords() {
		int generation = ++readGeneration;
		Context appContext = getApplicationContext();
		new Thread(() -> {
			List<LocalDiagnosticRepository.Record> records =
					LocalDiagnosticRepository.loadStored(appContext);
			runOnUiThread(() -> {
				if (!resumed || isFinishing() || isDestroyed() || generation != readGeneration) return;
				currentRecords = records;
				composeController.update(records);
			});
		}, "JLP-read-diagnostics").start();
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
			public void onSaveLog() {
				saveLog();
			}

			@Override
			public void onCopySelected(List<String> reportIds) {
				copySelected(reportIds);
			}

			@Override
			public void onDeleteSelected(List<String> reportIds) {
				deleteSelected(reportIds);
			}
		};
	}

	private void saveLog() {
		if (logSaveRunning) return;
		logSaveRunning = true;
		composeController.setSavingLog(true);
		new Thread(() -> {
			ManualLogCapture.Result result = ManualLogCapture.save(getApplicationContext());
			runOnUiThread(() -> {
				logSaveRunning = false;
				if (isFinishing() || isDestroyed()) return;
				composeController.setSavingLog(false);
				if (result.saved()) {
					ThemedToast.show(this, getString(
							result.status == ManualLogCapture.Status.PARTIAL
									? R.string.log_save_partial : R.string.log_save_success,
							result.fileName), Toast.LENGTH_LONG);
				} else {
					int message = result.status == ManualLogCapture.Status.EMPTY
							? R.string.log_save_empty : result.status == ManualLogCapture.Status.TIMEOUT
							? R.string.log_save_timeout : R.string.log_save_failed;
					ThemedToast.show(this, message, Toast.LENGTH_LONG);
				}
			});
		}, "JLP-save-diagnostic-log").start();
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
			ThemedToast.show(this, getResources().getQuantityString(
					R.plurals.crash_reports_copied, records.size(), records.size()),
					Toast.LENGTH_SHORT);
		}
	}

	private void deleteSelected(List<String> reportIds) {
		List<LocalDiagnosticRepository.Record> records = selectedRecords(reportIds);
		if (records.isEmpty()) return;
		readGeneration++;
		Context appContext = getApplicationContext();
		new Thread(() -> {
			boolean allSourcesDeleted = true;
			boolean allBundlesDeleted = true;
			for (LocalDiagnosticRepository.Record selected : records) {
				LocalDiagnosticRepository.DeleteResult result =
						LocalDiagnosticRepository.deleteWithArtifacts(appContext, selected);
				allSourcesDeleted &= result.sourceDeleted;
				allBundlesDeleted &= result.bundleDeleted;
			}
			boolean finalAllSourcesDeleted = allSourcesDeleted;
			boolean finalAllBundlesDeleted = allBundlesDeleted;
			runOnUiThread(() -> {
				if (isFinishing() || isDestroyed()) return;
				updateStoredRecords();
				if (!finalAllSourcesDeleted) {
					ThemedToast.show(
							this, records.size() > 1 ? R.string.crash_reports_delete_failed
									: R.string.crash_report_delete_failed, Toast.LENGTH_LONG);
				} else if (!finalAllBundlesDeleted) {
					ThemedToast.show(
							this, records.size() > 1 ? R.string.crash_reports_bundle_cleanup_failed
									: R.string.crash_report_bundle_cleanup_failed, Toast.LENGTH_LONG);
				}
			});
		}, "JLP-delete-diagnostics").start();
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}
}
