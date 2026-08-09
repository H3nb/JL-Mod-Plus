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
import java.util.List;

import javax.microedition.media.camera.CaptureRequest;
import javax.microedition.media.camera.VirtualCameraCapabilities;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ManagerCapabilityTest {
	@Test
	public void captureProtocolMatchesRuntimeHardwareCapabilities() {
		List<String> types = Arrays.asList(Manager.getSupportedContentTypes("capture"));
		assertEquals(VirtualCameraCapabilities.supportsAudioCapture(),
				types.contains(RecordPlayer.CONTENT_TYPE));
		assertEquals(VirtualCameraCapabilities.supportsVideoCapture(),
				types.contains(CaptureRequest.CONTENT_TYPE));
	}

	@Test
	public void liveCameraContentTypeRoutesToCaptureOnlyWhenCameraExists() {
		List<String> protocols = Arrays.asList(
				Manager.getSupportedProtocols(CaptureRequest.CONTENT_TYPE));
		assertEquals(VirtualCameraCapabilities.supportsVideoCapture(),
				protocols.contains("capture"));
	}

	@Test
	public void amrAudioCaptureProtocolMatchesMicrophoneCapability() {
		List<String> amrProtocols = Arrays.asList(
				Manager.getSupportedProtocols(RecordPlayer.CONTENT_TYPE));
		assertEquals(VirtualCameraCapabilities.supportsAudioCapture(),
				amrProtocols.contains("capture"));
		assertFalse(amrProtocols.contains("device"));
		assertTrue(amrProtocols.contains("https"));
		assertFalse(Arrays.asList(Manager.getSupportedProtocols("audio/amr-wb"))
				.contains("capture"));
	}

	@Test
	public void deviceProtocolAdvertisesOnlyMidiAndToneDevices() {
		assertArrayEquals(
				new String[]{"audio/midi", "audio/x-midi", "audio/x-tone-seq"},
				Manager.getSupportedContentTypes("device"));
	}

	@Test
	public void sequencedAudioSupportsDeviceAndStreamProtocols() {
		assertArrayEquals(
				new String[]{"device", "file", "http", "https", "resource"},
				Manager.getSupportedProtocols(" Audio/MIDI; charset=binary "));
	}

	@Test
	public void decodedAudioDoesNotClaimDeviceProtocol() {
		assertArrayEquals(
				new String[]{"file", "http", "https", "resource"},
				Manager.getSupportedProtocols("audio/x-wav"));
	}

	@Test
	public void smafAdvertisesRegisteredContentTypeForStreamProtocolsOnly() {
		List<String> all = Arrays.asList(Manager.getSupportedContentTypes(null));
		List<String> fileTypes = Arrays.asList(Manager.getSupportedContentTypes("file"));
		assertTrue(all.contains("application/vnd.smaf"));
		assertTrue(fileTypes.contains("application/vnd.smaf"));
		assertArrayEquals(
				new String[]{"file", "http", "https", "resource"},
				Manager.getSupportedProtocols(" Application/Vnd.Smaf; charset=binary "));
		assertFalse(Arrays.asList(Manager.getSupportedContentTypes("device"))
				.contains("application/vnd.smaf"));
	}

	@Test
	public void legacyAudioMmfAliasIsNotAdvertised() {
		List<String> all = Arrays.asList(Manager.getSupportedContentTypes(null));
		assertFalse(all.contains("audio/mmf"));
		assertEquals(0, Manager.getSupportedProtocols("audio/mmf").length);
	}

	@Test
	public void unsupportedCapabilityQueriesReturnEmptyArrays() {
		assertEquals(0, Manager.getSupportedContentTypes("rtsp").length);
		assertEquals(0, Manager.getSupportedProtocols("video/x-unsupported").length);
	}

	@Test
	public void nullCapabilityQueriesRemainNonEmptyAndIncludeHttps() {
		assertTrue(Manager.getSupportedContentTypes(null).length > 0);
		assertTrue(Arrays.asList(Manager.getSupportedProtocols(null)).contains("https"));
	}

	@Test
	public void nullProtocolAdvertisesCaptureOnlyWhenAnyCapturePathExists() {
		List<String> protocols = Arrays.asList(Manager.getSupportedProtocols(null));
		boolean expected = VirtualCameraCapabilities.supportsAudioCapture()
				|| VirtualCameraCapabilities.supportsVideoCapture();
		assertEquals(expected, protocols.contains("capture"));
	}

	@Test
	public void nullPlayerLocatorRemainsIllegalArgument() {
		assertThrows(IllegalArgumentException.class, () -> Manager.createPlayer((String) null));
	}

	@Test
	public void unsupportedPlayerLocatorUsesMediaExceptionInsteadOfNoOpPlayer() {
		assertThrows(MediaException.class, () -> Manager.createPlayer("rtsp://example.invalid/audio"));
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
