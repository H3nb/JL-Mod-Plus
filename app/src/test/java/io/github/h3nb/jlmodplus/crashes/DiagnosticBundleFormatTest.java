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

package io.github.h3nb.jlmodplus.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.Test;

public class DiagnosticBundleFormatTest {
	@Test
	public void bundleIsReadableVersionedAndAddsOnlyAvailableEvidence() throws Exception {
		IncidentSummary incident = new IncidentSummary(
				IncidentSummary.Category.ANR,
				1234L,
				"Example",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				"deadbeef · emulatorDebug",
				"Android SDK 36 · Device",
				"midlet",
				Collections.emptyList(),
				null);
		String json = DiagnosticBundleFormat.incidentJson(incident);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		DiagnosticBundleFormat.write(
				bytes,
				"# report\n",
				json,
				"main thread trace",
				null);

		Map<String, String> entries = unzip(bytes.toByteArray());
		assertEquals(3, entries.size());
		assertEquals("# report\n", entries.get(DiagnosticBundleFormat.REPORT_ENTRY));
		assertTrue(entries.get(DiagnosticBundleFormat.INCIDENT_ENTRY)
				.contains("\"formatVersion\": 1"));
		assertEquals("main thread trace", entries.get(DiagnosticBundleFormat.ANR_ENTRY));
		assertFalse(entries.containsKey(DiagnosticBundleFormat.TOMBSTONE_ENTRY));
	}

	@Test
	public void sanitizesValuesBeforeJsonEscaping() {
		IncidentSummary.JavaFailure failure = new IncidentSummary.JavaFailure(
				"java.lang.IllegalStateException",
				"failed at content://private.provider/item/42",
				"example.Game.run(Game.java:42)");
		IncidentSummary incident = new IncidentSummary(
				IncidentSummary.Category.MIDLET_CRASH,
				1234L,
				"Example",
				failure,
				null,
				null,
				failure.frame,
				null,
				null,
				null,
				null,
				null,
				"midlet",
				Collections.emptyList(),
				null);

		String json = DiagnosticBundleFormat.incidentJson(
				incident,
				null,
				value -> DiagnosticExportSanitizer.sanitize(value, null, null));

		assertTrue(json.contains("\"failureMessage\": \"failed at <uri>\""));
		assertFalse(json.contains("private.provider"));
		assertTrue(json.trim().endsWith("}"));
	}

	@Test
	public void optionalTombstoneSummaryUsesSeparateTextEntry() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DiagnosticBundleFormat.write(
				bytes,
				"# report\n",
				"{\"formatVersion\":1}\n",
				null,
				"Native crash details\nSignal: SIGSEGV (11)");

		Map<String, String> entries = unzip(bytes.toByteArray());
		assertTrue(entries.containsKey(DiagnosticBundleFormat.TOMBSTONE_ENTRY));
		assertFalse(entries.keySet().stream().anyMatch(name -> name.endsWith(".pb")));
		assertFalse(entries.keySet().stream().anyMatch(name -> name.endsWith(".proto")));
	}

	private static Map<String, String> unzip(byte[] bytes) throws Exception {
		Map<String, String> result = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(
				new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			byte[] buffer = new byte[1024];
			while ((entry = zip.getNextEntry()) != null) {
				ByteArrayOutputStream content = new ByteArrayOutputStream();
				int count;
				while ((count = zip.read(buffer)) != -1) content.write(buffer, 0, count);
				result.put(entry.getName(), content.toString(StandardCharsets.UTF_8));
			}
		}
		return result;
	}
}
