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
import static org.junit.Assert.assertTrue;

public class SnapshotEncodingParserTest {
	@Test
	public void nullAndJpegShorthandUseVirtualDefault() throws Exception {
		SnapshotRequest nullRequest = SnapshotEncodingParser.parse(null);
		SnapshotRequest shorthand = SnapshotEncodingParser.parse("JPEG");

		assertTrue(nullRequest.isDefaultResolution());
		assertTrue(shorthand.isDefaultResolution());
		assertEquals(90, nullRequest.getQuality());
	}

	@Test
	public void parsesExplicitJpegDimensionsAndQuality() throws Exception {
		SnapshotRequest request = SnapshotEncodingParser.parse(
				"height=240&quality=80&encoding=jpeg&width=320");

		assertEquals(320, request.getWidth());
		assertEquals(240, request.getHeight());
		assertEquals(80, request.getQuality());
	}

	@Test
	public void acceptsPortraitAndLandscapeMaximumWithinPixelBudget() throws Exception {
		SnapshotRequest portrait = SnapshotEncodingParser.parse(
				"encoding=jpeg&width=1536&height=2048");
		SnapshotRequest landscape = SnapshotEncodingParser.parse(
				"encoding=jpeg&width=2048&height=1536");

		assertEquals(1536, portrait.getWidth());
		assertEquals(2048, portrait.getHeight());
		assertEquals(2048, landscape.getWidth());
		assertEquals(1536, landscape.getHeight());
	}

	@Test
	public void encodingOnlyUsesDefaultResolution() throws Exception {
		SnapshotRequest request = SnapshotEncodingParser.parse("encoding=jpeg&quality=75");
		assertTrue(request.isDefaultResolution());
		assertEquals(75, request.getQuality());
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
		assertThrows(IllegalArgumentException.class, () ->
				SnapshotEncodingParser.parse("encoding=jpeg&quality=101"));
		assertThrows(MediaException.class, () ->
				SnapshotEncodingParser.parse("encoding=jpeg&width=4096&height=2160"));
	}
}
