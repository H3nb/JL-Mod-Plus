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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CameraConfigurationTest {
	@Test
	public void defaultSizeClassIsVgaAndBothOrientationsAreAdvertised() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse("capture://video");
		CameraConfiguration configuration = new CameraConfiguration(request);

		assertEquals(640, configuration.getStillWidth());
		assertEquals(480, configuration.getStillHeight());
		assertTrue(CameraConfiguration.snapshotEncodings()
				.startsWith("encoding=jpeg&width=640&height=480 "
						+ "encoding=jpeg&width=480&height=640"));
	}

	@Test
	public void explicitLocatorDimensionsAffectSourceButNotDefaultStillSize() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse(
				"capture://video?encoding=jpeg&width=480&height=640");
		CameraConfiguration configuration = new CameraConfiguration(request);

		assertEquals(480, configuration.getVideoWidth());
		assertEquals(640, configuration.getVideoHeight());
		assertEquals(640, configuration.getStillWidth());
		assertEquals(480, configuration.getStillHeight());
	}

	@Test
	public void unspecifiedSnapshotMatchesPortraitJavaViewfinder() throws Exception {
		CameraConfiguration configuration = new CameraConfiguration(
				CaptureLocatorParser.parse("capture://video"));
		configuration.setViewfinderSize(240, 320);
		SnapshotRequest resolved = configuration.resolveSnapshot(
				SnapshotEncodingParser.parse("encoding=jpeg&quality=77"));

		assertEquals(480, resolved.getWidth());
		assertEquals(640, resolved.getHeight());
		assertEquals(77, resolved.getQuality());
	}

	@Test
	public void unspecifiedSnapshotMatchesLandscapeJavaViewfinder() throws Exception {
		CameraConfiguration configuration = new CameraConfiguration(
				CaptureLocatorParser.parse("capture://video"));
		configuration.setViewfinderSize(320, 240);
		SnapshotRequest resolved = configuration.resolveSnapshot(
				SnapshotEncodingParser.parse(null));

		assertEquals(640, resolved.getWidth());
		assertEquals(480, resolved.getHeight());
	}

	@Test
	public void explicitSnapshotDimensionsRemainLiteralInPortraitViewfinder() throws Exception {
		CameraConfiguration configuration = new CameraConfiguration(
				CaptureLocatorParser.parse("capture://video"));
		configuration.setViewfinderSize(240, 320);
		SnapshotRequest resolved = configuration.resolveSnapshot(
				SnapshotEncodingParser.parse("encoding=jpeg&width=640&height=480"));

		assertEquals(640, resolved.getWidth());
		assertEquals(480, resolved.getHeight());
	}

	@Test
	public void compatibleCatalogIncludesClassicSquareAndPortraitSizes() {
		String encodings = CameraConfiguration.snapshotEncodings();
		assertTrue(encodings.contains("width=128&height=128"));
		assertTrue(encodings.contains("width=120&height=160"));
		assertTrue(encodings.contains("width=240&height=320"));
	}
}
