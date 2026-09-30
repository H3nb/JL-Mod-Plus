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

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.ui.ThemedToast;
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat;

/** Read-only diagnostic detail view with explicit copy/report/delete actions. */
public class CrashReportDetailsActivity extends AppCompatActivity {
	static final String EXTRA_REPORT_ID = "ru.playsoftware.j2meloader.crashes.REPORT_ID";
	private static final String GITHUB_NEW_ISSUE_URL =
			"https://github.com/H3nb/JL-Mod-Plus/issues/new";
	private static final String GITHUB_ISSUE_TEMPLATE = "diagnostic-report.yml";
	private static final String GITHUB_SUMMARY_FIELD = "diagnostic-summary";

	private LocalDiagnosticRepository.Record record;
	private String exportText;
	private ComposeView composeView;
	private CrashReportDetailsController composeController;
	private DiagnosticBundleStore.PreparedBundle preparedBundle;
	private volatile boolean preparingBundle;
	private volatile boolean deletingRecord;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableForComposeSurface(this);
		composeView = new ComposeView(this);
		composeView.setId(R.id.crash_report_details_compose_root);
		setContentView(composeView);
		String recordId = getIntent().getStringExtra(EXTRA_REPORT_ID);
		// Historical reconciliation is owned by CrashReporter in the background. Opening a report
		// must never copy framework traces or run maintenance on the UI thread.
		record = LocalDiagnosticRepository.findStored(this, recordId);
		if (record == null) {
			ThemedToast.show(this, R.string.crash_report_unavailable, Toast.LENGTH_SHORT);
			finish();
			return;
		}

		String displayText = DiagnosticReportText.build(record);
		applyReportText(displayText);
		composeController = CrashReportsComposeBridge.installDetails(
				composeView, displayText, createActions());
		loadNativeSummaryAsync(displayText);
	}

	private CrashReportDetailsActions createActions() {
		return new CrashReportDetailsActions() {
			@Override
			public void onBack() {
				finish();
			}

			@Override
			public void onCopy() {
				copyReport();
			}

			@Override
			public void onReportGitHub() {
				reportOnGitHub();
			}

			@Override
			public void onDismissBundleReady() {
				composeController.dismissBundleReady();
			}

			@Override
			public void onLocateBundle() {
				locateBundle();
			}

			@Override
			public void onOpenGitHub() {
				openGitHub();
			}

			@Override
			public void onDelete() {
				deleteReport();
			}
		};
	}

	private void loadNativeSummaryAsync(String baseDisplayText) {
		ProcessExitStore.Snapshot exit = record.getProcessExitSnapshot();
		if (exit == null || !"native-tombstone-protobuf".equals(exit.traceKind)) {
			return;
		}
		Thread parser = new Thread(() -> {
			String nativeSummary = NativeTombstoneSummary.summarize(exit);
			if (nativeSummary == null) return;
			runOnUiThread(() -> {
				if (isFinishing() || isDestroyed()) return;
				String displayText = DiagnosticReportText.withNativeSummary(
						baseDisplayText, nativeSummary);
				applyReportText(displayText);
				composeController.updateDisplay(displayText);
			});
		}, "JLP-native-diagnostic");
		parser.start();
	}

	private void applyReportText(String displayText) {
		exportText = DiagnosticExportSanitizer.sanitize(this, displayText);
	}

	private void copyReport() {
		ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_reports), exportText));
			ThemedToast.show(this, R.string.crash_report_copied, Toast.LENGTH_SHORT);
		}
	}

	private void reportOnGitHub() {
		if (preparingBundle || deletingRecord) return;
		preparingBundle = true;
		ThemedToast.show(this, R.string.crash_report_bundle_preparing, Toast.LENGTH_SHORT);
		Context appContext = getApplicationContext();
		LocalDiagnosticRepository.Record target = record;
		new Thread(() -> {
			DiagnosticBundleStore.PreparedBundle result;
			try {
				result = DiagnosticBundleStore.ensure(appContext, target);
			} catch (Exception e) {
				result = null;
			}
			if (deletingRecord) {
				if (result != null) DiagnosticBundleStore.deleteTracked(appContext, target);
				preparingBundle = false;
				return;
			}
			DiagnosticBundleStore.PreparedBundle finalResult = result;
			runOnUiThread(() -> {
				preparingBundle = false;
				if (isFinishing() || isDestroyed() || deletingRecord) return;
				if (finalResult == null) {
					ThemedToast.show(
							this, R.string.crash_report_bundle_failed, Toast.LENGTH_LONG);
					return;
				}
				preparedBundle = finalResult;
				composeController.showBundleReady(finalResult.fileName);
			});
		}, "JLP-diagnostic-bundle").start();
	}

	private void locateBundle() {
		if (preparedBundle == null) return;
		Uri directory = diagnosticsDirectoryDocumentUri();

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			Intent folder = new Intent(Intent.ACTION_VIEW)
					.setDataAndType(directory, DocumentsContract.Document.MIME_TYPE_DIR)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			try {
				startActivity(folder);
				return;
			} catch (ActivityNotFoundException | SecurityException ignored) {
				// Android 6-7 have no standard initial-folder extra; fall through to a picker.
			}
		}

		Intent locate = new Intent(Intent.ACTION_OPEN_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("application/zip");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			Uri initialUri = preferredInitialDocumentUri(preparedBundle.uri, directory);
			if (initialUri != null) {
				locate.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
			}
		}
		try {
			startActivity(locate);
			return;
		} catch (ActivityNotFoundException | SecurityException ignored) {
			// Fall through only when the system document picker itself is unavailable.
		}
		Intent fallback = new Intent(Intent.ACTION_GET_CONTENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("application/zip");
		try {
			startActivity(fallback);
		} catch (ActivityNotFoundException | SecurityException e) {
			ThemedToast.show(this, R.string.crash_report_locate_failed, Toast.LENGTH_LONG);
		}
	}

	@Nullable
	private Uri preferredInitialDocumentUri(Uri bundleUri, Uri directoryUri) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				&& bundleUri != null
				&& ContentResolver.SCHEME_CONTENT.equals(bundleUri.getScheme())
				&& MediaStore.AUTHORITY.equals(bundleUri.getAuthority())) {
			try {
				Uri documentUri = MediaStore.getDocumentUri(this, bundleUri);
				if (resolvesDocumentsProvider(documentUri)
						&& hasReadPermission(documentUri)) {
					return documentUri;
				}
			} catch (RuntimeException ignored) {
				// Conversion is best-effort and grants no new permissions.
			}
		}
		return resolvesDocumentsProvider(directoryUri) ? directoryUri : null;
	}

	private boolean resolvesDocumentsProvider(Uri uri) {
		return uri != null
				&& ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
				&& uri.getAuthority() != null
				&& getPackageManager().resolveContentProvider(uri.getAuthority(), 0) != null;
	}

	private boolean hasReadPermission(Uri uri) {
		return checkUriPermission(
				uri,
				android.os.Process.myPid(),
				android.os.Process.myUid(),
				Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
	}

	static Uri diagnosticsDirectoryDocumentUri() {
		return DocumentsContract.buildDocumentUri(
				"com.android.externalstorage.documents",
				"primary:" + Environment.DIRECTORY_DOWNLOADS + "/"
						+ DiagnosticBundleStore.PUBLIC_DIRECTORY);
	}

	private void openGitHub() {
		if (preparedBundle == null) return;
		String issueUrl = GitHubIssueDraft.buildIssueFormUrl(
				GITHUB_NEW_ISSUE_URL,
				GITHUB_ISSUE_TEMPLATE,
				preparedBundle.issueTitle,
				GITHUB_SUMMARY_FIELD,
				preparedBundle.issueSummary);
		Intent issueIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl))
				.addCategory(Intent.CATEGORY_BROWSABLE);
		try {
			startActivity(issueIntent);
		} catch (ActivityNotFoundException | SecurityException e) {
			ThemedToast.show(this, R.string.crash_report_github_unavailable, Toast.LENGTH_LONG);
		}
	}

	private void deleteReport() {
		if (deletingRecord) return;
		deletingRecord = true;
		Context appContext = getApplicationContext();
		LocalDiagnosticRepository.Record target = record;
		new Thread(() -> {
			LocalDiagnosticRepository.DeleteResult result =
					LocalDiagnosticRepository.deleteWithArtifacts(appContext, target);
			runOnUiThread(() -> {
				if (!result.sourceDeleted) {
					deletingRecord = false;
					if (!isFinishing() && !isDestroyed()) {
						ThemedToast.show(
								this, R.string.crash_report_delete_failed, Toast.LENGTH_LONG);
					}
					return;
				}
				if (!result.bundleDeleted && !isFinishing() && !isDestroyed()) {
					ThemedToast.show(
							this, R.string.crash_report_bundle_cleanup_failed, Toast.LENGTH_LONG);
				}
				finish();
			});
		}, "JLP-delete-diagnostic").start();
	}

}
