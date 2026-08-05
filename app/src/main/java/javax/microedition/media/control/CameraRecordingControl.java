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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.microedition.media.CameraPlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.camera.CaptureRequest;
import javax.microedition.util.ContextHolder;

/** JSR-135 RecordControl backed by the CameraX MP4 recording session. */
public final class CameraRecordingControl implements RecordControl {
	private static final String CONTENT_TYPE = "video/mp4";

	private final CameraPlayer player;
	private final boolean withAudio;

	private OutputStream destination;
	private boolean destinationOwned;
	private File recordingFile;
	private boolean recordingRequested;
	private boolean recordingActive;
	private int recordSizeLimit = Integer.MAX_VALUE;

	public CameraRecordingControl(CameraPlayer player, CaptureRequest request) {
		this.player = player;
		this.withAudio = request.isAudioVideo();
	}

	@Override
	public synchronized void setRecordStream(OutputStream stream) {
		if (stream == null) {
			throw new IllegalArgumentException("record stream must not be null");
		}
		checkNotActive();
		closeOwnedDestination();
		destination = stream;
		destinationOwned = false;
	}

	@Override
	public synchronized void setRecordLocation(String locator)
			throws IOException, MediaException {
		if (locator == null) {
			throw new IllegalArgumentException("record locator must not be null");
		}
		checkNotActive();
		OutputStream newDestination = Connector.openOutputStream(locator);
		closeOwnedDestination();
		destination = newDestination;
		destinationOwned = true;
	}

	@Override
	public synchronized String getContentType() {
		return CONTENT_TYPE;
	}

	@Override
	public synchronized void startRecord() {
		if (destination == null) {
			throw new IllegalStateException("setRecordStream or setRecordLocation first");
		}
		if (recordingRequested || recordingActive) {
			return;
		}
		recordingRequested = true;
		if (player.getState() == javax.microedition.media.Player.STARTED) {
			beginRecording();
		}
	}

	@Override
	public synchronized void stopRecord() {
		recordingRequested = false;
		if (!recordingActive) {
			return;
		}
		try {
			player.stopCameraRecording();
			recordingActive = false;
			player.notifyRecordingEvent(javax.microedition.media.PlayerListener.RECORD_STOPPED, null);
		} catch (MediaException e) {
			player.notifyRecordingEvent(javax.microedition.media.PlayerListener.RECORD_ERROR, e);
			throw new IllegalStateException("Camera recording could not stop", e);
		}
	}

	@Override
	public synchronized void commit() throws IOException {
		if (recordingActive) {
			stopRecord();
		}
		if (recordingFile == null) {
			throw new IllegalStateException("no camera recording is available");
		}
		if (destination == null) {
			throw new IllegalStateException("setRecordStream or setRecordLocation first");
		}
		try (FileInputStream input = new FileInputStream(recordingFile)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) >= 0) {
				if (count > 0) {
					destination.write(buffer, 0, count);
				}
			}
			destination.flush();
		} catch (IOException e) {
			deleteRecordingFile();
			throw e;
		}
		deleteRecordingFile();
		closeOwnedDestination();
		destination = null;
		destinationOwned = false;
		recordingRequested = false;
		player.notifyRecordingEvent(javax.microedition.media.PlayerListener.RECORD_STOPPED, null);
	}

	@Override
	public synchronized int setRecordSizeLimit(int size) throws MediaException {
		if (size <= 0) {
			throw new IllegalArgumentException("record size limit must be positive");
		}
		if (recordingActive) {
			throw new MediaException("record size limit cannot change while recording");
		}
		recordSizeLimit = size;
		return size;
	}

	@Override
	public synchronized void reset() throws IOException {
		if (recordingActive) {
			stopRecord();
		}
		deleteRecordingFile();
		recordingRequested = false;
	}

	/** Starts an armed recording when the Player becomes STARTED. */
	public synchronized void onPlayerStarted() {
		if (recordingRequested && !recordingActive) {
			beginRecording();
		}
	}

	/** Stops the physical recording when the Player is stopped. */
	public synchronized void onPlayerStopped() {
		if (!recordingActive) {
			return;
		}
		recordingActive = false;
		recordingRequested = false;
		player.notifyRecordingEvent(javax.microedition.media.PlayerListener.RECORD_STOPPED, null);
	}

	/** Releases the temporary file and any emulator-owned destination. */
	public synchronized void onPlayerClosed() {
		if (recordingActive) {
			try {
				player.stopCameraRecording();
			} catch (MediaException ignored) {
				// Closing is best effort; the temporary file is still removed below.
			}
		}
		recordingActive = false;
		recordingRequested = false;
		deleteRecordingFile();
		closeOwnedDestination();
		destination = null;
		destinationOwned = false;
	}

	private void beginRecording() {
		try {
			recordingFile = File.createTempFile(
					"jlmod-camera-record-", ".mp4", ContextHolder.getCacheDir());
			long limit = recordSizeLimit == Integer.MAX_VALUE
					? Long.MAX_VALUE : recordSizeLimit;
			player.startCameraRecording(recordingFile, withAudio, limit);
			recordingActive = true;
			player.notifyRecordingEvent(javax.microedition.media.PlayerListener.RECORD_STARTED, null);
		} catch (IOException | MediaException | RuntimeException e) {
			deleteRecordingFile();
			recordingActive = false;
			recordingRequested = false;
			player.notifyRecordingEvent(javax.microedition.media.PlayerListener.RECORD_ERROR, e);
			throw new IllegalStateException("Camera recording could not start", e);
		}
	}

	private void checkNotActive() {
		if (recordingActive || recordingRequested) {
			throw new IllegalStateException("camera recording is active or armed");
		}
	}

	private void deleteRecordingFile() {
		File file = recordingFile;
		recordingFile = null;
		if (file != null && file.exists() && !file.delete()) {
			file.deleteOnExit();
		}
	}

	private void closeOwnedDestination() {
		if (!destinationOwned || destination == null) {
			return;
		}
		try {
			destination.close();
		} catch (IOException ignored) {
			// Replacing a destination is best effort; the new destination remains authoritative.
		}
	}
}
