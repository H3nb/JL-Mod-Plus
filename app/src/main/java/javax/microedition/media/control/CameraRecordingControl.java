/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
	private static final int DESTINATION_NONE = 0;
	private static final int DESTINATION_STREAM = 1;
	private static final int DESTINATION_LOCATION = 2;

	private final CameraPlayer player;
	private final boolean withAudio;
	private final int width;
	private final int height;

	private OutputStream destination;
	private boolean destinationOwned;
	private int destinationKind = DESTINATION_NONE;
	private File recordingFile;
	private boolean recordingRequested;
	private boolean recordingActive;
	private boolean recordingCycleStarted;

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
		checkDestinationReplaceable(DESTINATION_STREAM);
		MidletMediaPermissionGate.requireRecordPermission();
		closeOwnedDestination();
		destination = stream;
		destinationOwned = false;
		destinationKind = DESTINATION_STREAM;
	}

	@Override
	public synchronized void setRecordLocation(String locator) throws IOException, MediaException {
		if (locator == null) {
			throw new IllegalArgumentException("record locator must not be null");
		}
		checkDestinationReplaceable(DESTINATION_LOCATION);
		MidletMediaPermissionGate.requireRecordPermission();
		OutputStream newDestination = Connector.openOutputStream(locator);
		closeOwnedDestination();
		destination = newDestination;
		destinationOwned = true;
		destinationKind = DESTINATION_LOCATION;
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
		recordingCycleStarted = true;
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
		if (recordingRequested || recordingActive) {
			stopForFinalization();
		}
		if (recordingFile == null) {
			finishRecordingCycle(true);
			throw new IOException("Camera recording was never started");
		}

		try {
			player.finalizeCameraRecording();
		} catch (MediaException e) {
			if (!player.hasCameraRecording()) {
				// CameraX has already delivered a terminal Finalize event. Do not let a
				// later commit treat its invalid temporary MP4 as a successful recording.
				finishRecordingCycle(true);
			}
			// If the backend still owns the recording (for example a timeout), keep
			// the current cycle intact so reset/commit/close can finish cleanup later.
			throw new IOException("Camera recording could not be finalized", e);
		}
		recordingActive = false;

		IOException failure = null;
		try {
			copyRecordingToDestination();
		} catch (IOException e) {
			failure = e;
		}
		try {
			finishCommittedRecordingCycle();
		} catch (IOException e) {
			if (failure == null) {
				failure = e;
			} else {
				failure.addSuppressed(e);
			}
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
		// CameraX can stop its temporary file at a byte limit, but this control
		// cannot yet perform the implicit JSR-135 commit when that limit is reached.
		// Report the optional feature as unsupported instead of exposing partial semantics.
		throw new MediaException("Camera record size limit is not supported yet");
	}

	@Override
	public synchronized void reset() throws IOException {
		if (destination == null && recordingFile == null) {
			return;
		}
		if (recordingRequested || recordingActive) {
			stopForFinalization();
		}
		if (recordingFile == null) {
			recordingCycleStarted = false;
			return;
		}
		try {
			player.finalizeCameraRecording();
		} catch (MediaException e) {
			if (!player.hasCameraRecording()) {
				// A terminally invalid recording cannot be reused after reset failure.
				finishRecordingCycle(true);
			}
			// Preserve a still-live backend recording so another reset/close can retry
			// cleanup rather than leaving CameraX poisoned behind cleared Java state.
			throw new IOException("Camera recording could not be reset", e);
		}
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
		deleteRecordingFile();
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
		boolean resuming = recordingFile != null;
		try {
			if (!resuming) {
				recordingFile = File.createTempFile(
						"jlmod-camera-record-", ".mp4", ContextHolder.getCacheDir());
				player.beginCameraRecording(recordingFile, withAudio, Long.MAX_VALUE, width, height);
			} else {
				player.resumeCameraRecording();
			}
			recordingActive = true;
			player.notifyRecordingEvent(PlayerListener.RECORD_STARTED, null);
		} catch (IOException | MediaException | RuntimeException e) {
			if (!resuming) {
				boolean finalized = false;
				try {
					player.finalizeCameraRecording();
					finalized = true;
				} catch (MediaException cleanupFailure) {
					e.addSuppressed(cleanupFailure);
				}
				if (finalized) {
					deleteRecordingFile();
				}
			}
			// On resume failure keep the existing file. The persistent CameraX
			// recording may still be paused and can be retried or finalized later.
			recordingActive = false;
			recordingRequested = false;
			player.notifyRecordingEvent(PlayerListener.RECORD_ERROR, e);
			throw new IllegalStateException("Camera recording could not start", e);
		}
	}

	/**
	 * Implements the stop portion of implicit commit/reset without allowing a
	 * CameraX pause failure to bypass finalization and leave the recording poisoned.
	 */
	private void stopForFinalization() {
		boolean wasRequested = recordingRequested;
		recordingRequested = false;
		if (recordingActive) {
			try {
				player.pauseCameraRecording();
				recordingActive = false;
			} catch (MediaException e) {
				player.notifyRecordingEvent(PlayerListener.RECORD_ERROR, e);
				// finalizeCameraRecording() below remains authoritative and can stop
				// an active CameraX recording even when pause itself failed.
			}
		}
		if (wasRequested) {
			player.notifyRecordingEvent(PlayerListener.RECORD_STOPPED, null);
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
		recordingCycleStarted = false;
		deleteRecordingFile();
		if (closeDestination) {
			closeOwnedDestination();
			destination = null;
			destinationOwned = false;
			destinationKind = DESTINATION_NONE;
		}
	}

	private void finishRecordingCycle(boolean closeDestination) {
		deleteRecordingFile();
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;
		if (closeDestination) {
			closeOwnedDestination();
			destination = null;
			destinationOwned = false;
			destinationKind = DESTINATION_NONE;
		}
	}

	private void finishCommittedRecordingCycle() throws IOException {
		deleteRecordingFile();
		recordingRequested = false;
		recordingActive = false;
		recordingCycleStarted = false;

		IOException closeFailure = null;
		if (destinationOwned && destination != null) {
			try {
				destination.close();
			} catch (IOException e) {
				closeFailure = new IOException("Camera recording destination could not be closed", e);
			}
		}
		destination = null;
		destinationOwned = false;
		destinationKind = DESTINATION_NONE;
		if (closeFailure != null) {
			throw closeFailure;
		}
	}

	private void checkDestinationReplaceable(int requestedKind) {
		if (recordingCycleStarted || recordingFile != null || recordingRequested || recordingActive) {
			throw new IllegalStateException("commit or reset the current recording first");
		}
		if (destinationKind != DESTINATION_NONE && destinationKind != requestedKind) {
			throw new IllegalStateException("commit before changing the record destination type");
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
			// Destination cleanup is best effort outside commit error paths.
		}
	}
}
