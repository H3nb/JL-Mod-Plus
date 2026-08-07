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
	public void defaultResolutionIsLandscapeVgaAndFirstAdvertisedEncodingMatches() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse("capture://video");
		CameraConfiguration configuration = new CameraConfiguration(request);

		assertEquals(640, configuration.getStillWidth());
		assertEquals(480, configuration.getStillHeight());
		assertTrue(CameraConfiguration.snapshotEncodings()
				.startsWith("encoding=jpeg&width=640&height=480"));
	}

	@Test
	public void explicitCatalogSourceBecomesInitialVirtualResolution() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse(
				"capture://video?encoding=jpeg&width=480&height=640");
		CameraConfiguration configuration = new CameraConfiguration(request);

		assertEquals(480, configuration.getStillWidth());
		assertEquals(640, configuration.getStillHeight());
	}

	@Test
	public void defaultSnapshotResolvesThroughConfigurationAndPreservesQuality() throws Exception {
		CaptureRequest request = CaptureLocatorParser.parse("capture://video");
		CameraConfiguration configuration = new CameraConfiguration(request);
		SnapshotRequest resolved = configuration.resolveSnapshot(
				SnapshotEncodingParser.parse("encoding=jpeg&quality=77"));

		assertEquals(640, resolved.getWidth());
		assertEquals(480, resolved.getHeight());
		assertEquals(77, resolved.getQuality());
	}
}
