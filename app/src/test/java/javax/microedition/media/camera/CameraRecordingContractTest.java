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

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Keeps the backend boundary aligned with MMAPI standby/resume semantics. */
public class CameraRecordingContractTest {
	@Test
	public void recordingSessionExposesPauseResumeAndFinalizeSeparately() throws Exception {
		Method begin = CameraRecordingSession.class.getMethod(
				"beginRecording", java.io.File.class, boolean.class, long.class,
				int.class, int.class);
		Method pause = CameraRecordingSession.class.getMethod("pauseRecording");
		Method resume = CameraRecordingSession.class.getMethod("resumeRecording");
		Method finalize = CameraRecordingSession.class.getMethod("finalizeRecording");

		assertNotNull(begin);
		assertNotNull(pause);
		assertNotNull(resume);
		assertNotNull(finalize);
	}

	@Test
	public void videoEncodingsAdvertiseTheRecordControlContainer() {
		assertEquals("encoding=video/mp4", VirtualCameraCapabilities.VIDEO_ENCODING);
		assertEquals("video/mp4", CaptureRequest.DEFAULT_RECORDING_ENCODING);
	}
}
