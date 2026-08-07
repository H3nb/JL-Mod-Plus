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

package javax.microedition.media.control;

import java.io.IOException;
import java.io.OutputStream;

import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.camera.CaptureRequest;

/**
 * Compile-time placeholder for the camera recording implementation. Camera
 * RecordControl is intentionally not exposed by CameraPlayer until its MMAPI
 * standby/resume/commit contract is implemented on the recording branch.
 */
public final class CameraRecordingControl implements RecordControl {
	public CameraRecordingControl(CameraPlayer player, CaptureRequest request) {
	}

	@Override
	public void setRecordStream(OutputStream stream) {
		if (stream == null) {
			throw new IllegalArgumentException("record stream must not be null");
		}
		throw unavailable();
	}

	@Override
	public void setRecordLocation(String locator) throws IOException, MediaException {
		if (locator == null) {
			throw new IllegalArgumentException("record locator must not be null");
		}
		throw new MediaException("Camera recording is not enabled");
	}

	@Override
	public String getContentType() {
		return "video/mp4";
	}

	@Override
	public void startRecord() {
		throw unavailable();
	}

	@Override
	public void stopRecord() {
	}

	@Override
	public void commit() throws IOException {
		throw unavailable();
	}

	@Override
	public int setRecordSizeLimit(int size) throws MediaException {
		if (size <= 0) {
			throw new IllegalArgumentException("record size limit must be positive");
		}
		throw new MediaException("Camera recording is not enabled");
	}

	@Override
	public void reset() throws IOException {
	}

	public void onPlayerStarted() {
	}

	public void onPlayerStopped() {
	}

	public void onPlayerClosed() {
	}

	private static IllegalStateException unavailable() {
		return new IllegalStateException("Camera RecordControl is not enabled");
	}
}
