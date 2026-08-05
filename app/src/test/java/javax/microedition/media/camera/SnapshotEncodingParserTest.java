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

package javax.microedition.media.camera;

import org.junit.Test;

import javax.microedition.media.MediaException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SnapshotEncodingParserTest {
	@Test
	public void nullUsesDeterministicDefault() throws Exception {
		SnapshotRequest request = SnapshotEncodingParser.parse(null);

		assertEquals(480, request.getWidth());
		assertEquals(640, request.getHeight());
	}

	@Test
	public void parsesExplicitJpegDimensionsInAnySupportedOrder() throws Exception {
		SnapshotRequest request = SnapshotEncodingParser.parse(
				"height=240&encoding=jpeg&width=320");

		assertEquals(320, request.getWidth());
		assertEquals(240, request.getHeight());
	}

	@Test
	public void acceptsPortraitMaximumWithinPixelBudget() throws Exception {
		SnapshotRequest request = SnapshotEncodingParser.parse(
				"encoding=jpeg&width=1536&height=2048");

		assertEquals(1536, request.getWidth());
		assertEquals(2048, request.getHeight());
	}

	@Test
	public void rejectsUnsupportedAndMalformedRequests() {
		assertThrows(MediaException.class, () ->
				SnapshotEncodingParser.parse("encoding=png"));
		assertThrows(IllegalArgumentException.class, () ->
				SnapshotEncodingParser.parse("encoding=jpeg&width=640"));
		assertThrows(IllegalArgumentException.class, () ->
				SnapshotEncodingParser.parse("encoding=jpeg&width=640&width=320&height=480"));
		assertThrows(IllegalArgumentException.class, () ->
				SnapshotEncodingParser.parse("encoding=jpeg&Width=640&width=320&height=480"));
		assertThrows(MediaException.class, () ->
				SnapshotEncodingParser.parse("encoding=jpeg&width=4096&height=2160"));
	}
}
