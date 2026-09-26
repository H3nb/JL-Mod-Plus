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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiagnosticExportSanitizerTest {
	@Test
	public void redactsKnownRootsButPreservesUsefulSuffixes() {
		String input = "jar=/storage/emulated/0/JL-Mod Plus/converted/game/res.jar "
				+ "db=/data/user/0/io.github.h3nb.jlmodplus/files/report.json";

		String sanitized = DiagnosticExportSanitizer.sanitize(
				input,
				"/storage/emulated/0/JL-Mod Plus",
				"/data/user/0/io.github.h3nb.jlmodplus");

		assertTrue(sanitized.contains("<emulator-dir>/converted/game/res.jar"));
		assertTrue(sanitized.contains("<app-data>/files/report.json"));
		assertFalse(sanitized.contains("/storage/emulated/0/JL-Mod Plus"));
		assertFalse(sanitized.contains("/data/user/0/io.github.h3nb.jlmodplus"));
	}

	@Test
	public void preservesAndroidTechnicalModulePaths() {
		String input = "/system/lib64/liba.so /apex/com.android.runtime/lib64/libb.so "
				+ "/vendor/lib64/libc.so /product/lib64/libd.so";

		String sanitized = DiagnosticExportSanitizer.sanitize(input, null, null);

		assertTrue(sanitized.contains("/system/lib64/liba.so"));
		assertTrue(sanitized.contains("/apex/com.android.runtime/lib64/libb.so"));
		assertTrue(sanitized.contains("/vendor/lib64/libc.so"));
		assertTrue(sanitized.contains("/product/lib64/libd.so"));
	}

	@Test
	public void stripsUriSecretsWithoutDestroyingUsefulHttpContext() {
		String input = "GET https://user:secret@example.com/api/crash?id=42&token=abc#fragment "
				+ "content://com.example.provider/private/42";

		String sanitized = DiagnosticExportSanitizer.sanitize(input, null, null);

		assertTrue(sanitized.contains("https://example.com/api/crash"));
		assertFalse(sanitized.contains("user:secret"));
		assertFalse(sanitized.contains("token=abc"));
		assertFalse(sanitized.contains("#fragment"));
		assertFalse(sanitized.contains("com.example.provider"));
		assertTrue(sanitized.contains("<uri>"));
	}

	@Test
	public void redactsUnownedUserStoragePaths() {
		String input = "save=/storage/emulated/0/Personal/MyGame/save.dat "
				+ "linux=/home/hendra/private.txt windows=C:\\Users\\User\\secret.txt";

		String sanitized = DiagnosticExportSanitizer.sanitize(input, null, null);

		assertFalse(sanitized.contains("Personal/MyGame"));
		assertFalse(sanitized.contains("/home/hendra"));
		assertFalse(sanitized.contains("C:\\Users\\User"));
		assertTrue(sanitized.contains("<user-path>"));
	}

	@Test
	public void keepsDiagnosticMetadataAndJavaFramesReadable() {
		String input = "Build: abc123 · emulatorDebug\n"
				+ "MIDlet: Example\nProcess: midlet\nFingerprint: deadbeef\n"
				+ "at javax.microedition.shell.MidletThread.handleMessage(MidletThread.java:123)";

		String sanitized = DiagnosticExportSanitizer.sanitize(input, null, null);

		assertTrue(sanitized.contains("abc123"));
		assertTrue(sanitized.contains("Example"));
		assertTrue(sanitized.contains("deadbeef"));
		assertTrue(sanitized.contains("MidletThread.handleMessage"));
		assertTrue(sanitized.contains("MidletThread.java:123"));
	}
}
