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

package ru.woesss.j2me.mmapi.audio;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioFailureReportStoreTest {
	@Test
	public void savesAndReadsPrivateReport() throws IOException {
		Path directory = Files.createTempDirectory("audio-reports");
		AudioFailure failure = AudioFailure.createWithDetail("song.mid", "audio/midi", "EAS",
				AudioFailure.Phase.START, "NATIVE_START_FAILED", "test failure");

		String reportId = AudioFailureReportStore.save(directory.toFile(), failure);
		String report = AudioFailureReportStore.read(directory.toFile(), reportId);

		assertTrue(report.contains("song.mid"));
		assertTrue(AudioFailureReportStore.delete(directory.toFile(), reportId));
		assertFalse(AudioFailureReportStore.delete(directory.toFile(), reportId));
	}

	@Test
	public void rejectsTraversalReportIds() throws IOException {
		Path directory = Files.createTempDirectory("audio-reports");
		try {
			AudioFailureReportStore.read(directory.toFile(), "../outside");
			throw new AssertionError("Expected invalid report id");
		} catch (IOException expected) {
			assertEquals("Invalid audio report id", expected.getMessage());
		}
	}
}
