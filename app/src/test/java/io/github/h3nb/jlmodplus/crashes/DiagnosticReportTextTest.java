/*
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

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiagnosticReportTextTest {
	@Test
	public void nativeSummaryIsInsertedImmediatelyAfterFailure() {
		String report = "JL-Mod Plus diagnostic report\n\n"
				+ "Type: Process exit diagnostic\n"
				+ "Failure: Native crash · SIGSEGV (11)\n\n"
				+ "Last app context\nLocation: activity.microactivity";
		String nativeSummary = "Native crash details\nSignal code: SEGV_MAPERR (1)";

		String result = DiagnosticReportText.withNativeSummary(report, nativeSummary);

		int failure = result.indexOf("Failure: Native crash");
		int nativeDetails = result.indexOf("Native crash details");
		int appContext = result.indexOf("Last app context");
		assertTrue(failure >= 0);
		assertTrue(nativeDetails > failure);
		assertTrue(appContext > nativeDetails);
	}

	@Test
	public void nativeSummaryFallsBackToEndWhenFailureLineIsUnavailable() {
		String report = "JL-Mod Plus diagnostic report\n\nType: Process exit diagnostic";
		String nativeSummary = "Native crash details\nSignal: SIGSEGV (11)";

		String result = DiagnosticReportText.withNativeSummary(report, nativeSummary);

		assertTrue(result.startsWith(report));
		assertTrue(result.endsWith(nativeSummary));
	}
}
