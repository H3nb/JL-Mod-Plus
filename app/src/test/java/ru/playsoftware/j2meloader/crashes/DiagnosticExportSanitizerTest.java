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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiagnosticExportSanitizerTest {
	@Test
	public void redactsKnownAppAndEmulatorPaths() {
		String input = "jar=/storage/emulated/0/JL-Mod Plus/converted/game/res.jar "
				+ "db=/data/user/0/ru.playsoftware.j2meloader/files/report.json";

		String sanitized = DiagnosticExportSanitizer.sanitize(
				input,
				"/storage/emulated/0/JL-Mod Plus",
				"/data/user/0/ru.playsoftware.j2meloader");

		assertTrue(sanitized.contains("<emulator-dir>"));
		assertTrue(sanitized.contains("<app-data>"));
		assertFalse(sanitized.contains("/storage/emulated/0/JL-Mod Plus"));
		assertFalse(sanitized.contains("/data/user/0/ru.playsoftware.j2meloader"));
	}

	@Test
	public void redactsUrisAndRemainingAbsolutePaths() {
		String input = "GET https://example.com/private?q=token "
				+ "content://com.example.provider/private/42 "
				+ "ftp://example.com/private.bin "
				+ "jar:file:/storage/emulated/0/a.jar!/secret.txt "
				+ "native=/vendor/lib64/libx.so windows=C:\\Users\\User\\secret.txt";

		String sanitized = DiagnosticExportSanitizer.sanitize(input, null, null);

		assertFalse(sanitized.contains("example.com"));
		assertFalse(sanitized.contains("com.example.provider"));
		assertFalse(sanitized.contains("/storage/emulated/0/a.jar"));
		assertFalse(sanitized.contains("/vendor/lib64/libx.so"));
		assertFalse(sanitized.contains("C:\\Users\\User"));
		assertTrue(sanitized.contains("<uri>"));
		assertTrue(sanitized.contains("<path>"));
	}

	@Test
	public void leavesJavaStackFramesReadable() {
		String input = "at javax.microedition.shell.MidletThread.handleMessage(MidletThread.java:123)";

		String sanitized = DiagnosticExportSanitizer.sanitize(input, null, null);

		assertTrue(sanitized.contains("MidletThread.handleMessage"));
		assertTrue(sanitized.contains("MidletThread.java:123"));
	}
}
