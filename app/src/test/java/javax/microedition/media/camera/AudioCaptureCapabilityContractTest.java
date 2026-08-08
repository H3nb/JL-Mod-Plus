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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AudioCaptureCapabilityContractTest {
	@Test
	public void advertisedAudioEncodingMatchesRecordPlayerBackend() {
		assertEquals(
				"encoding=audio/amr&rate=8000&channels=1",
				VirtualCameraCapabilities.AUDIO_ENCODING);
	}

	@Test
	public void captureSupportPropertiesMatchRuntimeCapabilities() {
		boolean audio = VirtualCameraCapabilities.supportsAudioCapture();
		boolean video = VirtualCameraCapabilities.supportsVideoCapture();

		assertEquals(Boolean.toString(audio),
				VirtualCameraCapabilities.systemProperty("supports.audio.capture"));
		assertEquals(Boolean.toString(video),
				VirtualCameraCapabilities.systemProperty("supports.video.capture"));
		assertEquals(Boolean.toString(audio || video),
				VirtualCameraCapabilities.systemProperty("supports.recording"));
	}

	@Test
	public void audioEncodingPropertiesArePresentExactlyWhenAudioCaptureIsSupported() {
		if (VirtualCameraCapabilities.supportsAudioCapture()) {
			assertEquals(VirtualCameraCapabilities.AUDIO_ENCODING,
					VirtualCameraCapabilities.systemProperty("audio.encoding"));
			assertEquals(VirtualCameraCapabilities.AUDIO_ENCODING,
					VirtualCameraCapabilities.systemProperty("audio.encodings"));
		} else {
			assertNull(VirtualCameraCapabilities.systemProperty("audio.encoding"));
			assertNull(VirtualCameraCapabilities.systemProperty("audio.encodings"));
		}
	}

	@Test
	public void videoAndSnapshotPropertiesArePresentExactlyWhenCameraCaptureIsSupported() {
		if (VirtualCameraCapabilities.supportsVideoCapture()) {
			assertEquals(VirtualCameraCapabilities.VIDEO_ENCODING,
					VirtualCameraCapabilities.systemProperty("video.encoding"));
			assertEquals(VirtualCameraCapabilities.VIDEO_ENCODING,
					VirtualCameraCapabilities.systemProperty("video.encodings"));
			String snapshot = VirtualCameraCapabilities.systemProperty("video.snapshot.encodings");
			assertTrue(snapshot.startsWith("encoding=jpeg"));
			assertEquals(snapshot,
					VirtualCameraCapabilities.systemProperty("video.snapshot.encoding"));
		} else {
			assertNull(VirtualCameraCapabilities.systemProperty("video.encoding"));
			assertNull(VirtualCameraCapabilities.systemProperty("video.encodings"));
			assertNull(VirtualCameraCapabilities.systemProperty("video.snapshot.encoding"));
			assertNull(VirtualCameraCapabilities.systemProperty("video.snapshot.encodings"));
		}
	}
}
