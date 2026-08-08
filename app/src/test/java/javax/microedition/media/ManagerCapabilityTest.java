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

package javax.microedition.media;

import org.junit.Test;

import java.util.Arrays;

import javax.microedition.media.camera.CaptureRequest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ManagerCapabilityTest {
	@Test
	public void captureProtocolAdvertisesImplementedLiveSources() {
		assertArrayEquals(
				new String[]{RecordPlayer.CONTENT_TYPE, CaptureRequest.CONTENT_TYPE},
				Manager.getSupportedContentTypes("capture"));
	}

	@Test
	public void liveCameraContentTypeRoutesOnlyToCaptureProtocol() {
		assertArrayEquals(
				new String[]{"capture"},
				Manager.getSupportedProtocols(CaptureRequest.CONTENT_TYPE));
	}

	@Test
	public void audioCaptureContentTypeStillIncludesCaptureProtocol() {
		assertTrue(Arrays.asList(Manager.getSupportedProtocols(RecordPlayer.CONTENT_TYPE))
				.contains("capture"));
	}

	@Test
	public void unsupportedCapabilityQueriesReturnEmptyArrays() {
		assertEquals(0, Manager.getSupportedContentTypes("rtsp").length);
		assertEquals(0, Manager.getSupportedProtocols("video/x-unsupported").length);
	}

	@Test
	public void nullCapabilityQueriesRemainNonEmpty() {
		assertTrue(Manager.getSupportedContentTypes(null).length > 0);
		assertTrue(Manager.getSupportedProtocols(null).length > 0);
	}

	@Test
	public void nullPlayerLocatorRemainsIllegalArgument() {
		assertThrows(IllegalArgumentException.class, () -> Manager.createPlayer((String) null));
	}

	@Test
	public void malformedCameraLocatorsUseMediaException() {
		assertThrows(MediaException.class, () ->
				Manager.createPlayer("capture://video?width=640"));
		assertThrows(MediaException.class, () ->
				Manager.createPlayer("capture://video?fps=0"));
		assertThrows(MediaException.class, () ->
				Manager.createPlayer("capture://?encoding=gray8"));
	}
}
