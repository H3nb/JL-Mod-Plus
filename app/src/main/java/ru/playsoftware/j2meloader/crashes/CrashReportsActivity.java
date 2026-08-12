/*
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;

/** Local-only inbox for retained JL-Mod Plus diagnostic records. */
public class CrashReportsActivity extends AppCompatActivity {
	private ReportAdapter adapter;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableIfSupported(this);
		setContentView(R.layout.activity_crash_reports);
		setTitle(R.string.crash_reports);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		ListView listView = findViewById(R.id.crash_reports_list);
		TextView emptyView = findViewById(R.id.crash_reports_empty);
		listView.setEmptyView(emptyView);
		adapter = new ReportAdapter();
		listView.setAdapter(adapter);
		listView.setOnItemClickListener((parent, view, position, id) -> {
			LocalDiagnosticRepository.Record record = adapter.getItem(position);
			Intent intent = new Intent(this, CrashReportDetailsActivity.class);
			intent.putExtra(CrashReportDetailsActivity.EXTRA_REPORT_ID, record.getId());
			startActivity(intent);
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		adapter.setRecords(LocalDiagnosticRepository.load(this));
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}

	private int kindLabel(LocalDiagnosticRepository.Kind kind) {
		return switch (kind) {
			case MIDLET_FAILURE -> R.string.crash_report_midlet_failure;
			case JAVA_REPORT -> R.string.crash_report_java_report;
			case PROCESS_EXIT -> R.string.crash_report_process_exit;
		};
	}

	private final class ReportAdapter extends BaseAdapter {
		private final DateFormat dateFormat = DateFormat.getDateTimeInstance(
				DateFormat.MEDIUM, DateFormat.SHORT);
		private List<LocalDiagnosticRepository.Record> records = Collections.emptyList();

		void setRecords(List<LocalDiagnosticRepository.Record> records) {
			this.records = records == null ? Collections.emptyList() : records;
			notifyDataSetChanged();
		}

		@Override
		public int getCount() {
			return records.size();
		}

		@Override
		public LocalDiagnosticRepository.Record getItem(int position) {
			return records.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view = convertView;
			if (view == null) {
				view = LayoutInflater.from(parent.getContext())
						.inflate(android.R.layout.simple_list_item_2, parent, false);
			}
			TextView title = view.findViewById(android.R.id.text1);
			TextView subtitle = view.findViewById(android.R.id.text2);
			LocalDiagnosticRepository.Record record = getItem(position);

			String midletName = record.getMidletName();
			if (midletName != null && !midletName.trim().isEmpty()) {
				title.setText(midletName);
			} else {
				title.setText(kindLabel(record.getKind()));
			}

			String type = getString(kindLabel(record.getKind()));
			String time = record.getTimestampMillis() > 0
					? dateFormat.format(new Date(record.getTimestampMillis()))
					: "";
			subtitle.setText(time.isEmpty() ? type : getString(R.string.crash_report_list_subtitle, type, time));
			return view;
		}
	}
}
