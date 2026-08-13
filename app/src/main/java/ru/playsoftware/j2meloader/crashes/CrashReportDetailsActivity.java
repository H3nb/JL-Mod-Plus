/*
 * Modified for JL-Mod Plus.
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

// Modified for JL-Mod Plus.

package ru.playsoftware.j2meloader.crashes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.DateFormat;
import java.util.Date;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;

/** Read-only diagnostic detail view with explicit copy/share/delete actions. */
public class CrashReportDetailsActivity extends AppCompatActivity {
	static final String EXTRA_REPORT_ID = "ru.playsoftware.j2meloader.crashes.REPORT_ID";

	private LocalDiagnosticRepository.Record record;
	private String exportText;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableIfSupported(this);
		setContentView(R.layout.activity_crash_report_details);
		EdgeToEdgeCompat.protectHostContent(this);
		setTitle(R.string.crash_reports);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		String recordId = getIntent().getStringExtra(EXTRA_REPORT_ID);
		record = LocalDiagnosticRepository.find(this, recordId);
		if (record == null) {
			Toast.makeText(this, R.string.crash_report_unavailable, Toast.LENGTH_SHORT).show();
			finish();
			return;
		}

		String displayText = buildReportText(record);
		exportText = DiagnosticExportSanitizer.sanitize(this, displayText);
		TextView details = findViewById(R.id.crash_report_details_text);
		details.setText(displayText);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.crash_report_details, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == android.R.id.home || id == R.id.action_close_crash_report) {
			finish();
			return true;
		}
		if (id == R.id.action_copy_crash_report) {
			copyReport();
			return true;
		}
		if (id == R.id.action_share_crash_report) {
			confirmShare();
			return true;
		}
		if (id == R.id.action_delete_crash_report) {
			confirmDelete();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void copyReport() {
		ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_reports), exportText));
			Toast.makeText(this, R.string.crash_report_copied, Toast.LENGTH_SHORT).show();
		}
	}

	private void confirmShare() {
		new AlertDialog.Builder(this)
				.setTitle(R.string.share_report)
				.setMessage(R.string.crash_report_share_disclosure)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.share_report, (dialog, which) -> shareReport())
				.show();
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

	private void confirmDelete() {
		new AlertDialog.Builder(this)
				.setTitle(R.string.crash_report_delete_title)
				.setMessage(R.string.crash_report_delete_message)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.delete_report, (dialog, which) -> deleteReport())
				.show();
	}

	private void deleteReport() {
		if (LocalDiagnosticRepository.delete(this, record)) {
			finish();
		} else {
			Toast.makeText(this, R.string.crash_report_delete_failed, Toast.LENGTH_LONG).show();
		}
	}

	private String buildReportText(LocalDiagnosticRepository.Record record) {
		StringBuilder text = new StringBuilder();
		text.append("JL-Mod Plus diagnostic report\n");
		if (record.getTimestampMillis() > 0) {
			DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
			text.append("Time: ")
					.append(dateFormat.format(new Date(record.getTimestampMillis())))
					.append('\n');
		}
		text.append('\n').append(record.getDetailText());
		return text.toString();
	}
}
