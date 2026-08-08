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
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.crashes.runtime.ProcessDeathReportStore;

/** Displays a locally persisted report for an unexpected Android process death. */
public final class ProcessDeathReportActivity extends AppCompatActivity {
    public static final String EXTRA_REPORT_ID = "process_death_report_id";

    private String reportId;
    private String report;
    private final ReportComposeState composeState = new ReportComposeState();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reportId = getIntent().getStringExtra(EXTRA_REPORT_ID);
        try {
            report = ProcessDeathReportStore.read(this, reportId);
        } catch (IOException | RuntimeException error) {
            finish();
            return;
        }

        ReportComposeHost.install(this, composeState, new ReportComposeCallbacks() {
            @Override
            public void onPrimaryAction() {
                shareReport();
            }

            @Override
            public void onCopyAction() {
                copyReport();
                deleteAndFinish();
            }

            @Override
            public void onCancelAction() {
                deleteAndFinish();
            }

            @Override
            public void onChoice(int index) {
                // This report currently has no secondary choice dialog.
            }
        });

        composeState.setReport(
                getString(R.string.crash_dialog_title),
                getString(R.string.crash_report_instruction) + "\n\n" + report,
                getString(R.string.share_error_report),
                getString(android.R.string.copy),
                getString(android.R.string.cancel)
        );
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.app_name) + " process death report",
                report
        ));
        Toast.makeText(this, R.string.msg_text_copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.error_report_subject))
                .putExtra(Intent.EXTRA_TEXT, report);

        if (ProcessDeathReportStore.hasTrace(this, reportId)) {
            try {
                Uri traceUri = ProcessDeathReportStore.getTraceUri(this, reportId);
                intent.setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_STREAM, traceUri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .setClipData(ClipData.newRawUri("Android process exit trace", traceUri));
            } catch (IOException | RuntimeException ignored) {
                // The text report remains shareable even if the optional attachment disappeared.
                intent.setType("text/plain");
            }
        }

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_error_report)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.error_report_no_share_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteAndFinish() {
        ProcessDeathReportStore.delete(this, reportId);
        finish();
    }
}
