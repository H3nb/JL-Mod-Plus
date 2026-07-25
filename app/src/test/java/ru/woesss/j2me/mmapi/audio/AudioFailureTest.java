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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioFailureTest {
	@Test
	public void reportRedactsFullSourcePath() {
		AudioFailure failure = AudioFailure.create(
				"file:///private/user/save/music/unknown.adpcm?token=secret",
				"audio/x-unknown", "Android MediaPlayer", AudioFailure.Phase.START,
				"MEDIA_PLAYER_START_FAILED", new IllegalStateException("decoder rejected /decoder/rejected/input"));

		String report = failure.toReportText();
		assertTrue(report.contains("Source: unknown.adpcm"));
		assertFalse(report.contains("/private/user/save"));
		assertFalse(report.contains("token=secret"));
		assertFalse(report.contains("/decoder/rejected"));
		assertTrue(report.contains("MEDIA_PLAYER_START_FAILED"));
	}

	@Test
	public void reportBoundsUntrustedErrorDetail() {
		String detail = "x".repeat(1000);
		AudioFailure failure = AudioFailure.createWithDetail("music.mid", "audio/midi", "EAS",
				AudioFailure.Phase.RUNTIME, "NATIVE_RUNTIME_ERROR", detail);

		assertTrue(failure.toReportText().length() < 1200);
	}
}
