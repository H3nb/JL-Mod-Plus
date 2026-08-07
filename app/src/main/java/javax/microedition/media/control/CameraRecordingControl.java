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
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.camera.CaptureRequest;
import javax.microedition.media.camera.MidletMediaPermissionGate;
import javax.microedition.util.ContextHolder;

/** JSR-135 RecordControl backed by one resumable CameraX MP4 recording. */
public final class CameraRecordingControl implements RecordControl {
	private static final String CONTENT_TYPE = "video/mp4";

	private final CameraPlayer player;
	private final boolean withAudio;
	private final int width;
	private final int height;

	private OutputStream destination;
	private boolean destinationOwned;
	private File recordingFile;
	private boolean recordingRequested;
	private boolean recordingActive;

	public CameraRecordingControl(CameraPlayer player, CaptureRequest request) {
		this.player = player;
		this.withAudio = request.isAudioVideo();
		this.width = request.getWidth();
		this.height = request.getHeight();
	}

	@Override
	public synchronized void setRecordStream(OutputStream stream) {
		if (stream == null) {
			throw new IllegalArgumentException("record stream must not be null");
		}
		checkDestinationReplaceable();
		MidletMediaPermissionGate.requireRecordPermission();
		closeOwnedDestination();
		destination = stream;
		destinationOwned = false;
	}

	@Override
	public synchronized void setRecordLocation(String locator) throws IOException, MediaException {
		if (locator == null) {
			throw new IllegalArgumentException("record locator must not be null");
		}
		checkDestinationReplaceable();
		MidletMediaPermissionGate.requireRecordPermission();
		OutputStream newDestination = Connector.openOutputStream(locator);
		closeOwnedDestination();
		destination = newDestination;
		destinationOwned = true;
	}

	@Override
	public String getContentType() {
		return CONTENT_TYPE;
	}

	@Override
	public synchronized void startRecord() {
		if (destination == null) {
			throw new IllegalStateException("setRecordStream or setRecordLocation first");
		}
		if (recordingRequested) {
			return;
		}
		recordingRequested = true;
		if (player.getState() == Player.STARTED) {
			startOrResumePhysicalRecording();
		}
	}

	@Override
	public synchronized void stopRecord() {
		if (!recordingRequested && !recordingActive) {
			return;
		}
		boolean wasRequested = recordingRequested;
		recordingRequested = false;
		if (recordingActive) {
			try {
				player.pauseCameraRecording();
				recordingActive = false;
			} catch (MediaException e) {
				player.notifyRecordingEvent(PlayerListener.RECORD_ERROR, e);
				throw new IllegalStateException("Camera recording could not pause", e);
			}
		}
		if (wasRequested) {
			player.notifyRecordingEvent(PlayerListener.RECORD_STOPPED, null);
		}
	}

	@Override
	public synchronized void commit() throws IOException {
		if (destination == null) {
			throw new IllegalStateException("setRecordStream or setRecordLocation first");
		}
		if (recordingRequested || recordingActive) {
			stopRecord();
		}
		if (recordingFile == null) {
			throw new IllegalStateException("no camera recording is available");
		}

		IOException failure = null;
		try {
			player.finalizeCameraRecording();
			copyRecordingToDestination();
		} catch (MediaException e) {
			failure = new IOException("Camera recording could not be finalized", e);
		} catch (IOException e) {
			failure = e;
		} finally {
			deleteRecordingFile();
			closeOwnedDestination();
			destination = null;
			destinationOwned = false;
			recordingRequested = false;
			recordingActive = false;
		}
		if (failure != null) {
			throw failure;
		}
	}

	@Override
	public synchronized int setRecordSizeLimit(int size) throws MediaException {
		if (size <= 0) {
			throw new IllegalArgumentException("record size limit must be positive");
		}
		throw new MediaException("Camera record size limit is not supported yet");
	}

	@Override
	public synchronized void reset() throws IOException {
		if (destination == null && recordingFile == null) {
			return;
		}
		if (recordingRequested || recordingActive) {
			stopRecord();
		}
		if (recordingFile == null) {
			return;
		}
		try {
			player.finalizeCameraRecording();
			deleteRecordingFile();
		} catch (MediaException e) {
			invalidateAfterFailure();
			throw new IOException("Camera recording could not be reset", e);
		}
	}

	/** Starts or resumes an armed recording after the Player enters STARTED. */
	public synchronized void onPlayerStarted() {
		if (recordingRequested && !recordingActive) {
			startOrResumePhysicalRecording();
		}
	}

	/** Puts an active recording into MMAPI standby before Player.stop() unbinds the camera. */
	public synchronized void onPlayerStopping() {
		if (!recordingActive) {
			return;
		}
		try {
			player.pauseCameraRecording();
			recordingActive = false;
		} catch (MediaException e) {
			recordingRequested = false;
			player.notifyRecordingEvent(PlayerListener.RECORD_ERROR, e);
		}
	}

	/** Discards an unfinished recording before camera resources are deallocated. */
	public synchronized void onPlayerDeallocated() {
		discardCurrentRecording(false);
	}

	/** Implements RecordControl.reset-on-close and releases an emulator-owned destination. */
	public synchronized void onPlayerClosed() {
		discardCurrentRecording(true);
	}

	private void startOrResumePhysicalRecording() {
		try {
			if (recordingFile == null) {
				recordingFile = File.createTempFile(
						"jlmod-camera-record-", ".mp4", ContextHolder.getCacheDir());
				player.beginCameraRecording(recordingFile, withAudio, Long.MAX_VALUE, width, height);
			} else {
				player.resumeCameraRecording();
			}
			recordingActive = true;
			player.notifyRecordingEvent(PlayerListener.RECORD_STARTED, null);
		} catch (IOException | MediaException | RuntimeException e) {
			deleteRecordingFile();
			recordingActive = false;
			recordingRequested = false;
			player.notifyRecordingEvent(PlayerListener.RECORD_ERROR, e);
			throw new IllegalStateException("Camera recording could not start", e);
		}
	}

	private void copyRecordingToDestination() throws IOException {
		try (FileInputStream input = new FileInputStream(recordingFile)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) != -1) {
				if (count > 0) {
					destination.write(buffer, 0, count);
				}
			}
			destination.flush();
		}
	}

	private void discardCurrentRecording(boolean closeDestination) {
		if (recordingActive) {
			try {
				player.pauseCameraRecording();
			} catch (MediaException ignored) {
				// The recording is discarded below regardless of the pause result.
			}
		}
		if (recordingFile != null) {
			try {
				player.finalizeCameraRecording();
			} catch (MediaException ignored) {
				// Player release is the final fallback for a backend that cannot finalize.
			}
		}
		recordingRequested = false;
		recordingActive = false;
		deleteRecordingFile();
		if (closeDestination) {
			closeOwnedDestination();
			destination = null;
			destinationOwned = false;
		}
	}

	private void invalidateAfterFailure() {
		deleteRecordingFile();
		closeOwnedDestination();
		destination = null;
		destinationOwned = false;
		recordingRequested = false;
		recordingActive = false;
	}

	private void checkDestinationReplaceable() {
		if (recordingFile != null || recordingRequested || recordingActive) {
			throw new IllegalStateException("commit or reset the current recording first");
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
			// Destination cleanup is best effort outside commit/reset error paths.
		}
	}
}
