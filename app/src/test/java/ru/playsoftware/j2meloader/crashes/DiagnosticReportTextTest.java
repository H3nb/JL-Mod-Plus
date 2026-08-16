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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiagnosticReportTextTest {
	@Test
	public void githubDraftOmitsEmbeddedAnrTrace() {
		String detail = "Type: Process exit diagnostic\n"
				+ "Exit reason: ANR (6)\n"
				+ "System trace: captured, 1024 bytes (anr-text)\n"
				+ "\nANR trace:\n"
				+ "raw system evidence\n"
				+ "/data/user/0/example/private.txt";

		String githubDetail = DiagnosticReportText.removeRawSystemTrace(detail);

		assertTrue(githubDetail.contains("System trace: captured, 1024 bytes"));
		assertTrue(githubDetail.contains("retained locally"));
		assertFalse(githubDetail.contains("raw system evidence"));
		assertFalse(githubDetail.contains("/data/user/0/example/private.txt"));
	}

	@Test
	public void githubDraftLeavesNonTraceDetailUnchanged() {
		String detail = "Type: Java diagnostic report\nStack trace:\njava.lang.IllegalStateException";

		assertEquals(detail, DiagnosticReportText.removeRawSystemTrace(detail));
	}
}
