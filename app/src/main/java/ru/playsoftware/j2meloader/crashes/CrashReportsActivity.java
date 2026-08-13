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

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import java.util.List;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;

/** Local-only inbox for retained JL-Mod Plus diagnostic records. */
public class CrashReportsActivity extends AppCompatActivity {
	private ComposeView composeView;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableIfSupported(this);
		composeView = new ComposeView(this);
		composeView.setId(R.id.crash_reports_compose_root);
		setContentView(composeView);
		EdgeToEdgeCompat.protectHostContent(this);
		CrashReportsComposeBridge.installList(composeView, null, createActions());
	}

	@Override
	protected void onResume() {
		super.onResume();
		List<LocalDiagnosticRepository.Record> records = LocalDiagnosticRepository.load(this);
		CrashReportsComposeBridge.installList(composeView, records, createActions());
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
		};
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}
}
