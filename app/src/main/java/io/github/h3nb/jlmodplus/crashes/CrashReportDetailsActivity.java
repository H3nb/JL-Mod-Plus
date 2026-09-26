/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at
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
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import java.util.ArrayList;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.ui.ThemedToast;
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat;

/** Read-only diagnostic detail view with explicit copy/share/report/delete actions. */
public class CrashReportDetailsActivity extends AppCompatActivity {
	static final String EXTRA_REPORT_ID = "ru.playsoftware.j2meloader.crashes.REPORT_ID";
	private static final String GITHUB_NEW_ISSUE_URL =
			"https://github.com/H3nb/JL-Mod-Plus/issues/new";
	private static final String GITHUB_ISSUE_TEMPLATE = "diagnostic-report.yml";
	private static final String GITHUB_SUMMARY_FIELD = "diagnostic-summary";
	private static final String NATIVE_TOMBSTONE_MIME_TYPE = "application/x-protobuf";

	private LocalDiagnosticRepository.Record record;
	private String exportText;
	private ComposeView composeView;
	private CrashReportDetailsController composeController;
	private DiagnosticTraceAttachment.Attachment traceAttachment;
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

		traceAttachment = DiagnosticTraceAttachment.find(this, record.getId(), record.getSessionId());
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
			public void onShare() {
				shareReport();
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
		if (traceAttachment == null || !NATIVE_TOMBSTONE_MIME_TYPE.equals(traceAttachment.mimeType)) {
			return;
		}
		Uri traceUri = traceAttachment.uri;
		Thread parser = new Thread(() -> {
			String nativeSummary = NativeTombstoneSummary.summarize(this, traceUri);
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

	private void shareReport() {
		if (traceAttachment == null) {
			shareTextOnly();
			return;
		}
		DiagnosticSummaryAttachment.Attachment summaryAttachment = DiagnosticSummaryAttachment.create(
				this, record.getId(), exportText);
		if (summaryAttachment == null) {
			shareSingleTrace();
			return;
		}

		ArrayList<Uri> streams = new ArrayList<>(2);
		streams.add(summaryAttachment.uri);
		streams.add(traceAttachment.uri);
		Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
		share.setType("*/*");
		share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_reports));
		share.putExtra(Intent.EXTRA_TEXT, exportText);
		share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
		ClipData clipData = ClipData.newUri(
				getContentResolver(), getString(R.string.crash_reports), summaryAttachment.uri);
		clipData.addItem(new ClipData.Item(traceAttachment.uri));
		share.setClipData(clipData);
		share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		startActivity(Intent.createChooser(share, getString(R.string.crash_report_share_title)));
	}

	private void shareTextOnly() {
		Intent share = new Intent(Intent.ACTION_SEND);
		share.setType("text/plain");
		share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_reports));
		share.putExtra(Intent.EXTRA_TEXT, exportText);
		startActivity(Intent.createChooser(share, getString(R.string.crash_report_share_title)));
	}

	private void shareSingleTrace() {
		Intent share = new Intent(Intent.ACTION_SEND);
		share.setType(traceAttachment.mimeType);
		share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_reports));
		share.putExtra(Intent.EXTRA_TEXT, exportText);
		share.putExtra(Intent.EXTRA_STREAM, traceAttachment.uri);
		share.setClipData(ClipData.newUri(
				getContentResolver(), getString(R.string.crash_reports), traceAttachment.uri));
		share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		startActivity(Intent.createChooser(share, getString(R.string.crash_report_share_title)));
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
		Intent locate = new Intent(Intent.ACTION_OPEN_DOCUMENT)
				.addCategory(Intent.CATEGORY_OPENABLE)
				.setType("application/zip");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				&& ContentResolverScheme.isContent(preparedBundle.uri)) {
			locate.putExtra(DocumentsContract.EXTRA_INITIAL_URI, preparedBundle.uri);
		}
		try {
			startActivity(locate);
			return;
		} catch (ActivityNotFoundException | SecurityException ignored) {
			// Fall through to the generic document-provider surface.
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

	private static final class ContentResolverScheme {
		private ContentResolverScheme() {}

		static boolean isContent(Uri uri) {
			return uri != null && "content".equals(uri.getScheme());
		}
	}
}
