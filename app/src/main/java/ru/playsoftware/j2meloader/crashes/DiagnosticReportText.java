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

package ru.playsoftware.j2meloader.crashes;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Formats local diagnostics consistently for detail, clipboard, and explicit sharing surfaces. */
final class DiagnosticReportText {
	private static final String BATCH_SEPARATOR = "\n\n====================\n\n";
	private static final String ANR_TRACE_MARKER = "\nANR trace:\n";
	private static final String GITHUB_TRACE_NOTICE =
			"\nANR trace: retained locally; use Share Report in JL-Mod Plus to attach retained trace evidence explicitly.";

	private DiagnosticReportText() {}

	static String build(LocalDiagnosticRepository.Record record) {
		return build(record, record.getDetailText());
	}

	/** GitHub drafts never inline retained raw Android trace evidence. */
	static String buildForGitHub(LocalDiagnosticRepository.Record record) {
		return build(record, removeRawSystemTrace(record.getDetailText()));
	}

	static String removeRawSystemTrace(String detailText) {
		if (detailText == null || detailText.isEmpty()) {
			return detailText;
		}
		int traceStart = detailText.indexOf(ANR_TRACE_MARKER);
		if (traceStart < 0) {
			return detailText;
		}
		return detailText.substring(0, traceStart) + GITHUB_TRACE_NOTICE;
	}

	private static String build(LocalDiagnosticRepository.Record record, String detailText) {
		StringBuilder text = new StringBuilder();
		text.append("JL-Mod Plus diagnostic report\n");
		if (record.getTimestampMillis() > 0) {
			DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
			text.append("Time: ")
					.append(dateFormat.format(new Date(record.getTimestampMillis())))
					.append('\n');
		}
		if (detailText != null && !detailText.isEmpty()) {
			text.append('\n').append(detailText);
		}
		return text.toString();
	}

	static String buildBatch(List<LocalDiagnosticRepository.Record> records) {
		StringBuilder text = new StringBuilder();
		for (LocalDiagnosticRepository.Record record : records) {
			if (text.length() > 0) {
				text.append(BATCH_SEPARATOR);
			}
			text.append(build(record));
		}
		return text.toString();
	}
}
